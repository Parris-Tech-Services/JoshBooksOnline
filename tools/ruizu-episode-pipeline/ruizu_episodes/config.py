"""User policy: which shows each player may receive, and the download limits.

Stored as hand-editable JSON on the phone (never in this public repository,
because it holds private Drive folder ids and household names). It is a trust
boundary, so it is validated on every load and a bad file is reported rather
than half-applied.
"""
from __future__ import annotations

import json
import os
import re
from dataclasses import asdict, dataclass, field, replace
from pathlib import Path

CONFIG_VERSION = 1
SAFE_ID = re.compile(r"^[A-Za-z0-9_-]{10,128}$")
ALL_SHOWS = "*"


class ConfigError(ValueError):
    pass


@dataclass(frozen=True)
class PlayerPolicy:
    name: str
    shows: tuple[str, ...] | str = ()  # explicit list, or "*" for every show
    per_show: int = 1  # how many not-yet-copied episodes of each show to keep ready
    min_free_mb: int = 500

    def allows(self, show: str) -> bool:
        if self.shows == ALL_SHOWS:
            return True
        return show.casefold() in {s.casefold() for s in self.shows}


@dataclass(frozen=True)
class Limits:
    max_download_mb_per_run: int = 1500
    require_unmetered: bool = True
    max_staged_mb: int = 8000
    min_phone_free_mb: int = 2000
    listing_ttl_hours: int = 168
    max_listings_per_run: int = 40


@dataclass(frozen=True)
class Config:
    library_roots: tuple[str, ...] = ()
    players: dict[str, PlayerPolicy] = field(default_factory=dict)
    limits: Limits = Limits()

    def player_named(self, wanted: str) -> tuple[str, PlayerPolicy]:
        """Look a player up by marker id, id prefix or (case-insensitive) name."""
        matches = [
            (pid, p) for pid, p in self.players.items()
            if pid == wanted or pid.startswith(wanted) or p.name.casefold() == wanted.casefold()
        ]
        if len(matches) != 1:
            known = ", ".join(f"{p.name} ({pid[:8]})" for pid, p in self.players.items()) or "none yet"
            raise ConfigError(f"no single player matches {wanted!r}; known players: {known}")
        return matches[0]

    def with_player(self, player_id: str, policy: PlayerPolicy) -> Config:
        players = dict(self.players)
        players[player_id] = policy
        return replace(self, players=players)


def _int(raw: object, name: str, low: int, high: int) -> int:
    if isinstance(raw, bool) or not isinstance(raw, int) or not low <= raw <= high:
        raise ConfigError(f"{name} must be a whole number from {low} to {high}")
    return raw


def _shows(raw: object, where: str) -> tuple[str, ...] | str:
    if raw == ALL_SHOWS:
        return ALL_SHOWS
    if not isinstance(raw, list) or not all(isinstance(s, str) and 0 < len(s) <= 120 for s in raw):
        raise ConfigError(f'{where}.shows must be "*" or a list of show names')
    return tuple(raw)


def _player(pid: str, raw: object) -> PlayerPolicy:
    where = f"players.{pid}"
    if not SAFE_ID.match(pid) or not isinstance(raw, dict):
        raise ConfigError(f"{where} must be keyed by the player's marker id and hold an object")
    name = raw.get("name")
    if not isinstance(name, str) or not 0 < len(name) <= 48:
        raise ConfigError(f"{where}.name must be 1-48 characters")
    return PlayerPolicy(
        name=name,
        shows=_shows(raw.get("shows", []), where),
        per_show=_int(raw.get("per_show", 1), f"{where}.per_show", 1, 20),
        min_free_mb=_int(raw.get("min_free_mb", 500), f"{where}.min_free_mb", 0, 100_000),
    )


def _limits(raw: object) -> Limits:
    if not isinstance(raw, dict):
        raise ConfigError("limits must be an object")
    base = Limits()
    require = raw.get("require_unmetered", base.require_unmetered)
    if not isinstance(require, bool):
        raise ConfigError("limits.require_unmetered must be true or false")
    return Limits(
        max_download_mb_per_run=_int(raw.get("max_download_mb_per_run", base.max_download_mb_per_run),
                                     "limits.max_download_mb_per_run", 0, 100_000),
        require_unmetered=require,
        max_staged_mb=_int(raw.get("max_staged_mb", base.max_staged_mb), "limits.max_staged_mb", 0, 1_000_000),
        min_phone_free_mb=_int(raw.get("min_phone_free_mb", base.min_phone_free_mb),
                               "limits.min_phone_free_mb", 0, 1_000_000),
        listing_ttl_hours=_int(raw.get("listing_ttl_hours", base.listing_ttl_hours),
                               "limits.listing_ttl_hours", 1, 24 * 365),
        max_listings_per_run=_int(raw.get("max_listings_per_run", base.max_listings_per_run),
                                  "limits.max_listings_per_run", 1, 1000),
    )


def parse(raw: object) -> Config:
    if not isinstance(raw, dict) or raw.get("version") != CONFIG_VERSION:
        raise ConfigError(f"config must be an object with \"version\": {CONFIG_VERSION}")
    roots = raw.get("library_roots", [])
    if not isinstance(roots, list) or not all(isinstance(r, str) and SAFE_ID.match(r) for r in roots):
        raise ConfigError("library_roots must be a list of Drive folder ids")
    players = raw.get("players", {})
    if not isinstance(players, dict):
        raise ConfigError("players must be an object keyed by marker id")
    return Config(
        library_roots=tuple(roots),
        players={pid: _player(pid, p) for pid, p in players.items()},
        limits=_limits(raw.get("limits", {})),
    )


def to_json(config: Config) -> dict:
    return {
        "version": CONFIG_VERSION,
        "library_roots": list(config.library_roots),
        "players": {
            pid: {**asdict(p), "shows": p.shows if p.shows == ALL_SHOWS else list(p.shows)}
            for pid, p in config.players.items()
        },
        "limits": asdict(config.limits),
    }


def load(path: Path) -> Config:
    if not path.exists():
        return Config()
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ConfigError(f"cannot read {path}: {exc}") from exc
    return parse(raw)


def save(path: Path, config: Config) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".json.tmp")
    tmp.write_text(json.dumps(to_json(config), indent=2) + "\n", encoding="utf-8")
    os.replace(tmp, path)
