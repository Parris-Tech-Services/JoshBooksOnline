"""SQLite state: the one authority for what is known, staged and on which player.

Every state change is its own transaction, so a run killed at any point (phone
locks, Termux is stopped, cable pulled) leaves a consistent record that the
next run continues from. The schema is versioned with PRAGMA user_version and
migrated forward explicitly; a backup copy is taken at the start of each run.
"""
from __future__ import annotations

import os
import sqlite3
from contextlib import closing
from dataclasses import dataclass
from pathlib import Path

SCHEMA_VERSION = 1
MAX_EVENTS = 5000
BACKOFF_BASE = 15 * 60
BACKOFF_CAP = 24 * 3600
MAX_ATTEMPTS = 5

_SCHEMA_V1 = """
CREATE TABLE folders (
  id TEXT PRIMARY KEY,
  parent_id TEXT,
  name TEXT NOT NULL,
  show TEXT,
  depth INTEGER NOT NULL,
  path TEXT NOT NULL,
  listed_at INTEGER,
  failures INTEGER NOT NULL DEFAULT 0,
  retry_at INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE episodes (
  drive_id TEXT PRIMARY KEY,
  folder_id TEXT,
  show TEXT NOT NULL,
  path TEXT NOT NULL,
  label TEXT NOT NULL UNIQUE,
  kind TEXT NOT NULL CHECK (kind IN ('episode', 'extra', 'duplicate')),
  season INTEGER,
  episode INTEGER,
  source_bytes INTEGER,
  state TEXT NOT NULL CHECK (state IN ('discovered', 'staged', 'failed', 'gone')),
  attempts INTEGER NOT NULL DEFAULT 0,
  retry_at INTEGER NOT NULL DEFAULT 0,
  last_error TEXT,
  amv_bytes INTEGER,
  amv_sha256 TEXT,
  work_seconds REAL,
  discovered_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);
CREATE INDEX episodes_show ON episodes(show, season, episode);
CREATE TABLE copies (
  player_id TEXT NOT NULL,
  drive_id TEXT NOT NULL REFERENCES episodes(drive_id),
  state TEXT NOT NULL CHECK (state IN ('copying', 'verified', 'present', 'removed')),
  bytes INTEGER,
  seconds REAL,
  updated_at INTEGER NOT NULL,
  PRIMARY KEY (player_id, drive_id)
);
CREATE TABLE events (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  at INTEGER NOT NULL,
  level TEXT NOT NULL,
  message TEXT NOT NULL
);
"""

MIGRATIONS = {1: _SCHEMA_V1}


@dataclass(frozen=True)
class Episode:
    drive_id: str
    folder_id: str | None
    show: str
    path: str
    label: str
    kind: str
    season: int | None
    episode: int | None
    source_bytes: int | None
    state: str
    attempts: int
    retry_at: int
    last_error: str | None
    amv_bytes: int | None
    amv_sha256: str | None
    work_seconds: float | None


_EPISODE_COLUMNS = (
    "drive_id, folder_id, show, path, label, kind, season, episode, source_bytes, state, "
    "attempts, retry_at, last_error, amv_bytes, amv_sha256, work_seconds"
)


@dataclass(frozen=True)
class Folder:
    id: str
    parent_id: str | None
    name: str
    show: str | None
    depth: int
    path: str
    listed_at: int | None


class StoreError(RuntimeError):
    pass


def backoff_seconds(attempts: int) -> int:
    return min(BACKOFF_CAP, BACKOFF_BASE * 2 ** max(0, attempts - 1))


