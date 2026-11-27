"""Test doubles: a local fake of Google Drive's two endpoints, synthetic episodes, a fake player.

The fake Drive speaks real HTTP so the pipeline's actual curl commands run
against it, including byte-range resume, throttle pages and dropped connections.
No personal data or network access is involved.
"""
from __future__ import annotations

import hashlib
import html
import os
import shutil
import subprocess
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

from ruizu_episodes import config as cfg
from ruizu_episodes.config import Config, Limits, PlayerPolicy
from ruizu_episodes.process import Result, Runner
from ruizu_episodes.settings import Settings

os.environ["NO_PROXY"] = os.environ["no_proxy"] = "127.0.0.1,localhost"
HAVE_TOOLS = all(shutil.which(t) for t in ("ffmpeg", "ffprobe", "curl"))
needs_tools = unittest.skipUnless(HAVE_TOOLS, "ffmpeg, ffprobe and curl are required")
_SOURCES: dict[int, bytes] = {}


def synthetic_episode(seconds: int = 12) -> bytes:
    """A small real video with sound (cached per length), standing in for a TV episode."""
    if seconds not in _SOURCES:
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / "episode.mkv"
            subprocess.run(
                ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i",
                 "testsrc2=size=320x240:rate=24", "-f", "lavfi", "-i", "sine=frequency=330:sample_rate=44100",
                 "-t", str(seconds), "-c:v", "mpeg4", "-q:v", "8", "-c:a", "aac", str(out)],
                check=True,
            )
            _SOURCES[seconds] = out.read_bytes()
    return _SOURCES[seconds]


def drive_id(name: str) -> str:
    """A stable Drive-shaped id for a test object."""
    return "1x" + hashlib.sha1(name.encode()).hexdigest()[:31]


class FakeDrive:
    def __init__(self) -> None:
        self.folders: dict[str, list[tuple[str, str, bool]]] = {}
        self.files: dict[str, bytes] = {}
        self.requests: list[tuple[str, str]] = []
        self.throttle_listings = 0
        self.throttle_downloads: dict[str, int] = {}
        self.cut_downloads: dict[str, int] = {}
        self.bytes_served: dict[str, int] = {}
        self.lock = threading.Lock()
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), self._handler())
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)

    # ---- library building ----
    def folder(self, name: str, parent: str | None = None) -> str:
        fid = drive_id(f"folder {parent} {name}")
        self.folders.setdefault(fid, [])
        if parent:
            self.folders[parent].append((name, fid, True))
        return fid

    def file(self, parent: str, name: str, data: bytes) -> str:
        fid = drive_id(f"file {parent} {name}")
        self.files[fid] = data
        self.folders[parent].append((name, fid, False))
        return fid

    def count(self, kind: str, fid: str | None = None) -> int:
        with self.lock:
            return sum(1 for k, i in self.requests if k == kind and (fid is None or i == fid))

    # ---- server ----
    def __enter__(self) -> FakeDrive:
        self.thread.start()
        return self

    def __exit__(self, *exc) -> None:
        self.server.shutdown()
        self.server.server_close()

    @property
    def base(self) -> str:
        return f"http://127.0.0.1:{self.server.server_address[1]}"

    def listing_html(self, fid: str) -> str:
        rows = []
        for name, child, is_folder in self.folders[fid]:
            href = f"https://drive.google.com/drive/folders/{child}" if is_folder else \
                f"https://drive.google.com/file/d/{child}/view?usp=drive_web"
            rows.append(
                f'<div class="flip-entry" id="entry-{child}" tabindex="0" role="link"><div class="flip-entry-info">'
                f'<a href="{href}" target="_blank"><div class="flip-entry-title">{html.escape(name)}</div></a>'
                f"</div></div>"
            )
        return ('<html><body><div id="flip-contents" class="flip-contents flip-list-view">'
                f'<div class="flip-entries">{"".join(rows)}</div></div></body></html>')

    def _handler(drive):  # noqa: N805 - closure over the fake
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def _send(self, code: int, body: bytes, ctype: str, extra: dict | None = None) -> None:
                self.send_response(code)
                self.send_header("Content-Type", ctype)
                self.send_header("Content-Length", str(len(body)))
                for key, value in (extra or {}).items():
                    self.send_header(key, value)
                self.end_headers()
                self.wfile.write(body)

            def do_GET(self):  # noqa: N802
                url = urlparse(self.path)
                fid = parse_qs(url.query).get("id", [""])[0]
                if url.path == "/embeddedfolderview":
                    self._listing(fid)
                elif url.path == "/download":
                    self._download(fid)
                else:
                    self._send(404, b"no", "text/plain")

            def _listing(self, fid: str) -> None:
                with drive.lock:
                    drive.requests.append(("list", fid))
                    throttled = drive.throttle_listings > 0
                    drive.throttle_listings -= 1 if throttled else 0
                if throttled:
                    self._send(200, b"<html><body>Sign in to continue</body></html>", "text/html")
                elif fid not in drive.folders:
                    self._send(404, b"not found", "text/html")
                else:
                    self._send(200, drive.listing_html(fid).encode(), "text/html; charset=utf-8")

            def _download(self, fid: str) -> None:
                probe = self.headers.get("Range") == "bytes=0-0"
                with drive.lock:
                    drive.requests.append(("probe" if probe else "download", fid))
                    throttled = not probe and drive.throttle_downloads.get(fid, 0) > 0
                    if throttled:
                        drive.throttle_downloads[fid] -= 1
                    cut = None if probe else drive.cut_downloads.pop(fid, None)
                if throttled:
                    self._send(200, b"<html>Too many users have viewed or downloaded this file</html>", "text/html")
                    return
                if fid not in drive.files:
                    self._send(404, b"gone", "text/html")
                    return
                data = drive.files[fid]
                start, end = self._range(len(data))
                body = data[start : end + 1]
                code = 206 if self.headers.get("Range") else 200
                self.send_response(code)
                self.send_header("Content-Type", "video/x-matroska")
                self.send_header("Content-Length", str(len(body)))
                if code == 206:
                    self.send_header("Content-Range", f"bytes {start}-{end}/{len(data)}")
                self.end_headers()
                sent = body[:cut] if cut is not None else body
                self.wfile.write(sent)
                with drive.lock:
                    drive.bytes_served[fid] = drive.bytes_served.get(fid, 0) + len(sent)
                if cut is not None:
                    self.close_connection = True
                    self.wfile.flush()
                    self.connection.shutdown(2)

            def _range(self, total: int) -> tuple[int, int]:
                header = self.headers.get("Range", "")
                if not header.startswith("bytes="):
                    return 0, total - 1
                first, _, last = header[6:].partition("-")
                return int(first or 0), min(int(last) if last else total - 1, total - 1)

        return Handler


