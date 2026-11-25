"""Check every AMV on a player for completeness, with no reference copy needed.

Exists because a copy cut short by a pulled cable keeps its header but loses frames, and once
the staged file has been cleared from the phone there is nothing left to hash against. The
AMV header still records the length the file was meant to have, so comparing it with the
frames actually present finds truncated episodes (see media.header_seconds).

Read-only unless asked to repair. Repair deletes only a file that is proven truncated *and*
carries the label of an episode this tool knows, then forgets that it was copied, so the next
run prepares it again from Drive and copies a whole one. Nothing else on the player is touched.
"""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from . import player
from .media import AmvCheck, Media
from .names import player_filename
from .store import Store


@dataclass(frozen=True)
class FileReport:
    name: str
    check: AmvCheck
    known: bool
    repaired: bool


def _amv_files(root: Path) -> list[str]:
    return sorted(n for n in player.inventory(root) if n.lower().endswith(".amv"))


def check_player(store: Store, media: Media, root: Path, player_id: str | None, repair: bool,
                 now: int) -> list[FileReport]:
    episodes = {player_filename(e.label): e for e in store.episodes()}
    reports = []
    for name in _amv_files(root):
        result = media.inspect_amv(root / "VIDEO" / name)
        episode = episodes.get(name)
        repaired = False
        if repair and player_id and episode and result.status == "truncated":
            (root / "VIDEO" / name).unlink()
            store.clear_copy(player_id, episode.drive_id)
            store.log(now, "warn", f"removed truncated {name} ({result.detail}); it will be copied again")
            repaired = True
        reports.append(FileReport(name, result, episode is not None, repaired))
    return reports


def describe(report: FileReport) -> str:
    check = report.check
    if check.status == "ok":
        return f"OK            {report.name} ({check.frames // 15 // 60} min)"
    status = {"truncated": "TRUNCATED", "unverifiable": "UNVERIFIABLE", "wrong_profile": "WRONG PROFILE",
              "unreadable": "UNREADABLE"}[check.status]
    if report.repaired:
        suffix = " — removed; it will be prepared and copied again"
    elif check.status == "truncated":
        suffix = (" — run check-player --repair to replace it" if report.known
                  else " — not one of this tool's episodes; left alone")
    else:
        suffix = ""
    return f"{status:13} {report.name}: {check.detail}{suffix}"


def summary(reports: list[FileReport]) -> str:
    counts: dict[str, int] = {}
    for report in reports:
        counts[report.check.status] = counts.get(report.check.status, 0) + 1
    parts = ", ".join(f"{status.replace('_', ' ')} {n}" for status, n in sorted(counts.items()))
    repaired = sum(1 for r in reports if r.repaired)
    return f"checked {len(reports)} AMV file(s): {parts or 'none'}" + (f"; repaired {repaired}" if repaired else "")


def needs_attention(reports: list[FileReport]) -> bool:
    return any(r.check.status != "ok" and not r.repaired for r in reports)
