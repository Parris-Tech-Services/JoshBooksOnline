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


def read_identity(root: Path) -> Identity | None:
    """The marker's identity, or None when it is absent, oversized or not ours."""
    marker = root / MARKER
    try:
        if marker.is_symlink() or not marker.is_file() or marker.stat().st_size > MAX_MARKER_BYTES:
            return None
        data = json.loads(marker.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError):
        return None
    if not isinstance(data, dict) or data.get("version", 1) != 1:
        return None
    pid, name = str(data.get("id", "")).strip(), str(data.get("name", "")).strip()
    if not _ID.match(pid):
        return None
    first_seen = data.get("firstSeenMs", 0)
    return Identity(pid, name[:48] or "Unnamed player", first_seen if isinstance(first_seen, int) else 0)


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
    """Regular files in VIDEO/ and its subfolders, keyed by path relative to VIDEO/.

    Episodes live in per-show folders, so the listing is walked rather than read one level deep.
    Symlinks are skipped and the walk does not follow them, so a card cannot lead the tool out of
    its own folder.
    """
    found: dict[str, int] = {}
    base = root / "VIDEO"
    for current, dirs, names in os.walk(base, followlinks=False):
        dirs[:] = [d for d in dirs if not os.path.islink(os.path.join(current, d))]
        for name in names:
            path = Path(current) / name
            try:
                if path.is_symlink() or not path.is_file():
                    continue
                found[path.relative_to(base).as_posix()] = path.stat().st_size
            except OSError:
                continue
    return found


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
    if ".." in Path(dest_name).parts or Path(dest_name).is_absolute():
        raise CopyError(f"{dest_name}: not a path inside the player's VIDEO folder")
    final = video_dir / dest_name
    partial = video_dir / (dest_name + PARTIAL_SUFFIX)
    partial.parent.mkdir(parents=True, exist_ok=True)
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
        _fsync_dir(final.parent)
    except BaseException:
        try:
            partial.unlink(missing_ok=True)  # only ever the O_EXCL file this call created
        except OSError:
            pass  # card already gone; the caller keeps its 'copying' record so the next connection cleans up
        raise
    return time.monotonic() - started
