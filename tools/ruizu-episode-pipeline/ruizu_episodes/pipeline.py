"""One bounded run: copy what is ready, discover a little, stage a little, copy again.

Each episode moves through its own steps (discover -> download -> convert ->
verify -> stage -> copy -> verify on the card) and records every step as it
completes, so any run can stop at any point and the next one carries on.
A failing episode backs off on its own and never blocks the others.
"""
from __future__ import annotations

import math
import os
import time
from collections.abc import Callable
from dataclasses import dataclass, field
from pathlib import Path

from . import library, planner, player, termux
from .config import Config, PlayerPolicy
from .drive import DriveClient, DriveError
from .media import Media, MediaError, sha256_file
from .names import player_relative_path, player_spellings
from .process import Runner
from .settings import Settings
from .store import Episode, Store

MB = 1024 * 1024
DEFAULT_COPY_RATE = 1_000_000  # bytes/s, measured ~1.1 MB/s on the RUIZU before any history exists
DEFAULT_WORK_SECONDS = 240.0  # download + convert of one ~22 minute episode
COPY_SAFETY = 1.5
MAX_DOWNLOAD_SECONDS = 1800


@dataclass
class RunOptions:
    scan: bool = True
    fetch: bool = True
    copy: bool = True
    deadline: float | None = None
    max_download_mb: int | None = None
    allow_metered: bool = False


@dataclass
class Report:
    lines: list[str] = field(default_factory=list)
    copied: list[str] = field(default_factory=list)
    staged: list[str] = field(default_factory=list)
    failed: list[str] = field(default_factory=list)
    new_episodes: int = 0

    def summary(self) -> str:
        parts = []
        if self.copied:
            parts.append(f"copied {len(self.copied)} to the player")
        if self.staged:
            parts.append(f"prepared {len(self.staged)}")
        if self.new_episodes:
            parts.append(f"found {self.new_episodes} new in Drive")
        if self.failed:
            parts.append(f"{len(self.failed)} failed")
        return ", ".join(parts) or "nothing to do"


class Budget:
    """Time and download allowance for one run. The clock is injected for tests."""

    def __init__(self, clock: Callable[[], float], deadline: float | None, max_download_bytes: int):
        self.clock = clock
        self.deadline = deadline
        self.max_download_bytes = max_download_bytes
        self.downloaded = 0

    def seconds_left(self) -> float:
        return math.inf if self.deadline is None else self.deadline - self.clock()

    def can_spend(self, seconds: float) -> bool:
        return self.seconds_left() >= seconds

    def can_download(self, size: int) -> bool:
        return self.downloaded + size <= self.max_download_bytes

    def charge(self, size: int) -> None:
        self.downloaded += max(0, size)


@dataclass(frozen=True)
class Connected:
    root: Path
    identity: player.Identity
    policy: PlayerPolicy

    @property
    def video(self) -> Path:
        return self.root / "VIDEO"