class Store:
    def __init__(self, path: Path):
        path.parent.mkdir(parents=True, exist_ok=True)
        self.path = path
        self.conn = sqlite3.connect(str(path), timeout=30)
        self.conn.row_factory = sqlite3.Row
        self.conn.execute("PRAGMA foreign_keys = ON")
        self.migrate()

    def close(self) -> None:
        self.conn.close()

    # ---- schema ---------------------------------------------------------
    def schema_version(self) -> int:
        return int(self.conn.execute("PRAGMA user_version").fetchone()[0])

    def migrate(self) -> None:
        current = self.schema_version()
        if current > SCHEMA_VERSION:
            raise StoreError(
                f"{self.path} is schema v{current}, newer than this tool (v{SCHEMA_VERSION}); "
                "update the tool rather than risk overwriting newer state"
            )
        for version in range(current + 1, SCHEMA_VERSION + 1):
            with self.conn:
                self.conn.executescript(MIGRATIONS[version])
                self.conn.execute(f"PRAGMA user_version = {version}")

    def backup(self) -> Path:
        """Snapshot the database next to itself, replacing the previous snapshot atomically."""
        dest = self.path.with_suffix(".db.bak")
        tmp = self.path.with_suffix(".db.bak.tmp")
        with closing(sqlite3.connect(str(tmp))) as target:
            self.conn.backup(target)
        os.replace(tmp, dest)
        return dest

    # ---- events ---------------------------------------------------------
    def log(self, now: int, level: str, message: str) -> None:
        with self.conn:
            self.conn.execute("INSERT INTO events(at, level, message) VALUES (?, ?, ?)", (now, level, message))
            self.conn.execute(
                "DELETE FROM events WHERE id <= (SELECT MAX(id) FROM events) - ?", (MAX_EVENTS,)
            )

    def recent_events(self, limit: int) -> list[sqlite3.Row]:
        rows = self.conn.execute("SELECT at, level, message FROM events ORDER BY id DESC LIMIT ?", (limit,))
        return list(reversed(rows.fetchall()))

    # ---- folders --------------------------------------------------------
    def upsert_folder(self, folder: Folder) -> None:
        with self.conn:
            self.conn.execute(
                "INSERT INTO folders(id, parent_id, name, show, depth, path) VALUES (?, ?, ?, ?, ?, ?) "
                "ON CONFLICT(id) DO UPDATE SET parent_id = excluded.parent_id, name = excluded.name, "
                "show = excluded.show, depth = excluded.depth, path = excluded.path",
                (folder.id, folder.parent_id, folder.name, folder.show, folder.depth, folder.path),
            )

    def folder(self, folder_id: str) -> Folder | None:
        row = self.conn.execute(
            "SELECT id, parent_id, name, show, depth, path, listed_at FROM folders WHERE id = ?", (folder_id,)
        ).fetchone()
        return Folder(**dict(row)) if row else None

    def folders_due(self, now: int, stale_before: int, limit: int) -> list[Folder]:
        rows = self.conn.execute(
            "SELECT id, parent_id, name, show, depth, path, listed_at FROM folders "
            "WHERE retry_at <= ? AND (listed_at IS NULL OR listed_at < ?) "
            "ORDER BY listed_at IS NOT NULL, depth, listed_at, name LIMIT ?",
            (now, stale_before, limit),
        )
        return [Folder(**dict(r)) for r in rows]

    def mark_folder_listed(self, folder_id: str, now: int) -> None:
        with self.conn:
            self.conn.execute(
                "UPDATE folders SET listed_at = ?, failures = 0, retry_at = 0 WHERE id = ?", (now, folder_id)
            )

    def mark_folder_failed(self, folder_id: str, now: int) -> None:
        with self.conn:
            row = self.conn.execute("SELECT failures FROM folders WHERE id = ?", (folder_id,)).fetchone()
            failures = (row["failures"] if row else 0) + 1
            self.conn.execute(
                "UPDATE folders SET failures = ?, retry_at = ? WHERE id = ?",
                (failures, now + backoff_seconds(failures), folder_id),
            )

    def shows(self) -> list[str]:
        rows = self.conn.execute("SELECT DISTINCT show FROM folders WHERE depth = 0 AND show IS NOT NULL")
        names = {r["show"] for r in rows}
        names.update(r["show"] for r in self.conn.execute("SELECT DISTINCT show FROM episodes"))
        return sorted(names, key=str.lower)

    # ---- episodes -------------------------------------------------------
    def episode(self, drive_id: str) -> Episode | None:
        row = self.conn.execute(f"SELECT {_EPISODE_COLUMNS} FROM episodes WHERE drive_id = ?", (drive_id,))
        found = row.fetchone()
        return Episode(**dict(found)) if found else None

    def episodes(self, where: str = "1 = 1", params: tuple = ()) -> list[Episode]:
        rows = self.conn.execute(f"SELECT {_EPISODE_COLUMNS} FROM episodes WHERE {where}", params)
        return [Episode(**dict(r)) for r in rows]

    def label_owner(self, label: str) -> str | None:
        row = self.conn.execute("SELECT drive_id FROM episodes WHERE label = ?", (label,)).fetchone()
        return row["drive_id"] if row else None

    def numbered_owner(self, show: str, season: int, episode: int) -> str | None:
        row = self.conn.execute(
            "SELECT drive_id FROM episodes WHERE show = ? AND season = ? AND episode = ? AND kind = 'episode'",
            (show, season, episode),
        ).fetchone()
        return row["drive_id"] if row else None

    def insert_episode(self, ep: Episode, now: int) -> None:
        with self.conn:
            self.conn.execute(
                f"INSERT INTO episodes({_EPISODE_COLUMNS}, discovered_at, updated_at) "
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                (*_episode_values(ep), now, now),
            )

    def touch_episode(self, drive_id: str, folder_id: str | None, path: str, now: int) -> None:
        """Seen again in a listing: refresh where it lives; bring a 'gone' episode back."""
        with self.conn:
            self.conn.execute(
                "UPDATE episodes SET folder_id = COALESCE(?, folder_id), path = ?, updated_at = ?, "
                "state = CASE state WHEN 'gone' THEN 'discovered' ELSE state END WHERE drive_id = ?",
                (folder_id, path, now, drive_id),
            )

    def mark_missing_from_folder(self, folder_id: str, present_ids: set[str], now: int) -> int:
        """Episodes no longer listed in a folder become 'gone' unless already staged locally."""
        rows = self.conn.execute(
            "SELECT drive_id FROM episodes WHERE folder_id = ? AND state IN ('discovered', 'failed')", (folder_id,)
        ).fetchall()
        missing = [r["drive_id"] for r in rows if r["drive_id"] not in present_ids]
        with self.conn:
            self.conn.executemany(
                "UPDATE episodes SET state = 'gone', updated_at = ? WHERE drive_id = ?",
                [(now, drive_id) for drive_id in missing],
            )
        return len(missing)

    def relabel(self, drive_id: str, label: str, now: int) -> None:
        with self.conn:
            self.conn.execute(
                "UPDATE episodes SET label = ?, updated_at = ? WHERE drive_id = ?", (label, now, drive_id)
            )

    def set_source_bytes(self, drive_id: str, size: int, now: int) -> None:
        with self.conn:
            self.conn.execute(
                "UPDATE episodes SET source_bytes = ?, updated_at = ? WHERE drive_id = ?", (size, now, drive_id)
            )

    def mark_staged(self, drive_id: str, amv_bytes: int, sha256: str | None, seconds: float | None, now: int) -> None:
        with self.conn:
            self.conn.execute(
                "UPDATE episodes SET state = 'staged', amv_bytes = ?, amv_sha256 = ?, "
                "work_seconds = COALESCE(?, work_seconds), last_error = NULL, retry_at = 0, updated_at = ? "
                "WHERE drive_id = ?",
                (amv_bytes, sha256, seconds, now, drive_id),
            )

    def record_failure(self, drive_id: str, error: str, now: int, permanent: bool = False) -> Episode:
        with self.conn:
            row = self.conn.execute("SELECT attempts FROM episodes WHERE drive_id = ?", (drive_id,)).fetchone()
            attempts = row["attempts"] + 1
            state = "failed" if permanent or attempts >= MAX_ATTEMPTS else "discovered"
            self.conn.execute(
                "UPDATE episodes SET attempts = ?, state = ?, retry_at = ?, last_error = ?, updated_at = ? "
                "WHERE drive_id = ?",
                (attempts, state, now + backoff_seconds(attempts), error[:500], now, drive_id),
            )
        return self.episode(drive_id)  # type: ignore[return-value]

    def unstage(self, drive_id: str, error: str, now: int) -> None:
        """The staged file is missing or invalid: forget it so it is fetched again."""
        with self.conn:
            self.conn.execute(
                "UPDATE episodes SET state = 'discovered', amv_bytes = NULL, amv_sha256 = NULL, "
                "last_error = ?, updated_at = ? WHERE drive_id = ?",
                (error[:500], now, drive_id),
            )

    def retry(self, drive_ids: list[str], now: int) -> int:
        with self.conn:
            cur = self.conn.executemany(
                "UPDATE episodes SET state = 'discovered', attempts = 0, retry_at = 0, last_error = NULL, "
                "updated_at = ? WHERE drive_id = ? AND state = 'failed'",
                [(now, d) for d in drive_ids],
            )
        return cur.rowcount

    def staged_bytes(self) -> int:
        row = self.conn.execute("SELECT COALESCE(SUM(amv_bytes), 0) FROM episodes WHERE state = 'staged'")
        return int(row.fetchone()[0])

    def average_work_seconds(self, default: float) -> float:
        row = self.conn.execute(
            "SELECT AVG(work_seconds) FROM (SELECT work_seconds FROM episodes WHERE work_seconds IS NOT NULL "
            "ORDER BY updated_at DESC LIMIT 20)"
        ).fetchone()
        return float(row[0]) if row[0] else default

    # ---- copies ---------------------------------------------------------
    def copies(self, player_id: str) -> dict[str, str]:
        rows = self.conn.execute("SELECT drive_id, state FROM copies WHERE player_id = ?", (player_id,))
        return {r["drive_id"]: r["state"] for r in rows}

    def set_copy(self, player_id: str, drive_id: str, state: str, now: int,
                 size: int | None = None, seconds: float | None = None) -> None:
        with self.conn:
            self.conn.execute(
                "INSERT INTO copies(player_id, drive_id, state, bytes, seconds, updated_at) "
                "VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT(player_id, drive_id) DO UPDATE SET "
                "state = excluded.state, bytes = COALESCE(excluded.bytes, bytes), "
                "seconds = COALESCE(excluded.seconds, seconds), updated_at = excluded.updated_at",
                (player_id, drive_id, state, size, seconds, now),
            )

    def copy_rows(self, player_id: str) -> list[sqlite3.Row]:
        rows = self.conn.execute(
            "SELECT drive_id, state, bytes FROM copies WHERE player_id = ? ORDER BY drive_id", (player_id,)
        )
        return rows.fetchall()

    def clear_copy(self, player_id: str, drive_id: str) -> None:
        with self.conn:
            self.conn.execute("DELETE FROM copies WHERE player_id = ? AND drive_id = ?", (player_id, drive_id))

    def copy_rate(self, default: float) -> float:
        """Bytes per second for recent verified copies, to judge whether a copy fits before a deadline."""
        row = self.conn.execute(
            "SELECT SUM(bytes), SUM(seconds) FROM (SELECT bytes, seconds FROM copies WHERE state = 'verified' "
            "AND bytes > 0 AND seconds > 0 ORDER BY updated_at DESC LIMIT 20)"
        ).fetchone()
        return float(row[0]) / float(row[1]) if row[0] and row[1] else default


def _episode_values(ep: Episode) -> tuple:
    return (
        ep.drive_id, ep.folder_id, ep.show, ep.path, ep.label, ep.kind, ep.season, ep.episode,
        ep.source_bytes, ep.state, ep.attempts, ep.retry_at, ep.last_error, ep.amv_bytes,
        ep.amv_sha256, ep.work_seconds,
    )


def new_episode(drive_id: str, folder_id: str | None, show: str, path: str, kind: str,
                season: int | None, number: int | None, label: str, size: int | None = None) -> Episode:
    return Episode(
        drive_id=drive_id, folder_id=folder_id, show=show, path=path, label=label, kind=kind,
        season=season, episode=number, source_bytes=size, state="discovered", attempts=0, retry_at=0,
        last_error=None, amv_bytes=None, amv_sha256=None, work_seconds=None,
    )
