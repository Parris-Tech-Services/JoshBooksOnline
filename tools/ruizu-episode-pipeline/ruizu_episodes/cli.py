"""ruizu-episodes: command line for the persistent RUIZU episode pipeline.

Typical use on the phone:
  ruizu-episodes import-legacy          # once: adopt the old scripts' state
  ruizu-episodes claim "Kid's player"  # once per player, while it is plugged in
  ruizu-episodes allow "Kid's player" "How I Met Your Mother" Heroes
  ruizu-episodes run --minutes 7        # copy what's ready, then prepare more
  ruizu-episodes status
"""
from __future__ import annotations

import argparse
import datetime as dt
import fcntl
import shutil
import signal
import sqlite3
import sys
import time
from contextlib import closing, contextmanager
from pathlib import Path

from . import __version__, library, planner, player, termux
from . import config as cfg
from .config import ALL_SHOWS, Config, ConfigError, PlayerPolicy
from .drive import DriveClient
from .pipeline import MB, Pipeline, RunOptions
from .process import Runner
from .settings import Settings
from .store import SCHEMA_VERSION, Store, StoreError

EXIT_BUSY = 75
SCHEDULED_MINUTES = 9  # Android stops scheduled jobs after roughly ten minutes


class Busy(RuntimeError):
    pass


@contextmanager
def exclusive(lock_path: Path):
    """One pipeline process at a time: a scheduled run and a widget tap must not interleave."""
    lock_path.parent.mkdir(parents=True, exist_ok=True)
    with open(lock_path, "w") as handle:
        try:
            fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as exc:
            raise Busy("another ruizu-episodes run is in progress; try again shortly") from exc
        yield


def deadline_from(args: argparse.Namespace, now: float) -> float | None:
    if getattr(args, "minutes", None):
        return now + args.minutes * 60
    if getattr(args, "until", None):
        hour, minute = (int(x) for x in args.until.split(":"))
        today = dt.datetime.fromtimestamp(now)
        target = today.replace(hour=hour, minute=minute, second=0, microsecond=0)
        if target.timestamp() <= now:
            target += dt.timedelta(days=1)
        return target.timestamp()
    return None


def _hhmm(text: str) -> str:
    parts = text.split(":")
    if len(parts) != 2 or not all(p.isdigit() for p in parts) or int(parts[0]) > 23 or int(parts[1]) > 59:
        raise argparse.ArgumentTypeError("use 24-hour HH:MM, e.g. 22:10")
    return text


# ---- commands --------------------------------------------------------------
def run_options(args: argparse.Namespace, now: float) -> RunOptions:
    if args.scheduled and not (args.minutes or args.until):
        args.minutes = SCHEDULED_MINUTES
    return RunOptions(
        scan=not args.no_scan, fetch=not args.no_fetch, copy=not args.no_copy,
        deadline=deadline_from(args, now), max_download_mb=args.max_download_mb,
        allow_metered=args.allow_metered or args.scheduled,  # the scheduler only starts jobs on unmetered networks
    )


def cmd_run(ctx: Context, args: argparse.Namespace) -> int:
    options = run_options(args, ctx.clock())
    with exclusive(ctx.settings.lock_path):
        report = ctx.pipeline(echo=None if args.quiet else print).run(options)
    progressed = bool(report.copied or report.staged)
    if (args.notify or args.scheduled) and (progressed or report.failed):
        termux.notify(ctx.runner, "RUIZU episodes", report.summary())
    return 1 if report.failed and not progressed else 0


def cmd_scan(ctx: Context, args: argparse.Namespace) -> int:
    with exclusive(ctx.settings.lock_path):
        limits = ctx.config.limits
        ttl = 0 if args.refresh else limits.listing_ttl_hours * 3600
        result = library.scan(ctx.store, DriveClient(ctx.settings, ctx.runner), ctx.config.library_roots,
                              int(ctx.clock()), ttl, args.max_listings or limits.max_listings_per_run, lambda: True)
    print(f"listed {result.listed} folder(s); {len(result.new_episodes)} new episode(s); "
          f"{result.failed} failed; {result.gone} no longer in Drive")
    for label in result.new_episodes[:40]:
        print(f"  + {label}")
    return 0