class FakeClock:
    def __init__(self, start: float = 1_800_000_000.0):
        self.now = start

    def __call__(self) -> float:
        return self.now

    def advance(self, seconds: float) -> None:
        self.now += seconds


class OfflineTermux(Runner):
    """Real subprocesses, but the Termux:API commands answer as configured (default: not installed)."""

    def __init__(self, wifi: str | None = None):
        self.wifi = wifi
        self.calls: list[list[str]] = []

    def available(self, program: str) -> bool:
        if program.startswith("termux-"):
            return program == "termux-wifi-connectioninfo" and self.wifi is not None
        return super().available(program)

    def run(self, cmd, timeout):
        self.calls.append(list(cmd))
        if cmd[0] == "termux-wifi-connectioninfo":
            return Result(0, self.wifi or "", "")
        return super().run(cmd, timeout)


class Sandbox:
    """Temporary home with state, staging and a player mount."""

    def __init__(self, base: Path, drive: FakeDrive | None = None):
        self.base = base
        self.player = base / "player"
        (self.player / "VIDEO").mkdir(parents=True)
        base_url = drive.base if drive else "http://127.0.0.1:9"
        self.settings = Settings(
            state_dir=base / "state", stage_dir=base / "stage", investigate_dir=base / "investigate",
            player_roots=(self.player,), list_url=base_url + "/embeddedfolderview?id={id}",
            download_url=base_url + "/download?id={id}",
        )

    def write_config(self, players: dict[str, PlayerPolicy], roots: tuple[str, ...] = (), **limits) -> Config:
        defaults = dict(require_unmetered=False, min_phone_free_mb=0)
        defaults.update(limits)
        config = Config(library_roots=roots, players=players, limits=Limits(**defaults))
        cfg.save(self.settings.config_path, config)
        return config

    def video_files(self) -> list[str]:
        """Every file under VIDEO/, by path relative to it (episodes live in show folders)."""
        base = self.player / "VIDEO"
        if not base.is_dir():
            return []
        return sorted(p.relative_to(base).as_posix() for p in base.rglob("*") if p.is_file())
