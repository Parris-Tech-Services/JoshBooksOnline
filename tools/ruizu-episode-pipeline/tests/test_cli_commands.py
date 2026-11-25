"""Every command a person can type, plus the Termux touch points, against real files."""
from __future__ import annotations

import contextlib
import io
import json
import tempfile
import unittest
from pathlib import Path

from ruizu_episodes import cli, player, termux
from ruizu_episodes import config as cfg
from ruizu_episodes.process import Result, Runner
from ruizu_episodes.settings import Settings
from ruizu_episodes.store import Store

from .support import HAVE_TOOLS, FakeClock, FakeDrive, OfflineTermux, Sandbox, needs_tools, synthetic_episode


class RecordingTermux(OfflineTermux):
    """Termux:API commands are 'installed' and every call is recorded."""

    def available(self, program: str) -> bool:
        return True if program.startswith("termux-") else super().available(program)

    def run(self, cmd, timeout):
        if cmd[0].startswith("termux-"):
            self.calls.append(list(cmd))
            return Result(0, self.wifi or "scheduled", "")
        return super().run(cmd, timeout)


class CommandsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.drive = FakeDrive().__enter__()
        self.sandbox = Sandbox(Path(self.tmp.name), self.drive)
        self.clock = FakeClock()
        self.runner = RecordingTermux(json.dumps({"supplicant_state": "COMPLETED", "ip": "10.0.0.2"}))
        self.root = self.drive.folder("TV")
        season = self.drive.folder("Season 1", self.drive.folder("Scrubs", self.root))
        if HAVE_TOOLS:  # synthetic episodes need ffmpeg; command tests without it still run
            self.drive.file(season, "Scrubs.S01E01.mkv", synthetic_episode(12))
        self.drive.folder("Glee", self.root)

    def tearDown(self):
        self.drive.__exit__()
        self.tmp.cleanup()

    def cli(self, *argv: str) -> tuple[int, str]:
        out = io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(out):
            code = cli.main(list(argv), self.sandbox.settings, self.runner, self.clock)
        return code, out.getvalue()

    def setup_player(self) -> str:
        self.assertEqual(self.cli("add-root", self.root)[0], 0)
        self.assertEqual(self.cli("scan")[0], 0)
        self.assertEqual(self.cli("claim", "Kid")[0], 0)
        return player.read_identity(self.sandbox.player).id

    def test_scan_and_shows_report_the_library(self):
        self.cli("add-root", self.root)
        code, out = self.cli("scan", "--refresh", "--max-listings", "10")
        self.assertEqual(code, 0, out)
        self.assertIn("+ Scrubs S01E01", out)
        code, out = self.cli("shows")
        self.assertIn("Glee: 0 known", out)
        self.assertIn("Scrubs: 1 known, 0 prepared", out)

    def test_add_root_rejects_something_that_is_not_a_folder_id(self):
        code, out = self.cli("add-root", "../../etc")
        self.assertEqual(code, 1)
        self.assertIn("folder id", out)

    def test_allow_deny_and_per_show_shape_the_policy(self):
        pid = self.setup_player()
        self.cli("allow", "Kid", "Scrubs", "Glee")
        code, out = self.cli("deny", "Kid", "glee")
        self.assertIn("Kid may receive: Scrubs", out)
        self.cli("per-show", "Kid", "3")
        policy = cfg.load(self.sandbox.settings.config_path).players[pid]
        self.assertEqual((policy.shows, policy.per_show), (("Scrubs",), 3))
        self.cli("allow", "Kid", "--all")
        self.assertEqual(cfg.load(self.sandbox.settings.config_path).players[pid].shows, cfg.ALL_SHOWS)
        code, out = self.cli("deny", "Kid", "Scrubs")
        self.assertEqual(code, 1)
        self.assertIn("allows every show", out)

    @needs_tools
    def test_run_then_status_then_log(self):
        self.setup_player()
        self.cli("allow", "Kid", "Scrubs")
        code, out = self.cli("run", "--minutes", "30", "--notify")
        self.assertEqual(code, 0, out)
        self.assertIn("copied Scrubs S01E01", out)
        self.assertTrue(any(c[0] == "termux-notification" for c in self.runner.calls))
        code, out = self.cli("status")
        self.assertIn("Kid (", out)
        self.assertIn("Connected now: Kid", out)
        code, out = self.cli("log", "-n", "5")
        self.assertIn("run finished", out)

    @needs_tools
    def test_retry_brings_back_a_failed_episode(self):
        self.setup_player()
        self.cli("allow", "Kid", "Scrubs")
        fid = next(iter(self.drive.files))
        self.drive.files[fid] = b"\x00" * 2_000_000  # not a video: fails for good
        code, _ = self.cli("run", "--quiet")
        self.assertEqual(code, 1)
        code, out = self.cli("status")
        self.assertIn("failed: Scrubs S01E01", out)
        code, out = self.cli("retry", "Scrubs S01E01")
        self.assertIn("1 episode(s) will be tried again", out)

    def test_schedule_and_unschedule_use_the_termux_job_scheduler(self):
        code, out = self.cli("schedule", "--every", "30")
        self.assertEqual(code, 0, out)
        job = next(c for c in self.runner.calls if c[0] == "termux-job-scheduler")
        self.assertIn("unmetered", job)
        self.assertIn(str(30 * 60_000), job)
        script = Path(job[job.index("--script") + 1])
        self.assertIn("run --scheduled", script.read_text())
        self.cli("unschedule")
        self.assertIn("--cancel", self.runner.calls[-1])

    def test_scheduled_runs_are_short_and_trust_the_scheduler_for_wifi(self):
        args = cli.build_parser().parse_args(["run", "--scheduled"])
        options = cli.run_options(args, 1000.0)
        self.assertEqual((options.deadline, options.allow_metered), (1000.0 + cli.SCHEDULED_MINUTES * 60, True))