def cmd_import(ctx: Context, _args: argparse.Namespace) -> int:
    with exclusive(ctx.settings.lock_path):
        result = library.import_legacy(ctx.store, ctx.settings, int(ctx.clock()))
    print(f"shows: {result.shows}, episodes: {result.episodes}, relabelled: {result.relabelled}, "
          f"staged files adopted: {result.staged}, unreadable lines skipped: {result.skipped_lines}")
    if result.unmatched_staged:
        print(f"staged files with no known Drive source (left as they are): {', '.join(result.unmatched_staged)}")
    return 0


def _mounted_root(ctx: Context, wanted: str | None) -> Path:
    roots = [Path(wanted)] if wanted else list(ctx.settings.player_roots)
    for root in roots:
        if player.is_mounted(root):
            return root
    raise ConfigError("no player is mounted (looked for a VIDEO folder at " + ", ".join(map(str, roots)) + ")")


def cmd_claim(ctx: Context, args: argparse.Namespace) -> int:
    with exclusive(ctx.settings.lock_path):
        root = _mounted_root(ctx, args.root)
        identity = player.read_identity(root)
        if identity is None:
            identity = player.write_identity(root, args.name)
            print(f"wrote identity marker {player.MARKER} to {root}: {identity.name} ({identity.id[:8]})")
        else:
            print(f"this player already has an identity: {identity.name} ({identity.id[:8]}); using it")
        existing = ctx.config.players.get(identity.id)
        policy = existing or PlayerPolicy(name=identity.name)
        cfg.save(ctx.settings.config_path, ctx.config.with_player(identity.id, policy))
    print(f"{policy.name} may receive: {describe_shows(policy)}")
    if not policy.shows:
        print(f'next: ruizu-episodes allow "{policy.name}" "<show>" ...   (see: ruizu-episodes shows)')
    return 0


def _resolve_shows(ctx: Context, wanted: list[str]) -> list[str]:
    known = {s.casefold(): s for s in ctx.store.shows()}
    missing = [w for w in wanted if w.casefold() not in known]
    if missing:
        raise ConfigError(f"unknown show(s): {', '.join(missing)}. Known: {', '.join(known.values()) or 'none yet'}. "
                          "If a show is new in Drive, run `ruizu-episodes scan` first.")
    return [known[w.casefold()] for w in wanted]


def cmd_allow(ctx: Context, args: argparse.Namespace) -> int:
    with exclusive(ctx.settings.lock_path):
        pid, policy = ctx.config.player_named(args.player)
        if args.all:
            shows: tuple[str, ...] | str = ALL_SHOWS
        else:
            current = () if policy.shows == ALL_SHOWS else policy.shows
            added = _resolve_shows(ctx, args.shows)
            shows = tuple(dict.fromkeys([*current, *added]))
        updated = PlayerPolicy(policy.name, shows, policy.per_show, policy.min_free_mb)
        cfg.save(ctx.settings.config_path, ctx.config.with_player(pid, updated))
    print(f"{policy.name} may receive: {'every show' if shows == ALL_SHOWS else ', '.join(shows)}")
    return 0


def cmd_deny(ctx: Context, args: argparse.Namespace) -> int:
    with exclusive(ctx.settings.lock_path):
        pid, policy = ctx.config.player_named(args.player)
        if policy.shows == ALL_SHOWS:
            raise ConfigError("this player allows every show; use `allow` with an explicit list first")
        drop = {s.casefold() for s in args.shows}
        updated = PlayerPolicy(policy.name, tuple(s for s in policy.shows if s.casefold() not in drop),
                               policy.per_show, policy.min_free_mb)
        cfg.save(ctx.settings.config_path, ctx.config.with_player(pid, updated))
    print(f"{policy.name} may receive: {describe_shows(updated)} (files already on it are not touched)")
    return 0


