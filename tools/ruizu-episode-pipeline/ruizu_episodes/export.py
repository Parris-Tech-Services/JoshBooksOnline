"""Read-only export of the pipeline's state, for migrating to the native RUIZU Sync engine (H2 on #47).

Keyed by episode label, because the native app identifies sources by Android document URIs and
the two sides share no source id (see the schema map on #47). Deliberately leaves out Drive file
ids and filesystem paths. The result still names players and shows, so it is private: keep it
on the phone and never commit it.
"""
from __future__ import annotations

import time

from .config import ALL_SHOWS, Config, to_json
from .names import player_filename
from .store import SCHEMA_VERSION, Store

EXPORT_FORMAT = "ruizu-episodes-export"
EXPORT_VERSION = 1


def _episodes(store: Store) -> list[dict]:
    return [
        {
            "label": ep.label, "show": ep.show, "kind": ep.kind, "season": ep.season, "episode": ep.episode,
            "state": ep.state, "source_bytes": ep.source_bytes, "amv_bytes": ep.amv_bytes,
            "amv_sha256": ep.amv_sha256, "player_file": player_filename(ep.label),
        }
        for ep in sorted(store.episodes(), key=lambda e: e.label)
    ]


def _players(store: Store, config: Config) -> list[dict]:
    labels = {ep.drive_id: ep.label for ep in store.episodes()}
    players = []
    for pid, policy in config.players.items():
        copies = store.copy_rows(pid)
        players.append({
            "marker_id": pid,
            "name": policy.name,
            "shows": policy.shows if policy.shows == ALL_SHOWS else list(policy.shows),
            "per_show": policy.per_show,
            "min_free_mb": policy.min_free_mb,
            "copies": sorted(({"label": labels[r["drive_id"]], "state": r["state"], "bytes": r["bytes"]}
                              for r in copies), key=lambda c: c["label"]),
        })
    return players


def export_state(store: Store, config: Config, now: float | None = None) -> dict:
    """Everything the native importer needs, read inside one transaction so it is self-consistent."""
    with store.conn:
        store.conn.execute("BEGIN")  # one consistent snapshot across tables
        result = {
            "format": EXPORT_FORMAT,
            "version": EXPORT_VERSION,
            "schema_version": SCHEMA_VERSION,
            "exported_at": time.strftime("%Y-%m-%dT%H:%M:%S%z", time.localtime(now)),
            "limits": to_json(config)["limits"],
            "players": _players(store, config),
            "episodes": _episodes(store),
        }
    return result
