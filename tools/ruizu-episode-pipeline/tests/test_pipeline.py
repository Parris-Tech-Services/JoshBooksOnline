"""End-to-end behaviour against a fake Drive over real HTTP, real curl, real ffmpeg and a fake player."""
from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from ruizu_episodes import player
from ruizu_episodes.config import PlayerPolicy
from ruizu_episodes.media import Media, sha256_file
from ruizu_episodes.names import (PLAYER_SUFFIX, legacy_player_filename, player_filename,
                                  player_relative_path)
from ruizu_episodes.pipeline import Pipeline, RunOptions
from ruizu_episodes.process import Runner
from ruizu_episodes.store import Store

from .support import FakeClock, FakeDrive, OfflineTermux, Sandbox, needs_tools, synthetic_episode

SHOWS = ("Glee", "Scrubs", "Girls")


@needs_tools
class PipelineTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.drive = FakeDrive().__enter__()
        self.sandbox = Sandbox(Path(self.tmp.name), self.drive)
        self.clock = FakeClock()
        self.root = self.drive.folder("TV")
        self.ids: dict[str, str] = {}
        for index, show in enumerate(SHOWS):
            season = self.drive.folder("Season 1", self.drive.folder(show, self.root))
            for number in (1, 2):
                # Glee is the smallest show, so it should be fetched first in each round.
                data = synthetic_episode(12 + index)
                self.ids[f"{show} S01E0{number}"] = self.drive.file(season, f"{show}.S01E0{number}.720p.mkv", data)
        self.kid = player.write_identity(self.sandbox.player, "Kid's player")
        self.stores: list[Store] = []

    def tearDown(self):
        for store in self.stores:
            store.close()
        self.drive.__exit__()
        self.tmp.cleanup()

    def pipeline(self, shows=("Glee", "Scrubs"), per_show=1, runner=None, **limits) -> Pipeline:
        policy = PlayerPolicy("Kid's player", shows, per_show, min_free_mb=0)
        config = self.sandbox.write_config({self.kid.id: policy}, roots=(self.root,), **limits)
        store = Store(self.sandbox.settings.db_path)
        self.stores.append(store)
        return Pipeline(self.sandbox.settings, config, store, runner or OfflineTermux(), self.clock)

    def run_once(self, options: RunOptions | None = None, **kwargs):
        return self.pipeline(**kwargs).run(options or RunOptions())

    def downloads_of(self, label: str) -> int:
        return self.drive.count("download", self.ids[label])

    # ---- the main promise ---------------------------------------------
    def test_first_run_discovers_prepares_and_copies_one_of_each_allowed_show(self):
        report = self.run_once()
        self.assertEqual(report.copied, ["Glee S01E01", "Scrubs S01E01"])
        self.assertEqual(self.sandbox.video_files(),
                         [player_relative_path("Glee S01E01"), player_relative_path("Scrubs S01E01")])
        for label in ("Glee S01E01", "Scrubs S01E01"):
            on_player = self.sandbox.player / "VIDEO" / player_relative_path(label)
            staged = self.sandbox.settings.stage_dir / f"{label}.amv"
            self.assertEqual(sha256_file(on_player), sha256_file(staged))
            Media(Runner()).verify_amv(on_player)
        self.assertEqual(self.downloads_of("Girls S01E01"), 0, "a show the player is not allowed is never fetched")
        self.assertEqual(list(self.sandbox.settings.work_dir.iterdir()), [], "sources are transient")

    def test_next_run_continues_without_rescanning_or_redownloading(self):
        self.run_once()
        listings = self.drive.count("list")
        self.clock.advance(3600)
        report = self.run_once()
        self.assertEqual(self.drive.count("list"), listings, "folders inside the TTL are not listed again")
        self.assertEqual(self.downloads_of("Glee S01E01"), 1)
        self.assertEqual(report.copied, ["Glee S01E02", "Scrubs S01E02"])

    def test_nothing_new_means_nothing_happens(self):
        self.run_once(per_show=2)
        self.clock.advance(60)
        report = self.run_once(per_show=2)
        self.assertEqual((report.copied, report.staged, report.failed), ([], [], []))

    def test_discovery_is_bounded_and_resumes_where_it_stopped(self):
        self.run_once(RunOptions(fetch=False, copy=False), max_listings_per_run=3)
        self.assertEqual(self.drive.count("list"), 3)
        self.run_once(RunOptions(fetch=False, copy=False), max_listings_per_run=3)
        self.run_once(RunOptions(fetch=False, copy=False), max_listings_per_run=3)
        listed = [fid for kind, fid in self.drive.requests if kind == "list"]
        self.assertEqual(len(listed), 7, "root + 3 shows + 3 seasons")
        self.assertEqual(len(set(listed)), 7, "no folder listed twice")

    # ---- the player ---------------------------------------------------
    def test_a_player_without_an_identity_is_never_written(self):
        (self.sandbox.player / player.MARKER).unlink()
        report = self.run_once()
        self.assertEqual(self.sandbox.video_files(), [])
        self.assertTrue(any("no identity marker" in line for line in report.lines))

    def test_a_player_that_is_not_set_up_is_never_written(self):
        (self.sandbox.player / player.MARKER).unlink()
        player.write_identity(self.sandbox.player, "Other player")
        self.run_once()
        self.assertEqual(self.sandbox.video_files(), [])

    def test_an_episode_removed_by_hand_is_not_pushed_back(self):
        self.run_once()
        (self.sandbox.player / "VIDEO" / player_relative_path("Glee S01E01")).unlink()
        self.clock.advance(60)
        report = self.run_once()
        self.assertNotIn("Glee S01E01", report.copied)
        self.assertIn("Glee S01E02", report.copied)

    def test_copied_episodes_go_into_a_show_folder_named_code_first(self):
        self.run_once()
        self.assertEqual(self.sandbox.video_files(),
                         [player_relative_path("Glee S01E01"), player_relative_path("Scrubs S01E01")])
        self.assertEqual(player_relative_path("Glee S01E01"), "Glee/S01E01 - Glee - RUIZU.amv")

    def test_an_episode_left_under_the_earlier_spelling_still_counts_as_had(self):
        self.run_once()
        video = self.sandbox.player / "VIDEO"
        # Put the same episode back the way an earlier version of this tool would have left it:
        # flat, with the show first. It is still the same episode, not a missing one.
        next(f for f in (video / "Glee").iterdir() if f.name.startswith("S01E01 - Glee")).rename(
            video / legacy_player_filename("Glee S01E01"))
        self.clock.advance(60)
        report = self.run_once()
        self.assertNotIn("Glee S01E01", report.copied)

    def test_an_interrupted_copy_is_cleaned_up_and_redone(self):
        self.run_once(RunOptions(copy=False))  # prepare only
        p = self.pipeline()
        ep = next(e for e in p.store.episodes() if e.label == "Glee S01E01")
        partial = (self.sandbox.player / "VIDEO" / player_relative_path("Glee S01E01")).with_name(
            player_filename("Glee S01E01") + player.PARTIAL_SUFFIX)
        partial.parent.mkdir(parents=True, exist_ok=True)
        partial.write_bytes(p.staged_path(ep).read_bytes()[:1000])  # what a killed run leaves
        p.store.set_copy(self.kid.id, ep.drive_id, "copying", 0)
        report = p.run(RunOptions(scan=False, fetch=False))
        self.assertIn("Glee S01E01", report.copied)
        self.assertFalse(any(n.endswith(player.PARTIAL_SUFFIX) for n in self.sandbox.video_files()))

    def test_a_truncated_episode_from_an_interrupted_copy_is_replaced(self):
        self.run_once(RunOptions(copy=False))
        p = self.pipeline()
        ep = next(e for e in p.store.episodes() if e.label == "Scrubs S01E01")
        final = self.sandbox.player / "VIDEO" / player_relative_path("Scrubs S01E01")
        final.parent.mkdir(parents=True, exist_ok=True)
        final.write_bytes(p.staged_path(ep).read_bytes()[:5000])
        p.store.set_copy(self.kid.id, ep.drive_id, "copying", 0)
        p.run(RunOptions(scan=False, fetch=False))
        self.assertEqual(sha256_file(final), sha256_file(p.staged_path(ep)))

    def test_a_cable_pull_mid_copy_is_cleaned_up_on_the_next_connection(self):
        self.run_once(RunOptions(copy=False))
        p = self.pipeline()
        video = self.sandbox.player / "VIDEO"
        real_write = player._write_synced

        def write_then_unplug(source, partial):
            partial.write_bytes(source.read_bytes()[:4096])
            video.rename(video.with_name("VIDEO-unplugged"))  # the card vanishes mid-write
            raise OSError(5, "Input/output error")

        player._write_synced = write_then_unplug
        try:
            report = p.run(RunOptions(scan=False, fetch=False))
        finally:
            player._write_synced = real_write
        self.assertTrue(report.failed)
        video.with_name("VIDEO-unplugged").rename(video)  # plugged back in
        self.assertTrue(any(n.endswith(player.PARTIAL_SUFFIX) for n in self.sandbox.video_files()))
        report = self.pipeline().run(RunOptions(scan=False, fetch=False))
        self.assertEqual(sorted(report.copied), ["Glee S01E01", "Scrubs S01E01"])
        self.assertFalse(any(n.endswith(player.PARTIAL_SUFFIX) for n in self.sandbox.video_files()))

    def test_a_truncated_file_left_by_the_old_scripts_is_repaired(self):
        self.run_once(RunOptions(copy=False))
        p = self.pipeline()
        ep = next(e for e in p.store.episodes() if e.label == "Glee S01E01")
        # Written before this tool, so it carries the earlier show-first spelling.
        old_copy = self.sandbox.player / "VIDEO" / ("Glee S01E01" + PLAYER_SUFFIX)
        old_copy.write_bytes(p.staged_path(ep).read_bytes()[:7000])
        report = p.run(RunOptions(scan=False, fetch=False))
        self.assertIn("Glee S01E01", report.copied)
        repaired = self.sandbox.player / "VIDEO" / player_relative_path("Glee S01E01")
        self.assertEqual(sha256_file(repaired), sha256_file(p.staged_path(ep)))
        self.assertFalse(old_copy.exists(), "the truncated earlier-spelling copy is not left behind")

    def test_someone_elses_file_with_the_same_name_is_left_alone(self):
        final = self.sandbox.player / "VIDEO" / player_relative_path("Glee S01E01")
        final.parent.mkdir(parents=True, exist_ok=True)
        final.write_bytes(b"a file put there by hand")
        report = self.run_once()
        self.assertEqual(final.read_bytes(), b"a file put there by hand")
        self.assertNotIn("Glee S01E01", report.copied)

    def test_no_copy_starts_that_would_run_past_the_deadline(self):
        self.run_once(RunOptions(copy=False))
        p = self.pipeline()
        report = p.run(RunOptions(scan=False, fetch=False, deadline=self.clock() + 1))
        self.assertEqual(report.copied, [])
        self.assertEqual(self.sandbox.video_files(), [])

    # ---- Drive misbehaving --------------------------------------------
    def test_a_throttle_page_never_becomes_part_of_an_episode_and_backs_off(self):
        self.drive.throttle_downloads[self.ids["Glee S01E01"]] = 1
        report = self.run_once()
        self.assertIn("Glee S01E01", report.failed)
        p = self.pipeline()
        ep = next(e for e in p.store.episodes() if e.label == "Glee S01E01")
        self.assertEqual((ep.state, ep.attempts), ("discovered", 1))
        self.assertGreater(ep.retry_at, self.clock())
        part = self.sandbox.settings.work_dir / f"{ep.drive_id}.src.part"
        self.assertFalse(part.exists() and b"<html" in part.read_bytes())
        self.clock.advance(3600)
        self.assertIn("Glee S01E01", p.run(RunOptions()).copied)

    def test_a_dropped_connection_resumes_instead_of_starting_again(self):
        size = len(self.drive.files[self.ids["Glee S01E01"]])
        self.drive.cut_downloads[self.ids["Glee S01E01"]] = size // 2
        first = self.run_once()
        self.assertIn("Glee S01E01", first.failed)
        self.clock.advance(3600)
        second = self.run_once()
        self.assertIn("Glee S01E01", second.copied)
        self.assertLess(self.drive.bytes_served[self.ids["Glee S01E01"]], size * 1.2)

    def test_a_file_that_is_not_video_fails_on_its_own(self):
        self.drive.files[self.ids["Glee S01E01"]] = b"\x00" * 2_000_000
        report = self.run_once()
        self.assertIn("Scrubs S01E01", report.copied)
        p = self.pipeline()
        ep = next(e for e in p.store.episodes() if e.label == "Glee S01E01")
        self.assertEqual(ep.state, "failed")
        self.assertEqual(list(self.sandbox.settings.work_dir.iterdir()), [])

    def test_a_listing_that_is_not_a_listing_backs_off_and_deletes_nothing(self):
        self.run_once(RunOptions(fetch=False, copy=False))
        known = len(self.pipeline().store.episodes())
        self.drive.throttle_listings = 100
        self.clock.advance(200 * 3600)  # everything is stale
        self.run_once(RunOptions(fetch=False, copy=False))
        store = self.pipeline().store
        self.assertEqual(len(store.episodes("state != 'gone'")), known)

    # ---- limits -------------------------------------------------------
    def test_download_allowance_is_respected(self):
        report = self.run_once(max_download_mb_per_run=0)
        self.assertEqual((report.staged, self.drive.count("download")), ([], 0))
        self.assertTrue(any("allowance" in line for line in report.lines))

    def test_downloads_wait_for_wifi_when_it_cannot_be_confirmed(self):
        report = self.run_once(require_unmetered=True)
        self.assertEqual(report.staged, [])
        self.assertTrue(any("could not confirm Wi-Fi" in line for line in report.lines))
        wifi = json.dumps({"supplicant_state": "COMPLETED", "ip": "192.168.1.5"})
        self.assertEqual(len(self.run_once(require_unmetered=True, runner=OfflineTermux(wifi)).staged), 2)


if __name__ == "__main__":
    unittest.main()
