"""Adopting the old scripts' state, and the command line a person actually types."""
from __future__ import annotations

import contextlib
import io
import json
import tempfile
import unittest
from pathlib import Path

from ruizu_episodes import cli, library, player
from ruizu_episodes import config as cfg
from ruizu_episodes.media import Media, sha256_file
from ruizu_episodes.names import PLAYER_SUFFIX
from ruizu_episodes.pipeline import Pipeline, RunOptions
from ruizu_episodes.process import Runner
from ruizu_episodes.store import Store

from .support import FakeClock, OfflineTermux, Sandbox, needs_tools, synthetic_episode

HIMYM_FOLDER = "1pHIMYMfolder000000000000000"
LEGACY = {f"How I Met Your Mother S02E{n:02d}": f"1HIMYMs02e{n:02d}00000000000000" for n in (10, 11, 12, 13)}


def make_amv(path: Path) -> None:
    src = path.with_suffix(".src.mkv")
    src.write_bytes(synthetic_episode(12))
    Media(Runner()).encode(src, path)
    src.unlink()


class LegacyHome:
    """The files the hand-run scripts left on the phone, in their real formats."""

    def __init__(self, sandbox: Sandbox, staged: tuple[str, ...]):
        s = sandbox.settings
        for d in (s.state_dir, s.investigate_dir, s.stage_dir):
            d.mkdir(parents=True, exist_ok=True)
        (s.investigate_dir / "tv-items.json").write_text(json.dumps({
            HIMYM_FOLDER: ["How I Met Your Mother", "application/vnd.google-apps.folder"],
            "not an id": ["Bad", "application/vnd.google-apps.folder"],
        }))
        (s.state_dir / "episodes.tsv").write_text("".join(
            f"How I Met Your Mother/Season 2/How I Met Your Mother {label[-6:]}.mkv\t183572480\t{fid}\n"
            for label, fid in LEGACY.items()) + "garbage line\n")
        (s.state_dir / "himym-order.tsv").write_text("".join(f"{fid}\t{label}\t0\n" for label, fid in LEGACY.items()))
        (s.state_dir / "other-shows.tsv").write_text("1obBuffyAbroad000000000000\tBuffy S05 - Buffy Abroad\t43139967\n")
        for label in staged:
            make_amv(s.stage_dir / f"{label}.amv")
        make_amv(s.stage_dir / "Unknown Show S01E01.amv")


@needs_tools
class LegacyImportTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.sandbox = Sandbox(Path(self.tmp.name))
        self.staged = ("How I Met Your Mother S02E10", "How I Met Your Mother S02E11", "How I Met Your Mother S02E12")
        LegacyHome(self.sandbox, self.staged)
        self.store = Store(self.sandbox.settings.db_path)

    def tearDown(self):
        self.store.close()
        self.tmp.cleanup()

    def test_import_is_read_only_and_repeatable(self):
        before = {p: p.read_bytes() for p in Path(self.tmp.name).rglob("*") if p.is_file() and "state" not in p.parts}
        first = library.import_legacy(self.store, self.sandbox.settings, 100)
        second = library.import_legacy(self.store, self.sandbox.settings, 200)
        after = {p: p.read_bytes() for p in before}
        self.assertEqual(before, after, "legacy files and staged episodes are not modified")
        self.assertEqual((first.shows, first.staged), (1, 3))
        self.assertEqual(first.unmatched_staged, ["Unknown Show S01E01.amv"])
        self.assertEqual((second.episodes, second.staged), (0, 0))
        self.assertEqual(self.store.episode("1obBuffyAbroad000000000000").label, "Buffy S05 - Buffy Abroad")

    def test_player_contents_are_recognised_and_the_next_episode_follows(self):
        library.import_legacy(self.store, self.sandbox.settings, 100)
        video = self.sandbox.player / "VIDEO"
        make_amv(video / ("How I Met Your Mother S02E10" + PLAYER_SUFFIX))  # put there by the old scripts
        (video / "All DX.avi").write_bytes(b"vendor sample")
        kid = player.write_identity(self.sandbox.player, "Kid's player")
        policy = cfg.PlayerPolicy("Kid's player", ("How I Met Your Mother",), 1, min_free_mb=0)
        config = self.sandbox.write_config({kid.id: policy})
        report = Pipeline(self.sandbox.settings, config, self.store, OfflineTermux(), FakeClock()).run(
            RunOptions(scan=False, fetch=False))
        self.assertEqual(report.copied, ["How I Met Your Mother S02E11"])
        copied = video / ("How I Met Your Mother S02E11" + PLAYER_SUFFIX)
        staged = self.sandbox.settings.stage_dir / "How I Met Your Mother S02E11.amv"
        self.assertEqual(sha256_file(copied), sha256_file(staged))
        self.assertEqual(self.store.copies(kid.id)[LEGACY["How I Met Your Mother S02E10"]], "present")

    def test_a_broken_legacy_staged_file_is_rebuilt_not_copied(self):
        library.import_legacy(self.store, self.sandbox.settings, 100)
        broken = self.sandbox.settings.stage_dir / "How I Met Your Mother S02E10.amv"
        broken.write_bytes(broken.read_bytes()[:3000])
        kid = player.write_identity(self.sandbox.player, "Kid")
        config = self.sandbox.write_config({kid.id: cfg.PlayerPolicy("Kid", ("How I Met Your Mother",), 1, 0)})
        report = Pipeline(self.sandbox.settings, config, self.store, OfflineTermux(), FakeClock()).run(
            RunOptions(scan=False, fetch=False))
        self.assertEqual(report.copied, [], "the show does not skip ahead past an episode being rebuilt")
        self.assertEqual(self.sandbox.video_files(), [])
        self.assertEqual(self.store.episode(LEGACY["How I Met Your Mother S02E10"]).state, "discovered")


class CliTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.sandbox = Sandbox(Path(self.tmp.name))
        self.clock = FakeClock()

    def tearDown(self):
        self.tmp.cleanup()

    def cli(self, *argv: str) -> tuple[int, str]:
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = cli.main(list(argv), self.sandbox.settings, OfflineTermux(), self.clock)
        return code, out.getvalue() + err.getvalue()

    def seed_show(self, name: str = "Scrubs") -> None:
        store = Store(self.sandbox.settings.db_path)
        library.register_video(store, name, None, "Season 1/x.S01E01.mkv", "1scrubs0000000000000000", 0)
        store.close()

    def test_claim_then_allow_sets_up_a_player(self):
        self.seed_show()
        code, out = self.cli("claim", "Kid's player")
        self.assertEqual(code, 0, out)
        self.assertIn("next: ruizu-episodes allow", out)
        identity = player.read_identity(self.sandbox.player)
        code, out = self.cli("allow", "kid's player", "scrubs")
        self.assertEqual(code, 0, out)
        config = cfg.load(self.sandbox.settings.config_path)
        self.assertEqual(config.players[identity.id].shows, ("Scrubs",))
        code, out = self.cli("claim", "Another name")
        self.assertIn("already has an identity", out)
        self.assertEqual(player.read_identity(self.sandbox.player), identity)

    def test_allow_refuses_unknown_shows(self):
        self.seed_show()
        self.cli("claim", "Kid")
        code, out = self.cli("allow", "Kid", "Scrubz")
        self.assertEqual(code, 1)
        self.assertIn("unknown show", out)

    def test_claim_needs_a_mounted_player(self):
        (self.sandbox.player / "VIDEO").rmdir()
        code, out = self.cli("claim", "Kid")
        self.assertEqual(code, 1)
        self.assertIn("no player is mounted", out)

    def test_status_explains_what_to_do_first(self):
        code, out = self.cli("status")
        self.assertEqual(code, 0)
        self.assertIn("none set up", out)
        self.assertIn("unidentified player", out)

    def test_a_second_run_while_one_is_in_progress_is_refused(self):
        with cli.exclusive(self.sandbox.settings.lock_path):
            code, out = self.cli("run", "--no-fetch", "--no-scan")
        self.assertEqual(code, cli.EXIT_BUSY)
        self.assertIn("in progress", out)

    def test_restore_backup_puts_back_the_last_snapshot(self):
        self.seed_show()
        self.cli("run", "--no-fetch", "--no-scan", "--no-copy")  # takes the snapshot
        store = Store(self.sandbox.settings.db_path)
        library.register_video(store, "Glee", None, "Season 1/g.S01E01.mkv", "1glee000000000000000000", 0)
        store.close()
        code, out = self.cli("restore-backup")
        self.assertEqual(code, 0, out)
        store = Store(self.sandbox.settings.db_path)
        self.assertEqual(store.shows(), ["Scrubs"])
        store.close()

    def test_until_picks_the_next_occurrence_of_that_time(self):
        args = cli.build_parser().parse_args(["run", "--until", "00:00"])
        deadline = cli.deadline_from(args, self.clock())
        self.assertTrue(0 < deadline - self.clock() <= 24 * 3600)
        with self.assertRaises(SystemExit), contextlib.redirect_stderr(io.StringIO()):
            cli.build_parser().parse_args(["run", "--until", "25:00"])

    def test_a_damaged_config_is_reported_not_guessed_around(self):
        self.sandbox.settings.state_dir.mkdir(parents=True, exist_ok=True)
        self.sandbox.settings.config_path.write_text('{"version": 1, "players": {"x": {}}}')
        code, out = self.cli("status")
        self.assertEqual(code, 1)
        self.assertIn("marker id", out)


if __name__ == "__main__":
    unittest.main()
