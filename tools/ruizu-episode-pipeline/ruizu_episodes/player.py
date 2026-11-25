"""Which RUIZU is plugged in, what its VIDEO folder holds, and copying to it safely.

Both players mount as volume 0000-006F at the same path, so the mount cannot
say which unit is connected. Identity comes from the `.ruizu-sync-device.json`
marker the RUIZU Sync app already uses (same format, so the app and this tool
agree). A player without a marker is never written to.

Copies are written under a `.ruizu-partial` name, flushed to the card, read
back and hashed, and only then renamed into place, so the VIDEO folder never
holds a half-written episode even if the cable is pulled or the phone locks.
"""
from __future__ import annotations

import json
import os
import re
import shutil
import time
import uuid
from dataclasses import dataclass
from pathlib import Path

from .media import sha256_file

MARKER = ".ruizu-sync-device.json"
MAX_MARKER_BYTES = 1024
MAX_MARKER_FIELDS = 16
# The RUIZU Sync app names its in-flight copies ".ruizu-<uuid>.part" (SafTransfer).
APP_TEMP = re.compile(r"^\.ruizu-[0-9a-fA-F-]{36}\.part$")
APP_TEMP_ACTIVE_SECONDS = 600
PARTIAL_SUFFIX = ".ruizu-partial"
_ID = re.compile(r"^[A-Za-z0-9_-]{1,64}$")
CHUNK = 1024 * 1024


@dataclass(frozen=True)
class Identity:
    id: str
    name: str
    first_seen_ms: int


class CopyError(RuntimeError):
    pass


def is_mounted(root: Path) -> bool:
    return (root / "VIDEO").is_dir()


class _DuplicateKey(ValueError):
    pass


def _reject_duplicates(pairs: list[tuple[str, object]]) -> dict:
    keys = [k for k, _ in pairs]
    if len(keys) != len(set(keys)):
        raise _DuplicateKey("duplicate key")
    return dict(pairs)


def _flat_marker(text: str) -> dict | None:
    """The marker as a flat object, with the same refusals as the app's PlayerIdentity.parse:
    duplicate keys, nested objects or arrays, and more than MAX_MARKER_FIELDS fields."""
    try:
        data = json.loads(text, object_pairs_hook=_reject_duplicates)
    except (ValueError, RecursionError):
        return None
    if not isinstance(data, dict) or len(data) > MAX_MARKER_FIELDS:
        return None
    if any(isinstance(v, (dict, list)) for v in data.values()):
        return None
    return data


def _clean_name(raw: object) -> str:
    """As PlayerIdentity.cleanName: drop control characters, collapse whitespace, 48 characters."""
    if raw is None:
        return "Unnamed player"
    text = "".join(c for c in str(raw) if not (ord(c) < 0x20 or 0x7F <= ord(c) <= 0x9F))
    text = re.sub(r"\s+", " ", text).strip()[:48].strip()
    return text or "Unnamed player"


def _first_seen(raw: object) -> int:
    text = str(raw).strip() if raw is not None and not isinstance(raw, bool) else ""
    return max(0, int(text)) if re.fullmatch(r"-?\d{1,19}", text) else 0


def read_identity(root: Path) -> Identity | None:
    """The marker's identity, or None when it is absent, oversized or not one the app would accept."""
    marker = root / MARKER
    try:
        if marker.is_symlink() or not marker.is_file() or marker.stat().st_size > MAX_MARKER_BYTES:
            return None
        data = _flat_marker(marker.read_text(encoding="utf-8").strip())
    except (OSError, UnicodeDecodeError):
        return None
    if data is None or ("version" in data and str(data["version"]).strip() != "1"):
        return None
    pid = str(data.get("id", "")).strip()
    if not _ID.match(pid):
        return None
    return Identity(pid, _clean_name(data.get("name")), _first_seen(data.get("firstSeenMs")))


