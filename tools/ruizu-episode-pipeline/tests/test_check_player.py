"""check-player: finding truncated episodes on a player with no reference copy left on the phone."""
from __future__ import annotations

import contextlib
import io
import subprocess
import tempfile
import unittest
from pathlib import Path

from ruizu_episodes import cli
from ruizu_episodes.media import Media, header_seconds
from ruizu_episodes.pipeline import RunOptions
from ruizu_episodes.process import Runner

from .support import needs_tools
from .test_pipeline import PipelineFixture


def truncate(path: Path, fraction: float) -> None:
    data = path.read_bytes()
    path.write_bytes(data[: int(len(data) * fraction)])  # header kept, frames lost: a pulled cable


@needs_tools
class HeaderTest(unittest.TestCase):
    def test_reads_the_length_ffmpeg_records_in_the_amv_header(self):
        with tempfile.TemporaryDirectory() as tmp:
            amv = Path(tmp) / "long.amv"
            subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i",
                            "testsrc2=size=160x128:rate=15", "-f", "lavfi", "-i", "sine=sample_rate=22050",
                            "-t", "75", "-c:v", "amv", "-c:a", "adpcm_ima_amv", "-block_size", "1470",
                            "-ar", "22050", "-ac", "1", "-r", "15", "-f", "amv", str(amv)], check=True)
            self.assertEqual(header_seconds(amv), 75)
            truncate(amv, 0.5)
            self.assertEqual(header_seconds(amv), 75, "a cut file keeps its header")
            check = Media(Runner()).inspect_amv(amv)
            self.assertEqual(check.status, "truncated")
            other = Path(tmp) / "not.amv"
            other.write_bytes(b"\x00" * 100)
            self.assertIsNone(header_seconds(other))


@needs_tools
class CheckPlayerTest(PipelineFixture):
    def cli(self, *argv: str) -> tuple[int, str]:
        out = io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(out):
            code = cli.main(list(argv), self.sandbox.settings, None, self.clock)
        return code, out.getvalue()

    def staged(self, label: str) -> Path:
        return self.sandbox.settings.stage_dir / f"{label}.amv"

    def on_player(self, label: str) -> Path:
        return self.sandbox.player / "VIDEO" / f"{label} - RUIZU.amv"

    def test_finds_and_repairs_a_truncated_episode_with_no_staged_copy_left(self):
        self.run_once()
        self.staged("Glee S01E01").unlink()  # the phone's copy was cleared to save space
        truncate(self.on_player("Glee S01E01"), 0.6)
        before = self.on_player("Glee S01E01").read_bytes()

        code, out = self.cli("check-player")
        self.assertEqual(code, 2, out)
        self.assertIn("TRUNCATED     Glee S01E01 - RUIZU.amv", out)
        self.assertIn("OK            Scrubs S01E01 - RUIZU.amv", out)
        self.assertEqual(self.on_player("Glee S01E01").read_bytes(), before, "the report changes nothing")

        code, out = self.cli("check-player", "--repair")
        self.assertEqual(code, 0, out)
        self.assertIn("removed; it will be prepared and copied again", out)
        self.assertFalse(self.on_player("Glee S01E01").exists())
        self.assertTrue(self.on_player("Scrubs S01E01").exists(), "complete episodes are never touched")

        self.clock.advance(60)
        report = self.run_once(RunOptions(scan=False))
        self.assertIn("Glee S01E01", report.copied, "replaced, not treated as removed by hand")
        self.assertEqual(Media(Runner()).inspect_amv(self.on_player("Glee S01E01")).status, "ok")

    def test_a_truncated_file_that_is_not_ours_is_reported_and_left_alone(self):
        self.run_once()
        stranger = self.sandbox.player / "VIDEO" / "Home video.amv"
        stranger.write_bytes(self.on_player("Glee S01E01").read_bytes())
        truncate(stranger, 0.5)
        code, out = self.cli("check-player", "--repair")
        self.assertEqual(code, 2)
        self.assertIn("not one of this tool's episodes; left alone", out)
        self.assertTrue(stranger.exists())

    def test_repair_needs_a_player_that_is_set_up_here(self):
        self.pipeline()  # writes config for the claimed player
        self.sandbox.settings.config_path.unlink()
        code, out = self.cli("check-player", "--repair")
        self.assertEqual(code, 1)
        self.assertIn("claimed and set up here", out)


if __name__ == "__main__":
    unittest.main()