def cmd_per_show(ctx: Context, args: argparse.Namespace) -> int:
    with exclusive(ctx.settings.lock_path):
        pid, policy = ctx.config.player_named(args.player)
        updated = PlayerPolicy(policy.name, policy.shows, args.count, policy.min_free_mb)
        cfg.save(ctx.settings.config_path, ctx.config.with_player(pid, updated))
    print(f"{policy.name}: {args.count} new episode(s) of each show per sync")
    return 0


def cmd_add_root(ctx: Context, args: argparse.Namespace) -> int:
    if not cfg.SAFE_ID.match(args.folder_id):
        raise ConfigError("that does not look like a Drive folder id")
    with exclusive(ctx.settings.lock_path):
        roots = tuple(dict.fromkeys([*ctx.config.library_roots, args.folder_id]))
        cfg.save(ctx.settings.config_path, Config(roots, ctx.config.players, ctx.config.limits))
    print("library root added; new show folders under it are found on the next run")
    return 0


def cmd_retry(ctx: Context, args: argparse.Namespace) -> int:
    failed = ctx.store.episodes("state = 'failed'")
    wanted = {x.casefold() for x in args.labels}
    chosen = [e for e in failed if args.all or e.label.casefold() in wanted]
    with exclusive(ctx.settings.lock_path):
        count = ctx.store.retry([e.drive_id for e in chosen], int(ctx.clock()))
    print(f"{count} episode(s) will be tried again on the next run")
    return 0


def cmd_log(ctx: Context, args: argparse.Namespace) -> int:
    for row in ctx.store.recent_events(args.lines):
        stamp = time.strftime("%d %b %H:%M", time.localtime(row["at"]))
        print(f"{stamp} {row['level']:5} {row['message']}")
    return 0


def cmd_restore(ctx: Context, _args: argparse.Namespace) -> int:
    backup = ctx.settings.db_path.with_suffix(".db.bak")
    if not backup.is_file():
        raise StoreError(f"no backup at {backup}")
    with closing(sqlite3.connect(str(backup))) as check:
        version = check.execute("PRAGMA user_version").fetchone()[0]
        if check.execute("PRAGMA integrity_check").fetchone()[0] != "ok" or not 0 < version <= SCHEMA_VERSION:
            raise StoreError("the backup is damaged or from a newer version; not restoring it")
    with exclusive(ctx.settings.lock_path):
        ctx.close()
        shutil.copyfile(backup, ctx.settings.db_path)
    print(f"restored {ctx.settings.db_path} from the backup taken at the start of the last run")
    return 0


def cmd_schedule(ctx: Context, args: argparse.Namespace) -> int:
    script = ctx.settings.state_dir / "scheduled-run.sh"
    package_parent = Path(__file__).resolve().parent.parent
    script.write_text(
        "#!/data/data/com.termux/files/usr/bin/sh\n"
        f"cd '{package_parent}' && exec '{sys.executable}' -m ruizu_episodes run --scheduled --quiet\n",
        encoding="utf-8",
    )
    script.chmod(0o700)
    print(termux.schedule(ctx.runner, script, args.every))
    print(f"scheduled every {args.every} min on unmetered networks while the battery is not low")
    return 0


def cmd_unschedule(ctx: Context, _args: argparse.Namespace) -> int:
    print(termux.unschedule(ctx.runner))
    return 0


def cmd_shows(ctx: Context, _args: argparse.Namespace) -> int:
    known: dict[str, list[str]] = {show: [] for show in ctx.store.shows()}
    for ep in ctx.store.episodes("kind != 'duplicate'"):
        known.setdefault(ep.show, []).append(ep.state)
    for show, states in known.items():
        print(f"{show}: {len(states)} known, {states.count('staged')} prepared")
    return 0


def cmd_status(ctx: Context, _args: argparse.Namespace) -> int:
    for line in status_lines(ctx):
        print(line)
    return 0