def write_identity(root: Path, name: str, now_ms: int | None = None) -> Identity:
    """Create a marker on a player that has none. Never replaces an existing marker."""
    clean = re.sub(r"[\x00-\x1f\x7f\"\\]", "", name).strip()[:48] or "Unnamed player"
    identity = Identity(str(uuid.uuid4()), clean, int(time.time() * 1000) if now_ms is None else now_ms)
    body = json.dumps({"version": 1, "id": identity.id, "name": identity.name, "firstSeenMs": identity.first_seen_ms},
                      separators=(",", ":"))
    try:
        fd = os.open(root / MARKER, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o666)
    except FileExistsError as exc:
        raise CopyError(f"{root / MARKER} already exists; not replacing another identity") from exc
    with os.fdopen(fd, "w", encoding="utf-8") as handle:
        handle.write(body)
        handle.flush()
        os.fsync(handle.fileno())
    return identity


def inventory(root: Path) -> dict[str, int]:
    """Regular files in VIDEO/ with their sizes (symlinks skipped)."""
    found = {}
    for entry in os.scandir(root / "VIDEO"):
        if entry.is_file(follow_symlinks=False):
            found[entry.name] = entry.stat(follow_symlinks=False).st_size
    return found


def app_copies_in_progress(root: Path, now: float) -> tuple[list[str], list[str]]:
    """The app's in-flight copy files in VIDEO/: (recently written, stale).

    There is no lock shared with the app, so a recently written app temporary file is taken to
    mean the app is copying right now; this tool then stays out of VIDEO/ until it has finished.
    An old one is a leftover the app removes on its next transfer, and does not block anything.
    """
    active: list[str] = []
    stale: list[str] = []
    for entry in os.scandir(root / "VIDEO"):
        if entry.is_file(follow_symlinks=False) and APP_TEMP.match(entry.name):
            age = now - entry.stat(follow_symlinks=False).st_mtime
            (active if age < APP_TEMP_ACTIVE_SECONDS else stale).append(entry.name)
    return active, stale


def free_bytes(path: Path) -> int:
    return shutil.disk_usage(path).free


def is_own_prefix(source: Path, candidate: Path) -> bool:
    """True when `candidate` is byte-for-byte a (possibly complete) prefix of `source`:
    proof that a file on the player is an unfinished copy of ours, safe to replace."""
    size = candidate.stat().st_size
    if size > source.stat().st_size:
        return False
    with open(source, "rb") as a, open(candidate, "rb") as b:
        remaining = size
        while remaining:
            want = min(CHUNK, remaining)
            if a.read(want) != b.read(want):
                return False
            remaining -= want
    return True


def _fsync_dir(path: Path) -> None:
    try:
        fd = os.open(path, os.O_RDONLY)
    except OSError:
        return
    try:
        os.fsync(fd)
    except OSError:
        pass  # some Android/FUSE mounts refuse directory fsync; the file itself was fsynced
    finally:
        os.close(fd)


def _write_synced(source: Path, partial: Path) -> None:
    fd = os.open(partial, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o666)
    with os.fdopen(fd, "wb") as out, open(source, "rb") as src:
        shutil.copyfileobj(src, out, CHUNK)
        out.flush()
        os.fsync(out.fileno())


def copy_verified(source: Path, video_dir: Path, dest_name: str, expected_sha256: str) -> float:
    """Copy `source` to video_dir/dest_name, proving the bytes on the card. Returns seconds taken."""
    final = video_dir / dest_name
    partial = video_dir / (dest_name + PARTIAL_SUFFIX)
    if final.exists() or partial.exists():
        raise CopyError(f"{dest_name} (or its partial) already on the player; reconcile first")
    started = time.monotonic()
    try:
        _write_synced(source, partial)
        if sha256_file(partial, drop_cache=True) != expected_sha256:
            raise CopyError(f"{dest_name}: bytes on the player do not match the staged file")
        if final.exists():
            raise CopyError(f"{dest_name} appeared during the copy; leaving it alone")
        os.rename(partial, final)
        _fsync_dir(video_dir)
    except BaseException:
        try:
            partial.unlink(missing_ok=True)  # only ever the O_EXCL file this call created
        except OSError:
            pass  # card already gone; the caller keeps its 'copying' record so the next connection cleans up
        raise
    return time.monotonic() - started
