"""Migration export (H2 on #47), the guard against writing while the app copies, and marker parity."""
from __future__ import annotations

import contextlib
import io
import json
import os
import tempfile
import time
import unittest
import uuid
from pathlib import Path

from ruizu_episodes import cli, player
from ruizu_episodes.pipeline import RunOptions

from .support import needs_tools
from .test_pipeline import PipelineFixture


@needs_tools
class ExportTest(PipelineFixture):
    """Reuses the end-to-end fixture: a fake Drive with three shows and a claimed player."""

    def test_export_is_label_keyed_complete_and_leaks_no_ids_or_paths(self):
        self.run_once(per_show=2)
        (self.sandbox.player / "VIDEO" / "Glee S01E01 - RUIZU.amv").unlink()  # removed by hand
        self.clock.advance(60)
        self.run_once(RunOptions(scan=False, fetch=False), per_show=2)
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            code = cli.main(["export"], self.sandbox.settings, None, self.clock)
        self.assertEqual(code, 0)
        text = out.getvalue()
        data = json.loads(text)
        self.assertEqual((data["format"], data["version"], data["schema_version"]), ("ruizu-episodes-export", 1, 1))
        kid = data["players"][0]
        self.assertEqual((kid["marker_id"], kid["shows"], kid["per_show"]), (self.kid.id, ["Glee", "Scrubs"], 2))
        states = {c["label"]: c["state"] for c in kid["copies"]}
        self.assertEqual(states["Glee S01E01"], "removed")
        self.assertEqual(states["Scrubs S01E02"], "verified")
        staged = {e["label"]: e for e in data["episodes"] if e["state"] == "staged"}
        self.assertEqual(len(staged["Glee S01E02"]["amv_sha256"]), 64)
        self.assertEqual(staged["Glee S01E02"]["player_file"], "Glee S01E02 - RUIZU.amv")
        for drive_file_id in self.ids.values():
            self.assertNotIn(drive_file_id, text, "Drive ids never leave the phone in an export")
        self.assertNotIn(self.root, text)
        self.assertNotIn(str(self.sandbox.base), text, "no filesystem paths")

    def test_export_writes_a_file_atomically_and_changes_nothing(self):
        self.run_once()
        db = self.sandbox.settings.db_path
        before = (db.stat().st_mtime_ns, db.read_bytes())
        target = self.sandbox.base / "export.json"
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(cli.main(["export", "--output", str(target)], self.sandbox.settings, None, self.clock), 0)
        self.assertEqual(json.loads(target.read_text())["format"], "ruizu-episodes-export")
        self.assertFalse(target.with_name("export.json.tmp").exists())
        self.assertEqual(before, (db.stat().st_mtime_ns, db.read_bytes()), "export is read-only")


@needs_tools
class AppWriterGuardTest(PipelineFixture):
    def app_temp(self, age_seconds: float) -> Path:
        temp = self.sandbox.player / "VIDEO" / f".ruizu-{uuid.uuid4()}.part"
        temp.write_bytes(b"the app is writing this")
        stamp = time.time() - age_seconds
        os.utime(temp, (stamp, stamp))
        return temp

    def test_no_episode_is_copied_while_the_app_is_copying(self):
        self.clock.now = time.time()
        temp = self.app_temp(age_seconds=5)
        report = self.run_once()
        self.assertEqual(report.copied, [])
        self.assertTrue(any("app is copying" in line for line in report.lines))
        self.assertEqual(self.sandbox.video_files(), [temp.name], "nothing written, the app's file untouched")
        self.assertEqual(len(report.staged), 2, "preparation carries on; only the copy waits")

    def test_an_old_app_leftover_does_not_block_and_is_left_alone(self):
        self.clock.now = time.time()
        temp = self.app_temp(age_seconds=3600)
        report = self.run_once()
        self.assertEqual(sorted(report.copied), ["Glee S01E01", "Scrubs S01E01"])
        self.assertTrue(temp.exists(), "only the app may remove its own temporary file")


class MarkerParityTest(unittest.TestCase):
    """The CLI accepts exactly the markers the app's PlayerIdentity.parse accepts."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def read(self, body: str):
        (self.root / player.MARKER).write_text(body)
        return player.read_identity(self.root)

    def test_refuses_what_the_app_refuses(self):
        many = ",".join(f'"k{i}":{i}' for i in range(16))
        for body in ('{"id":"abc","id":"def"}', '{"id":"abc","extra":{"x":1}}', '{"id":"abc","list":[1]}',
                     '{"id":"abc",' + many + '}', '{"version":"2","id":"abc"}', '[{"id":"abc"}]'):
            with self.subTest(body=body[:40]):
                self.assertIsNone(self.read(body))

    def test_accepts_and_cleans_what_the_app_accepts(self):
        identity = self.read('{"version":"1","id":"abc-123","name":"  Kid\\u0007s \\n  player  ","firstSeenMs":"x"}')
        self.assertEqual(identity, player.Identity("abc-123", "Kids player", 0))
        self.assertEqual(self.read('{"id":"abc","name":"' + "n" * 60 + '"}').name, "n" * 48)
        self.assertEqual(self.read('{"id":"abc"}').name, "Unnamed player")
        self.assertEqual(self.read('{"id":"abc","firstSeenMs":-5}').first_seen_ms, 0)


if __name__ == "__main__":
    unittest.main()