class Pipeline:
    def __init__(self, settings: Settings, config: Config, store: Store, runner: Runner,
                 clock: Callable[[], float] = time.time, echo: Callable[[str], None] | None = None):
        self.settings = settings
        self.config = config
        self.store = store
        self.runner = runner
        self.clock = clock
        self.echo = echo or (lambda _line: None)
        self.drive = DriveClient(settings, runner)
        self.media = Media(runner)
        self.report = Report()
        # What the connected player had when this run started. Planning against this snapshot
        # keeps `per_show` meaning "new episodes of each show per sync", however many passes copy.
        self.had_at_start: set[str] | None = None

    # ---- plumbing -------------------------------------------------------
    def now(self) -> int:
        return int(self.clock())

    def say(self, message: str, level: str = "info") -> None:
        self.store.log(self.now(), level, message)
        self.report.lines.append(message)
        self.echo(message)

    def staged_path(self, ep: Episode) -> Path:
        return self.settings.stage_dir / f"{ep.label}.amv"

    def wanted(self, player_id: str, policy: PlayerPolicy) -> list[Episode]:
        shows = planner.by_show(self.store.episodes("state IN ('discovered', 'staged')"))
        return planner.next_for_player(shows, set(self.store.copies(player_id)), policy)

    # ---- the run --------------------------------------------------------
    def run(self, options: RunOptions) -> Report:
        self.store.backup()
        limit_mb = options.max_download_mb
        if limit_mb is None:
            limit_mb = self.config.limits.max_download_mb_per_run
        budget = Budget(self.clock, options.deadline, limit_mb * MB)
        connected = self.detect_player() if options.copy else None
        if connected:
            self.reconcile(connected)
            self.copy_ready(connected, budget)
        if options.scan:
            self.scan(budget)
        if options.fetch:
            self.stage(budget, options)
        if connected and player.is_mounted(connected.root):
            # Discovery may have named episodes that were already on the card, so reconcile again
            # before copying: otherwise the first run copies a second copy of what is already there.
            self.reconcile(connected)
            self.copy_ready(connected, budget)
        self.say(f"run finished: {self.report.summary()}")
        return self.report

    # ---- which player ---------------------------------------------------
    def detect_player(self) -> Connected | None:
        for root in self.settings.player_roots:
            if player.is_mounted(root):
                return self._identify(root)
        return None

    def _identify(self, root: Path) -> Connected | None:
        identity = player.read_identity(root)
        if identity is None:
            files = len(player.inventory(root))
            self.say(f"a player is connected at {root} ({files} files in VIDEO) but has no identity marker; "
                     "nothing will be written to it until you run: ruizu-episodes claim \"<name>\"", "warn")
            return None
        policy = self.config.players.get(identity.id)
        if policy is None:
            self.say(f"connected player '{identity.name}' ({identity.id[:8]}) is not set up here; "
                     f"run: ruizu-episodes allow \"{identity.name}\" <show>...", "warn")
            return None
        self.say(f"connected: {policy.name} ({identity.id[:8]}), "
                 f"{player.free_bytes(root) // (1024 * MB)} GB free")
        return Connected(root, identity, policy)

    # ---- reconcile records with what is really on the card --------------
    def reconcile(self, conn: Connected) -> None:
        """Bring the records in line with the card before deciding anything."""
        episodes = {e.drive_id: e for e in self.store.episodes()}
        for drive_id, state in self.store.copies(conn.identity.id).items():
            if state == "copying":
                self._resolve_interrupted(conn, episodes[drive_id])
        files = player.inventory(conn.root)
        self._mark_removed(conn, episodes, files)
        self._adopt_found(conn, episodes, files)
        strays = [name for name in files if name.endswith(player.PARTIAL_SUFFIX)]
        if strays:
            self.say(f"left alone {len(strays)} unfinished file(s) this tool has no record of: {', '.join(strays)}",
                     "warn")

    def _mark_removed(self, conn: Connected, episodes: dict[str, Episode], files: dict[str, int]) -> None:
        """Deleted from the player by hand: remember that, so it is not pushed back."""
        for drive_id, state in self.store.copies(conn.identity.id).items():
            if state not in ("verified", "present"):
                continue
            label = episodes[drive_id].label
            # Every spelling counts as the same episode: the per-show folder path written now, the
            # code-first flat name, and the show-first flat name earlier versions wrote. Reading all
            # of them means restructuring the card is a rename rather than a re-download.
            if not any(name in files for name in player_spellings(label)):
                self.store.set_copy(conn.identity.id, drive_id, "removed", self.now())

    def _adopt_found(self, conn: Connected, episodes: dict[str, Episode], files: dict[str, int]) -> None:
        """Episodes already on the player (copied by the old scripts, or by hand) count as had,
        except a provably truncated copy of our own staged file, which is replaced."""
        known = self.store.copies(conn.identity.id)
        by_name: dict[str, Episode] = {}
        for episode in episodes.values():
            for spelling in player_spellings(episode.label):
                by_name.setdefault(spelling, episode)
        for name, size in files.items():
            ep = by_name.get(name)
            if ep is None or ep.drive_id in known:
                continue
            if self._is_truncated_copy(conn, ep, size, name):
                (conn.video / name).unlink()
                self.say(f"removed a truncated copy of {ep.label} left by an earlier interrupted copy; "
                         "it will be copied again")
            else:
                self.store.set_copy(conn.identity.id, ep.drive_id, "present", self.now(), size)

    def _is_truncated_copy(self, conn: Connected, ep: Episode, size: int, name: str) -> bool:
        staged = self.staged_path(ep)
        if ep.state != "staged" or not ep.amv_bytes or size >= ep.amv_bytes or not staged.is_file():
            return False
        return player.is_own_prefix(staged, conn.video / name)

    def _resolve_interrupted(self, conn: Connected, ep: Episode) -> None:
        """A copy was cut off. Remove only what is provably ours; keep anything verified."""
        name = player_relative_path(ep.label)
        staged = self.staged_path(ep)
        # An interrupted copy can carry any spelling this tool has written over time, in a show
        # folder or flat. All are provably this episode's, so all are cleaned rather than left
        # behind as unidentifiable junk on the card.
        partials = [conn.video / (candidate + player.PARTIAL_SUFFIX)
                    for candidate in player_spellings(ep.label)]
        removed = [p.name for p in partials if p.exists()]
        for leftover in partials:
            if leftover.exists():
                leftover.unlink()
        if removed:
            self.say(f"removed the unfinished copy of {ep.label} from an interrupted run")
        final = next((conn.video / candidate for candidate in player_spellings(ep.label)
                      if (conn.video / candidate).exists()), conn.video / name)
        name = final.relative_to(conn.video).as_posix()
        if not final.exists():
            self.store.clear_copy(conn.identity.id, ep.drive_id)
        elif ep.amv_sha256 and sha256_file(final, drop_cache=True) == ep.amv_sha256:
            self.store.set_copy(conn.identity.id, ep.drive_id, "verified", self.now(), final.stat().st_size)
        elif staged.exists() and player.is_own_prefix(staged, final):
            final.unlink()
            self.store.clear_copy(conn.identity.id, ep.drive_id)
            self.say(f"removed a truncated copy of {ep.label}; it will be copied again")
        else:
            self.store.set_copy(conn.identity.id, ep.drive_id, "present", self.now(), final.stat().st_size)
            self.say(f"{name} on the player is not this tool's file; left untouched", "warn")

    # ---- copy -----------------------------------------------------------
    def copy_ready(self, conn: Connected, budget: Budget) -> None:
        if self.had_at_start is None:
            self.had_at_start = set(self.store.copies(conn.identity.id))
        shows = planner.by_show(self.store.episodes("state IN ('discovered', 'staged')"))
        done = self.store.copies(conn.identity.id)
        planned = planner.next_for_player(shows, self.had_at_start, conn.policy)
        for ep in planner.ready_to_copy([e for e in planned if e.drive_id not in done]):
            if not player.is_mounted(conn.root):
                self.say("player unplugged; stopping copies (nothing half-written is left in VIDEO)", "warn")
                return
            if not self._copy_one(conn, ep, budget):
                return

    def _ensure_hash(self, ep: Episode, staged: Path) -> str | None:
        """Hash (and re-verify) a staged file imported from the old scripts, once."""
        if ep.amv_sha256:
            return ep.amv_sha256
        try:
            self.media.verify_amv(staged)
        except MediaError as exc:
            self.store.unstage(ep.drive_id, f"staged file failed verification: {exc}", self.now())
            self.say(f"{ep.label}: staged file is not a valid AMV ({exc}); it will be rebuilt", "warn")
            return None
        digest = sha256_file(staged)
        self.store.mark_staged(ep.drive_id, staged.stat().st_size, digest, None, self.now())
        return digest

    def _copy_fits(self, conn: Connected, ep: Episode, size: int, budget: Budget) -> bool:
        if player.free_bytes(conn.root) - size < conn.policy.min_free_mb * MB:
            self.say(f"{conn.policy.name} is nearly full; stopping before {ep.label}", "warn")
            return False
        estimate = size / self.store.copy_rate(DEFAULT_COPY_RATE) * COPY_SAFETY + 5
        if not budget.can_spend(estimate):
            self.say(f"stopping: copying {ep.label} (~{int(estimate)}s) might not finish before the deadline")
            return False
        return True

    def _copy_one(self, conn: Connected, ep: Episode, budget: Budget) -> bool:
        """Copy one episode. Returns False when copying should stop for this run."""
        staged = self.staged_path(ep)
        if not staged.is_file():
            self.store.unstage(ep.drive_id, "staged file missing", self.now())
            self.say(f"{ep.label}: staged file has gone missing; it will be rebuilt", "warn")
            return True
        digest = self._ensure_hash(ep, staged)
        if digest is None:
            return True  # unstaged for rebuilding; carry on with the next episode
        size = staged.stat().st_size
        if not self._copy_fits(conn, ep, size, budget):
            return False
        self.store.set_copy(conn.identity.id, ep.drive_id, "copying", self.now())
        try:
            seconds = player.copy_verified(staged, conn.video, player_relative_path(ep.label), digest)
        except (player.CopyError, OSError) as exc:
            still_mounted = player.is_mounted(conn.root)
            if still_mounted:
                self.store.clear_copy(conn.identity.id, ep.drive_id)
            # else: keep the 'copying' record; reconcile removes any partial left on the card next time
            self.say(f"copy of {ep.label} failed: {exc}", "error")
            self.report.failed.append(ep.label)
            return still_mounted
        self.store.set_copy(conn.identity.id, ep.drive_id, "verified", self.now(), size, seconds)
        self.report.copied.append(ep.label)
        self.say(f"copied {ep.label} to {conn.policy.name} ({size // MB} MB, {seconds:.0f}s, SHA-256 verified)")
        return True

    # ---- discover -------------------------------------------------------
    def scan(self, budget: Budget) -> None:
        limits = self.config.limits
        result = library.scan(self.store, self.drive, self.config.library_roots, self.now(),
                              limits.listing_ttl_hours * 3600, limits.max_listings_per_run,
                              lambda: budget.can_spend(30))
        self.report.new_episodes += len(result.new_episodes)
        if result.listed or result.failed:
            self.say(f"library: listed {result.listed} folder(s), {len(result.new_episodes)} new episode(s), "
                     f"{result.failed} listing(s) failed")

    # ---- stage ----------------------------------------------------------
    def stage(self, budget: Budget, options: RunOptions) -> None:
        if not self.config.players:
            self.say("no players are set up yet, so nothing is downloaded. Plug one in and run: "
                     "ruizu-episodes claim \"<name>\"", "warn")
            return
        wishes = [self.wanted(pid, policy) for pid, policy in self.config.players.items()]
        queue = planner.staging_queue(wishes, self.now())
        if queue and self._network_ok(options):
            for ep in queue:
                if not self._room_to_stage() or not self._stage_one(ep, budget):
                    return

    def _network_ok(self, options: RunOptions) -> bool:
        if options.allow_metered or not self.config.limits.require_unmetered:
            return True
        on_wifi = termux.wifi_connected(self.runner)
        if on_wifi:
            return True
        reason = "not on Wi-Fi" if on_wifi is False else "could not confirm Wi-Fi (Termux:API missing?)"
        self.say(f"not downloading: {reason}. Use --allow-metered to download anyway.", "warn")
        return False

    def _room_to_stage(self) -> bool:
        limits = self.config.limits
        if self.store.staged_bytes() >= limits.max_staged_mb * MB:
            self.say(f"staging folder has reached its {limits.max_staged_mb} MB cap; not downloading more", "warn")
            return False
        self.settings.work_dir.mkdir(parents=True, exist_ok=True)
        if player.free_bytes(self.settings.stage_dir) < limits.min_phone_free_mb * MB:
            self.say("phone storage is low; not downloading more", "warn")
            return False
        return True

    def _source_size(self, ep: Episode) -> int | None:
        if ep.source_bytes:
            return ep.source_bytes
        size = self.drive.probe_size(ep.drive_id)
        if size:
            self.store.set_source_bytes(ep.drive_id, size, self.now())
        return size

    def _affordable(self, ep: Episode, size: int | None, budget: Budget) -> bool:
        if size and not budget.can_download(size):
            self.say(f"download allowance for this run used; {ep.label} waits for the next run")
            return False
        if not budget.can_spend(self.store.average_work_seconds(DEFAULT_WORK_SECONDS)):
            self.say(f"not enough time left to prepare {ep.label}; it waits for the next run")
            return False
        return True

    def _stage_one(self, ep: Episode, budget: Budget) -> bool:
        """Download, convert, verify and stage one episode. Returns False to stop staging."""
        size = self._source_size(ep)
        if not self._affordable(ep, size, budget):
            return False
        work = self.settings.work_dir
        part, src, amv_part = (work / f"{ep.drive_id}{suffix}" for suffix in (".src.part", ".src", ".amv.part"))
        started = time.monotonic()
        try:
            self._download(ep, size, part, src, budget)
            duration = self.media.source_duration(src)
            self.media.encode(src, amv_part)
            self.media.verify_amv(amv_part, duration)
            self._publish(ep, amv_part, time.monotonic() - started)
            src.unlink(missing_ok=True)
        except DriveError as exc:
            return self._drive_failed(ep, exc, part)
        except MediaError as exc:
            self._failed(ep, str(exc), exc.permanent)
            if exc.permanent:
                src.unlink(missing_ok=True)
        finally:
            amv_part.unlink(missing_ok=True)
        return True

    def _download(self, ep: Episode, size: int | None, part: Path, src: Path, budget: Budget) -> None:
        if src.exists():
            return  # downloaded by an earlier run that stopped before converting
        before = part.stat().st_size if part.exists() else 0
        timeout = int(max(60, min(MAX_DOWNLOAD_SECONDS, budget.seconds_left())))
        self.say(f"downloading {ep.label}" + (f" ({size // MB} MB)" if size else ""))
        got = self.drive.download(ep.drive_id, part, size, timeout)
        budget.charge(got - before)
        os.replace(part, src)

    def _publish(self, ep: Episode, amv_part: Path, seconds: float) -> None:
        target = self.staged_path(ep)
        os.replace(amv_part, target)  # a stale encode under this name is derived data; the verified one wins
        digest = sha256_file(target)
        self.store.mark_staged(ep.drive_id, target.stat().st_size, digest, seconds, self.now())
        self.report.staged.append(ep.label)
        self.say(f"prepared {ep.label} ({target.stat().st_size // MB} MB AMV in {seconds:.0f}s)")

    def _drive_failed(self, ep: Episode, exc: DriveError, part: Path) -> bool:
        if exc.timed_out:
            self.say(str(exc))
            return False
        if exc.permanent:
            part.unlink(missing_ok=True)
        self._failed(ep, str(exc), exc.permanent)
        if exc.throttled:
            self.say("Drive is throttling downloads; stopping for this run", "warn")
        return not exc.throttled

    def _failed(self, ep: Episode, error: str, permanent: bool) -> None:
        updated = self.store.record_failure(ep.drive_id, error, self.now(), permanent)
        self.report.failed.append(ep.label)
        when = "gave up (run `ruizu-episodes retry` to try again)" if updated.state == "failed" else "will retry later"
        self.say(f"{ep.label}: {error} — {when}", "error")