# ---- status ----------------------------------------------------------------
def _size(ep) -> str:
    return f" ({ep.source_bytes // MB} MB)" if ep.source_bytes else ""


def describe_shows(policy: PlayerPolicy) -> str:
    if policy.shows == ALL_SHOWS:
        return "every show"
    return ", ".join(policy.shows) or "no shows allowed yet"


def _preview(items: list[str], limit: int, empty: str) -> str:
    more = " …" if len(items) > limit else ""
    return (", ".join(items[:limit]) or empty) + more


def _player_lines(ctx: Context, pid: str, policy: PlayerPolicy) -> list[str]:
    wanted = ctx.pipeline().wanted(pid, policy)
    ready = [e.label for e in planner.ready_to_copy(wanted)]
    todo = [e.label + _size(e) for e in wanted if e.state == "discovered"]
    return [
        f"  {policy.name} ({pid[:8]}): {describe_shows(policy)} · {policy.per_show} per show",
        f"    ready to copy: {_preview(ready, 8, 'nothing yet')}",
        f"    to prepare:    {_preview(todo, 6, 'nothing')}",
    ]


def _connected_line(ctx: Context) -> str:
    for root in ctx.settings.player_roots:
        if player.is_mounted(root):
            identity = player.read_identity(root)
            free = player.free_bytes(root) // (1024 * MB)
            if identity is None:
                return f"Connected now: an unidentified player at {root} ({free} GB free) — run `claim`"
            name = ctx.config.players[identity.id].name if identity.id in ctx.config.players else identity.name
            return f"Connected now: {name} ({identity.id[:8]}), {free} GB free"
    return "No player connected"


def status_lines(ctx: Context) -> list[str]:
    now = int(ctx.clock())
    episodes = ctx.store.episodes("kind != 'duplicate'")
    staged = [e for e in episodes if e.state == "staged"]
    failed = [e for e in episodes if e.state == "failed"]
    waiting = [e for e in episodes if e.state == "discovered" and e.retry_at > now]
    ttl = ctx.config.limits.listing_ttl_hours * 3600
    due = len(ctx.store.folders_due(now, now - ttl, 10_000))
    lines = [
        f"RUIZU episodes {__version__} — {time.strftime('%d %b %H:%M', time.localtime(now))}",
        f"Library: {len(ctx.store.shows())} shows, {len(episodes)} episodes known; {due} folder listing(s) due",
        f"Prepared on phone: {len(staged)} ({ctx.store.staged_bytes() // MB} MB) · "
        f"waiting to retry: {len(waiting)} · failed: {len(failed)}",
    ]
    lines += [f"  failed: {e.label} — {e.last_error}" for e in failed[:5]]
    lines.append("Players:" if ctx.config.players else "Players: none set up — plug one in and run `claim`")
    for pid, policy in ctx.config.players.items():
        lines += _player_lines(ctx, pid, policy)
    lines.append(_connected_line(ctx))
    return lines


# ---- wiring ----------------------------------------------------------------
class Context:
    def __init__(self, settings: Settings, runner: Runner | None = None, clock=time.time):
        self.settings = settings
        self.runner = runner or Runner()
        self.clock = clock
        self.config = cfg.load(settings.config_path)
        self.store = Store(settings.db_path)

    def pipeline(self, echo=None) -> Pipeline:
        return Pipeline(self.settings, self.config, self.store, self.runner, self.clock, echo)

    def close(self) -> None:
        self.store.close()


COMMANDS = {
    "status": cmd_status, "run": cmd_run, "scan": cmd_scan, "import-legacy": cmd_import,
    "claim": cmd_claim, "allow": cmd_allow, "deny": cmd_deny, "per-show": cmd_per_show,
    "add-root": cmd_add_root, "shows": cmd_shows, "retry": cmd_retry, "log": cmd_log,
    "restore-backup": cmd_restore, "schedule": cmd_schedule, "unschedule": cmd_unschedule,
}


