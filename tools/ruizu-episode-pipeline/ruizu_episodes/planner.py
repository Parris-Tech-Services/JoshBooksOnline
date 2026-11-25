"""Decide what each player should get next. Pure functions over stored facts.

For every show a player is allowed, the next episodes are the lowest-numbered
ones that player has never had (copied, found on it, or since removed by
hand: a removed episode is not pushed back). Shows are interleaved round-robin
so a short session spreads across as many shows as possible, and within each
round smaller files go first to keep downloads down.
"""
from __future__ import annotations

from collections import defaultdict
from collections.abc import Iterable

from .config import PlayerPolicy
from .store import Episode

UNKNOWN_SIZE = 1 << 62
_PLAYABLE_STATES = {"discovered", "staged"}


def episode_order(ep: Episode) -> tuple:
    return (ep.season if ep.season is not None else 999, ep.episode if ep.episode is not None else 9999, ep.label)


def by_show(episodes: Iterable[Episode]) -> dict[str, list[Episode]]:
    """Queueable episodes per show in viewing order. Numbered episodes win; a show
    with no numbered episodes at all offers its extras instead."""
    grouped: dict[str, list[Episode]] = defaultdict(list)
    for ep in episodes:
        if ep.kind != "duplicate" and ep.state in _PLAYABLE_STATES:
            grouped[ep.show].append(ep)
    result = {}
    for show, eps in grouped.items():
        numbered = [e for e in eps if e.kind == "episode"]
        result[show] = sorted(numbered or eps, key=episode_order)
    return result


def next_for_player(shows: dict[str, list[Episode]], had: set[str], policy: PlayerPolicy) -> list[Episode]:
    """Up to `per_show` episodes per allowed show, round-robin across shows.

    An episode that is waiting out a retry back-off keeps its place, so a show
    is not reordered by a temporary Drive hiccup. One that failed for good was
    already left out by by_show, so a single broken file never stalls a show.
    """
    per_show = []
    for show in sorted(shows, key=str.lower):
        if policy.allows(show):
            per_show.append([ep for ep in shows[show] if ep.drive_id not in had][: policy.per_show])
    ordered: list[Episode] = []
    for rank in range(policy.per_show):
        layer = [eps[rank] for eps in per_show if rank < len(eps)]
        ordered.extend(sorted(layer, key=lambda e: (e.source_bytes or UNKNOWN_SIZE, e.label)))
    return ordered


def staging_queue(wanted: Iterable[list[Episode]], now: int) -> list[Episode]:
    """Episodes to download and convert, interleaving every player's wishes."""
    lists = [list(w) for w in wanted]
    seen: set[str] = set()
    queue = []
    for rank in range(max((len(w) for w in lists), default=0)):
        for wishes in lists:
            ep = wishes[rank] if rank < len(wishes) else None
            if ep and ep.drive_id not in seen and ep.state == "discovered" and ep.retry_at <= now:
                seen.add(ep.drive_id)
                queue.append(ep)
    return queue


def ready_to_copy(wanted: list[Episode]) -> list[Episode]:
    return [ep for ep in wanted if ep.state == "staged"]
