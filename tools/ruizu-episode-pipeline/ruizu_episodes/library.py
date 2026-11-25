"""Incremental discovery of the Drive TV library, and import of the earlier scripts' state.

Each folder listing is stored the moment it succeeds, with the time it was
listed. A folder is only listed again once it is older than the listing TTL,
so a session never re-walks the whole library, and a run cut short keeps
every folder it already finished. Failed listings back off per folder.
"""
from __future__ import annotations

import json
import re
from collections.abc import Callable
from dataclasses import dataclass, field
from pathlib import Path

from .drive import DriveClient, DriveError, Entry
from .names import VIDEO_EXT, clean_text, identify, identify_label
from .settings import Settings
from .store import Folder, Store, new_episode

MAX_DEPTH = 2  # show folder (0) -> season folder (1) -> one more level (2)
LIBRARY_ROOT_DEPTH = -1
_LEGACY_ID = re.compile(r"^[A-Za-z0-9_-]{10,128}$")
LEGACY_LABEL_FILES = ("himym-order.tsv", "other-shows.tsv")


@dataclass
class ScanReport:
    listed: int = 0
    failed: int = 0
    new_episodes: list[str] = field(default_factory=list)
    gone: int = 0


def _unique_label(store: Store, label: str) -> str:
    if store.label_owner(label) is None:
        return label
    for n in range(2, 100):
        candidate = f"{label} ({n})"
        if store.label_owner(candidate) is None:
            return candidate
    raise RuntimeError(f"too many files share the label {label!r}")


def register_video(store: Store, show: str, folder_id: str | None, path: str, drive_id: str,
                   now: int, size: int | None = None) -> str | None:
    """Record a video file once. Returns its label when it is new, else None."""
    if store.episode(drive_id):
        store.touch_episode(drive_id, folder_id, path, now)
        return None
    ident = identify(show, path)
    kind, label = ident.kind, ident.label
    if kind == "episode" and store.numbered_owner(show, ident.season, ident.episode):  # type: ignore[arg-type]
        kind, label = "duplicate", f"{label} duplicate"
    label = _unique_label(store, label)
    episode = new_episode(drive_id, folder_id, show, path, kind, ident.season, ident.episode, label, size)
    store.insert_episode(episode, now)
    return label


def _child_folder(parent: Folder, entry: Entry) -> Folder:
    if parent.depth == LIBRARY_ROOT_DEPTH:
        return Folder(entry.id, parent.id, entry.name, clean_text(entry.name), 0, "", None)
    path = f"{parent.path}/{entry.name}" if parent.path else entry.name
    return Folder(entry.id, parent.id, entry.name, parent.show, parent.depth + 1, path, None)


def list_one(store: Store, drive: DriveClient, folder: Folder, now: int, report: ScanReport) -> bool:
    """List one folder and record what it holds. Returns False when Drive refused (throttled)."""
    try:
        entries = drive.list_folder(folder.id)
    except DriveError as exc:
        store.mark_folder_failed(folder.id, now)
        store.log(now, "warn", f"could not list {folder.show or 'library'}/{folder.path}: {exc}")
        report.failed += 1
        return not exc.throttled
    videos = set()
    for entry in entries:
        if entry.is_folder and folder.depth < MAX_DEPTH:
            store.upsert_folder(_child_folder(folder, entry))
        elif not entry.is_folder and folder.show and VIDEO_EXT.search(entry.name):
            videos.add(entry.id)
            path = f"{folder.path}/{entry.name}" if folder.path else entry.name
            label = register_video(store, folder.show, folder.id, path, entry.id, now)
            if label:
                report.new_episodes.append(label)
    if entries:  # an empty answer is not trusted as proof that files were deleted
        report.gone += store.mark_missing_from_folder(folder.id, videos, now)
    store.mark_folder_listed(folder.id, now)
    report.listed += 1
    return True


def seed_roots(store: Store, roots: tuple[str, ...]) -> None:
    for root in roots:
        if store.folder(root) is None:
            store.upsert_folder(Folder(root, None, "library", None, LIBRARY_ROOT_DEPTH, "", None))


def scan(store: Store, drive: DriveClient, roots: tuple[str, ...], now: int, ttl_seconds: int,
         max_listings: int, keep_going: Callable[[], bool]) -> ScanReport:
    """List folders that are new or older than the TTL, oldest-known-first, within the bounds."""
    seed_roots(store, roots)
    report = ScanReport()
    while report.listed + report.failed < max_listings and keep_going():
        due = store.folders_due(now, now - ttl_seconds, 1)
        if not due or not list_one(store, drive, due[0], now, report):
            break
    return report