def build_parser() -> argparse.ArgumentParser:
    ap = argparse.ArgumentParser(prog="ruizu-episodes", description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="command", required=True)
    sub.add_parser("status", help="what is known, prepared and ready for each player")
    run = sub.add_parser("run", help="copy what is ready, discover, prepare more, copy again")
    when = run.add_mutually_exclusive_group()
    when.add_argument("--minutes", type=int, help="stop starting new work after this many minutes")
    when.add_argument("--until", type=_hhmm, help="stop starting new work that would run past HH:MM")
    run.add_argument("--no-scan", action="store_true", help="do not list Drive folders this run")
    run.add_argument("--no-fetch", action="store_true", help="do not download or convert this run")
    run.add_argument("--no-copy", action="store_true", help="do not write to a connected player")
    run.add_argument("--max-download-mb", type=int, help="override the per-run download allowance")
    run.add_argument("--allow-metered", action="store_true", help="download even if Wi-Fi cannot be confirmed")
    run.add_argument("--scheduled", action="store_true", help=argparse.SUPPRESS)
    run.add_argument("--notify", action="store_true", help="post an Android notification with the result")
    run.add_argument("--quiet", action="store_true")
    scan = sub.add_parser("scan", help="list due Drive folders now (discovery only)")
    scan.add_argument("--refresh", action="store_true", help="treat every folder as due")
    scan.add_argument("--max-listings", type=int)
    sub.add_parser("import-legacy", help="adopt the old scripts' lists and staged files (read-only, repeatable)")
    claim = sub.add_parser("claim", help="give the connected player an identity marker")
    claim.add_argument("name")
    claim.add_argument("--root", help="player mount (default: the usual mount point)")
    allow = sub.add_parser("allow", help="let a player receive shows")
    allow.add_argument("player")
    allow.add_argument("shows", nargs="*")
    allow.add_argument("--all", action="store_true", help="every show, including ones added to Drive later")
    deny = sub.add_parser("deny", help="stop sending shows to a player")
    deny.add_argument("player")
    deny.add_argument("shows", nargs="+")
    per = sub.add_parser("per-show", help="how many new episodes of each show per sync")
    per.add_argument("player")
    per.add_argument("count", type=int, choices=range(1, 21), metavar="1-20")
    root = sub.add_parser("add-root", help="a Drive folder whose sub-folders are shows")
    root.add_argument("folder_id")
    sub.add_parser("shows", help="known shows")
    retry = sub.add_parser("retry", help="try failed episodes again")
    retry.add_argument("labels", nargs="*")
    retry.add_argument("--all", action="store_true")
    log = sub.add_parser("log", help="recent pipeline events")
    log.add_argument("-n", "--lines", type=int, default=30)
    sub.add_parser("restore-backup", help="put back the database snapshot from the start of the last run")
    schedule = sub.add_parser("schedule", help="run automatically with the Termux job scheduler")
    schedule.add_argument("--every", type=int, default=15, help="minutes between runs (Android minimum 15)")
    sub.add_parser("unschedule", help="stop the automatic runs")
    return ap


def _stop_on_signal(signum, _frame):
    raise SystemExit(128 + signum)  # unwinds through cleanup, so no partial file is left behind


def main(argv: list[str] | None = None, settings: Settings | None = None, runner: Runner | None = None,
         clock=time.time) -> int:
    for sig in (signal.SIGTERM, signal.SIGHUP):
        signal.signal(sig, _stop_on_signal)
    args = build_parser().parse_args(argv)
    try:
        ctx = Context(settings or Settings.from_env(), runner, clock)
    except (ConfigError, StoreError) as exc:
        print(f"ruizu-episodes: {exc}", file=sys.stderr)
        return 1
    try:
        return COMMANDS[args.command](ctx, args)
    except Busy as exc:
        print(f"ruizu-episodes: {exc}", file=sys.stderr)
        return EXIT_BUSY
    except (ConfigError, StoreError, player.CopyError, OSError) as exc:
        print(f"ruizu-episodes: {exc}", file=sys.stderr)
        return 1
    finally:
        ctx.close()
