"""Player identity and the copy that must never leave a half-written episode in VIDEO."""
from __future__ import annotations

import hashlib
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from ruizu_episodes import player


class IdentityTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def test_marker_matches_the_ruizu_sync_app_format(self):
        identity = player.write_identity(self.root, "Kid's player", now_ms=1234)
        data = json.loads((self.root / ".ruizu-sync-device.json").read_text())
        self.assertEqual(set(data), {"version", "id", "name", "firstSeenMs"})
        self.assertEqual((data["version"], data["name"], data["firstSeenMs"]), (1, "Kid's player", 1234))
        self.assertEqual(player.read_identity(self.root), identity)

    def test_reads_a_marker_written_by_the_app(self):
        (self.root / player.MARKER).write_text('{"version":1,"id":"abc-123","name":"Josh","firstSeenMs":5}')
        self.assertEqual(player.read_identity(self.root), player.Identity("abc-123", "Josh", 5))

    def test_never_replaces_an_existing_identity(self):
        first = player.write_identity(self.root, "One")
        with self.assertRaises(player.CopyError):
            player.write_identity(self.root, "Two")
        self.assertEqual(player.read_identity(self.root), first)

    def test_foreign_or_oversized_markers_are_not_trusted(self):
        marker = self.root / player.MARKER
        for body in ("not json", '{"version":2,"id":"abc"}', '{"id":"../../etc"}', "{" + " " * 2000 + "}"):
            marker.write_text(body)
            with self.subTest(body=body[:20]):
                self.assertIsNone(player.read_identity(self.root))


class CopyTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        base = Path(self.tmp.name)
        self.video = base / "VIDEO"
        self.video.mkdir()
        self.source = base / "ep.amv"
        self.source.write_bytes(os.urandom(3 * 1024 * 1024 + 17))
        self.sha = hashlib.sha256(self.source.read_bytes()).hexdigest()

    def tearDown(self):
        self.tmp.cleanup()

    def test_copy_lands_under_its_final_name_with_identical_bytes(self):
        player.copy_verified(self.source, self.video, "Ep - RUIZU.amv", self.sha)
        self.assertEqual(os.listdir(self.video), ["Ep - RUIZU.amv"])
        self.assertEqual((self.video / "Ep - RUIZU.amv").read_bytes(), self.source.read_bytes())

    def test_a_mismatch_leaves_nothing_behind(self):
        with self.assertRaises(player.CopyError):
            player.copy_verified(self.source, self.video, "Ep - RUIZU.amv", "0" * 64)
        self.assertEqual(os.listdir(self.video), [])

    def test_an_interruption_mid_write_leaves_nothing_behind(self):
        def pulled_cable(*_args, **_kwargs):
            raise OSError(5, "Input/output error")

        with patch.object(player.shutil, "copyfileobj", pulled_cable), self.assertRaises(OSError):
            player.copy_verified(self.source, self.video, "Ep - RUIZU.amv", self.sha)
        self.assertEqual(os.listdir(self.video), [])

    def test_never_overwrites_an_existing_file(self):
        (self.video / "Ep - RUIZU.amv").write_bytes(b"someone else's file")
        with self.assertRaises(player.CopyError):
            player.copy_verified(self.source, self.video, "Ep - RUIZU.amv", self.sha)
        self.assertEqual((self.video / "Ep - RUIZU.amv").read_bytes(), b"someone else's file")

    def test_own_prefix_proves_a_truncated_copy_is_ours(self):
        truncated = self.video / "t"
        truncated.write_bytes(self.source.read_bytes()[: 2 * 1024 * 1024 + 5])
        self.assertTrue(player.is_own_prefix(self.source, truncated))
        truncated.write_bytes(b"x" + self.source.read_bytes()[1:100])
        self.assertFalse(player.is_own_prefix(self.source, truncated))


if __name__ == "__main__":
    unittest.main()