# ---- import of the hand-run scripts' files ---------------------------------
@dataclass
class ImportReport:
    shows: int = 0
    episodes: int = 0
    relabelled: int = 0
    staged: int = 0
    unmatched_staged: list[str] = field(default_factory=list)
    skipped_lines: int = 0


def _rows(path: Path, columns: int) -> list[list[str]]:
    if not path.is_file():
        return []
    rows = []
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        parts = line.split("\t")
        if line.strip() and not line.startswith("#") and len(parts) == columns:
            rows.append([p.strip() for p in parts])
    return rows


def _size(raw: str) -> int | None:
    return int(raw) if raw.isdigit() and int(raw) > 0 else None


def import_shows(store: Store, settings: Settings, report: ImportReport) -> None:
    path = settings.investigate_dir / "tv-items.json"
    if not path.is_file():
        return
    data = json.loads(path.read_text(encoding="utf-8"))
    for folder_id, value in (data.items() if isinstance(data, dict) else []):
        if _LEGACY_ID.match(folder_id) and isinstance(value, list) and len(value) == 2 and "folder" in str(value[1]):
            show = clean_text(str(value[0]))
            if show and store.folder(folder_id) is None:
                store.upsert_folder(Folder(folder_id, None, show, show, 0, "", None))
                report.shows += 1


def import_paths(store: Store, settings: Settings, now: int, report: ImportReport) -> None:
    """episodes.tsv: 'Show/Season N/file.ext <TAB> size <TAB> drive id'."""
    for path, size, drive_id in _rows(settings.state_dir / "episodes.tsv", 3):
        if not _LEGACY_ID.match(drive_id) or "/" not in path:
            report.skipped_lines += 1
            continue
        show, rest = path.split("/", 1)
        if register_video(store, clean_text(show), None, rest, drive_id, now, _size(size)):
            report.episodes += 1


def import_labels(store: Store, settings: Settings, now: int, report: ImportReport) -> None:
    """'drive id <TAB> label <TAB> size' lists: the labels already used for staged files."""
    files = [settings.state_dir / name for name in LEGACY_LABEL_FILES] + [settings.work_dir / "variety.tsv"]
    for listing in files:
        for drive_id, label, size in _rows(listing, 3):
            if not _LEGACY_ID.match(drive_id):
                report.skipped_lines += 1
                continue
            _adopt_label(store, drive_id, clean_text(label), _size(size), now, report)


def _adopt_label(store: Store, drive_id: str, label: str, size: int | None, now: int, report: ImportReport) -> None:
    existing = store.episode(drive_id)
    if existing is None:
        show, ident = identify_label(label)
        if store.label_owner(label) is None:
            store.insert_episode(new_episode(drive_id, None, show, label, ident.kind, ident.season, ident.episode,
                                             label, size), now)
            report.episodes += 1
        return
    if size and not existing.source_bytes:
        store.set_source_bytes(drive_id, size, now)
    if existing.label != label and store.label_owner(label) is None:
        store.relabel(drive_id, label, now)
        report.relabelled += 1


def import_staged(store: Store, settings: Settings, now: int, report: ImportReport) -> None:
    """Encoded files already in the staging folder count as staged; their hash is taken on first copy."""
    if not settings.stage_dir.is_dir():
        return
    for amv in sorted(settings.stage_dir.glob("*.amv")):
        owner = store.label_owner(amv.stem)
        if owner is None:
            report.unmatched_staged.append(amv.name)
            continue
        if store.episode(owner).state != "staged":  # type: ignore[union-attr]
            store.mark_staged(owner, amv.stat().st_size, None, None, now)
            report.staged += 1


def import_legacy(store: Store, settings: Settings, now: int) -> ImportReport:
    """Read-only import of the earlier scripts' files. Safe to repeat."""
    report = ImportReport()
    import_shows(store, settings, report)
    import_paths(store, settings, now, report)
    import_labels(store, settings, now, report)
    import_staged(store, settings, now, report)
    store.log(now, "info", f"imported legacy state: {report.shows} shows, {report.episodes} episodes, "
                           f"{report.staged} staged files")
    return report
