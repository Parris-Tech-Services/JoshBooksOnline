"""Read-only access to the shared Google Drive TV library, without credentials.

Folder listings come from Drive's lightweight embedded folder view (the full
drive.google.com page now returns an empty body or a format the old scraper
could not read). Downloads use the direct-download endpoint the earlier
scripts proved, resumed with HTTP ranges so a run cut short keeps its bytes.
Everything Drive returns is untrusted: a page that is not a listing is a
failure, never an empty folder, and an HTML response is never media.
"""
from __future__ import annotations

import os
import re
from dataclasses import dataclass
from pathlib import Path

from .names import clean_text
from .process import Runner
from .settings import Settings

MAX_PAGE_BYTES = 5_000_000
MAX_ENTRIES = 2000
MIN_UNKNOWN_SIZE_BYTES = 1_000_000
CURL_RANGE_UNSUPPORTED = 33
CURL_TIMED_OUT = 28
_LISTING_MARK = 'class="flip-entries"'
_ENTRY_SPLIT = '<div class="flip-entry"'
_HREF = re.compile(r'href="https://drive\.google\.com/(drive/folders|file/d)/([A-Za-z0-9_-]{10,128})')
_TITLE = re.compile(r'<div class="flip-entry-title">(.*?)</div>', re.S)


@dataclass(frozen=True)
class Entry:
    name: str
    id: str
    is_folder: bool


class DriveError(RuntimeError):
    """A Drive request failed.

    throttled: Drive sent a web page instead of data, so back off the whole run.
    permanent: the file is gone (404/410); retrying will not help.
    timed_out: the run's time ran out mid-download; the bytes so far are kept to resume.
    """

    def __init__(self, message: str, throttled: bool = False, permanent: bool = False, timed_out: bool = False):
        super().__init__(message)
        self.throttled = throttled
        self.permanent = permanent
        self.timed_out = timed_out


def parse_listing(page: str) -> list[Entry] | None:
    """Entries in an embedded folder view, or None when the page is not a listing."""
    if _LISTING_MARK not in page:
        return None
    entries = []
    for block in page.split(_ENTRY_SPLIT)[1 : MAX_ENTRIES + 1]:
        href, title = _HREF.search(block), _TITLE.search(block)
        if not href or not title:
            continue
        name = clean_text(title.group(1), 200)
        if name:
            entries.append(Entry(name=name, id=href.group(2), is_folder=href.group(1) == "drive/folders"))
    return entries


def _header_values(headers: str, name: str) -> list[str]:
    prefix = name.lower() + ":"
    return [line.split(":", 1)[1].strip() for line in headers.splitlines() if line.lower().startswith(prefix)]


def _truncate(path: Path, size: int) -> None:
    if path.exists():
        os.truncate(path, size)


class DriveClient:
    def __init__(self, settings: Settings, runner: Runner):
        self.settings = settings
        self.runner = runner

    def _curl(self, *args: str) -> list[str]:
        return ["curl", "-sS", "-L", "-A", self.settings.user_agent, *args]

    def list_folder(self, folder_id: str) -> list[Entry]:
        url = self.settings.list_url.format(id=folder_id)
        result = self.runner.run(
            self._curl("--fail", "--max-time", "60", "--max-filesize", str(MAX_PAGE_BYTES), url), 90
        )
        if not result.ok:
            raise DriveError(f"listing {folder_id} failed: {result.stderr.strip()[:200] or result.returncode}")
        entries = parse_listing(result.stdout)
        if entries is None:
            raise DriveError(f"listing {folder_id} returned a page that is not a folder listing", throttled=True)
        return entries

    def probe_size(self, drive_id: str) -> int | None:
        """Total size from a 1-byte ranged GET; HEAD is unreliable once Drive starts throttling."""
        url = self.settings.download_url.format(id=drive_id)
        result = self.runner.run(self._curl("--max-time", "45", "-r", "0-0", "-D", "-", "-o", os.devnull, url), 60)
        types = _header_values(result.stdout, "content-type")
        ranges = _header_values(result.stdout, "content-range")
        if not result.ok or not ranges or (types and types[-1].startswith("text/html")):
            return None
        total = ranges[-1].rsplit("/", 1)[-1].strip()
        return int(total) if total.isdigit() else None

    def download(self, drive_id: str, part: Path, expected: int | None, timeout: int) -> int:
        """Fetch into `part`, resuming earlier bytes. Returns the finished size or raises DriveError."""
        before = part.stat().st_size if part.exists() else 0
        if expected and before == expected:
            return before
        if before and (not expected or before > expected):
            # Resuming is only safe against a known total; otherwise start clean.
            part.unlink()
            before = 0
        url = self.settings.download_url.format(id=drive_id)
        resume = ["-C", "-"] if before else []
        args = ["--fail", *resume, "--max-time", str(timeout), "-o", str(part),
                "-w", "%{http_code} %{content_type}", url]
        result = self.runner.run(self._curl(*args), timeout + 30)
        return self._finish_download(result, part, before, expected)

    def _finish_download(self, result, part: Path, before: int, expected: int | None) -> int:
        content_type = result.stdout.split(" ", 1)[1].strip() if " " in result.stdout else ""
        if content_type.startswith("text/html"):
            _truncate(part, before)  # never let a throttle page become part of the media
            raise DriveError("Drive answered with a web page instead of the file (throttled)", throttled=True)
        if result.returncode == CURL_TIMED_OUT:
            raise DriveError("download paused when this run's time ran out; it resumes next run", timed_out=True)
        if result.returncode == CURL_RANGE_UNSUPPORTED:
            part.unlink(missing_ok=True)
            raise DriveError("Drive refused to resume; the download restarts from zero next time")
        if not result.ok:
            code = result.stdout.split(" ", 1)[0]
            raise DriveError(f"download failed (curl {result.returncode}, http {code}): {result.stderr.strip()[:160]}",
                             permanent=code in {"404", "410"})
        return self._check_size(part, expected)

    @staticmethod
    def _check_size(part: Path, expected: int | None) -> int:
        size = part.stat().st_size if part.exists() else 0
        if expected and size != expected:
            raise DriveError(f"download incomplete: {size} of {expected} bytes")
        if not expected and size < MIN_UNKNOWN_SIZE_BYTES:
            part.unlink(missing_ok=True)
            raise DriveError(f"download too small to be an episode ({size} bytes)")
        return size