class TermuxAndSettingsTest(unittest.TestCase):
    def test_wifi_answers_are_never_guessed(self):
        self.assertIsNone(termux.wifi_connected(OfflineTermux()))
        self.assertIsNone(termux.wifi_connected(RecordingTermux("not json")))
        self.assertFalse(termux.wifi_connected(RecordingTermux('{"supplicant_state": "DISCONNECTED"}')))
        self.assertTrue(termux.wifi_connected(RecordingTermux('{"supplicant_state": "COMPLETED", "ip": "1.2.3.4"}')))

    def test_missing_scheduler_is_reported(self):
        self.assertIn("not installed", termux.schedule(OfflineTermux(), Path("x"), 15))
        self.assertIn("not installed", termux.unschedule(OfflineTermux()))

    def test_settings_default_to_the_existing_phone_folders(self):
        settings = Settings.from_env({"HOME": "/home/u", "RUIZU_PLAYER_ROOTS": "/a:/b"})
        self.assertEqual(settings.stage_dir, Path("/home/u/ruizu-episodes"))
        self.assertEqual(settings.db_path, Path("/home/u/.ruizu-player/pipeline.db"))
        self.assertEqual(settings.player_roots, (Path("/a"), Path("/b")))

    def test_runner_reports_missing_programs_and_timeouts(self):
        runner = Runner()
        self.assertEqual(runner.run(["definitely-not-a-program-xyz"], 5).returncode, 127)
        self.assertEqual(runner.run(["sleep", "5"], 0.2).returncode, 124)


class StoreCliEdgeTest(unittest.TestCase):
    def test_restore_refuses_a_missing_backup(self):
        with tempfile.TemporaryDirectory() as tmp:
            sandbox = Sandbox(Path(tmp))
            Store(sandbox.settings.db_path).close()
            out = io.StringIO()
            with contextlib.redirect_stdout(out), contextlib.redirect_stderr(out):
                code = cli.main(["restore-backup"], sandbox.settings, OfflineTermux(), FakeClock())
            self.assertEqual(code, 1)
            self.assertIn("no backup", out.getvalue())


if __name__ == "__main__":
    unittest.main()
