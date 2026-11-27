"""Unit behaviour: names, listing parser, config validation, planning, store schema and recovery."""
from __future__ import annotations

import sqlite3
import tempfile
import unittest
from pathlib import Path

from ruizu_episodes import config as cfg
from ruizu_episodes import names, planner
from ruizu_episodes.config import Config, ConfigError, PlayerPolicy
from ruizu_episodes.drive import parse_listing
from ruizu_episodes.store import SCHEMA_VERSION, Store, StoreError, new_episode

from .support import FakeDrive


class NamesTest(unittest.TestCase):
    def test_numbered_episodes_get_the_labels_the_old_scripts_used(self):
        ident = names.identify("How I Met Your Mother", "Season 2/How.I.Met.Your.Mother.S02E11.720p.mkv")
        self.assertEqual((ident.kind, ident.season, ident.episode), ("episode", 2, 11))
        self.assertEqual(ident.label, "How I Met Your Mother S02E11")
        self.assertEqual(names.identify("Angel", "Season 3/Angel 3x07 Fredless.avi").label, "Angel S03E07")

    def test_extras_take_their_season_from_the_folder_and_drop_release_noise(self):
        ident = names.identify("Buffy", "Season 6/Buffy.6xEX.Fan_Meeting.x264.mkv")
        self.assertEqual((ident.kind, ident.label), ("extra", "Buffy S06 - Fan Meeting"))

    def test_resolution_is_not_mistaken_for_an_episode_number(self):
        self.assertIsNone(names.episode_numbers("Pilot 1280x720 x264.mkv"))

    def test_untrusted_names_cannot_escape_the_folder_or_break_fat(self):
        ident = names.identify('Show/../..', 'Season 1/Bad<>:"|?*\\name.mkv')
        self.assertNotIn("/", ident.label)
        self.assertFalse(any(c in ident.label for c in '<>:"|?*\\'))

    def test_player_filenames_put_the_episode_code_first(self):
        self.assertEqual("S01E07 - The Originals - RUIZU.amv",
                         names.player_filename("The Originals S01E07"))
        self.assertEqual("S02E11 - How I Met Your Mother - RUIZU.amv",
                         names.player_filename("How I Met Your Mother S02E11"))

    def test_a_label_without_an_episode_code_keeps_the_show_first_spelling(self):
        # Extras such as "Buffy S05 - Buffy Abroad" have no code to move to the front.
        self.assertEqual("Buffy S05 - Buffy Abroad" + names.PLAYER_SUFFIX,
                         names.player_filename("Buffy S05 - Buffy Abroad"))

    def test_the_earlier_spelling_is_still_available_for_reading_a_player(self):
        self.assertEqual("The Originals S01E07" + names.PLAYER_SUFFIX,
                         names.legacy_player_filename("The Originals S01E07"))
        self.assertNotEqual(names.player_filename("The Originals S01E07"),
                            names.legacy_player_filename("The Originals S01E07"))

    def test_legacy_labels_are_understood(self):
        show, ident = names.identify_label("Buffy S05 - Buffy Abroad")
        self.assertEqual((show, ident.kind, ident.season), ("Buffy", "extra", 5))
        show, ident = names.identify_label("The Originals S01E01")
        self.assertEqual((show, ident.episode), ("The Originals", 1))


class ListingParserTest(unittest.TestCase):
    def test_parses_folders_and_files_from_the_embedded_view(self):
        fake = FakeDrive()
        season = fake.folder("Show")
        fake.folders[season] += [("Season 1", "F" * 25, True), ("Ep & S01E01.mkv", "V" * 25, False)]
        entries = parse_listing(fake.listing_html(season))
        fake.server.server_close()
        self.assertEqual([(e.id, e.is_folder) for e in entries], [("F" * 25, True), ("V" * 25, False)])
        self.assertEqual(entries[1].name, "Ep & S01E01.mkv")

    def test_a_page_that_is_not_a_listing_is_not_an_empty_folder(self):
        self.assertIsNone(parse_listing("<html><body>Sign in</body></html>"))
        self.assertEqual(parse_listing('<div class="flip-entries"></div>'), [])


class ConfigTest(unittest.TestCase):
    def test_round_trip_and_policy(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "pipeline.json"
            original = Config(("RootFolder123",), {"player-1234": PlayerPolicy("Kid", ("Scrubs",), 2)})
            cfg.save(path, original)
            loaded = cfg.load(path)
        self.assertEqual(loaded, original)
        self.assertTrue(loaded.players["player-1234"].allows("scrubs"))
        self.assertFalse(loaded.players["player-1234"].allows("Girls"))
        self.assertTrue(PlayerPolicy("All", cfg.ALL_SHOWS).allows("Anything"))

    def test_rejects_invalid_files_instead_of_half_applying_them(self):
        bad = [
            {"version": 2},
            {"version": 1, "players": {"x": {"name": "short id"}}},
            {"version": 1, "players": {"player-1234": {"name": "P", "per_show": 0}}},
            {"version": 1, "players": {"player-1234": {"name": "P", "shows": "Scrubs"}}},
            {"version": 1, "limits": {"require_unmetered": "yes"}},
        ]
        for raw in bad:
            with self.subTest(raw=raw), self.assertRaises(ConfigError):
                cfg.parse(raw)

    def test_player_lookup_by_name_or_id_prefix(self):
        config = Config(players={"abcdef12-0000": PlayerPolicy("Kid's player")})
        self.assertEqual(config.player_named("kid's PLAYER")[0], "abcdef12-0000")
        self.assertEqual(config.player_named("abcdef")[0], "abcdef12-0000")
        with self.assertRaises(ConfigError):
            config.player_named("nobody")


def _ep(show, season, number, state="discovered", size=None, kind="episode", retry_at=0):
    label = f"{show} S{season:02d}E{number:02d}" if number else f"{show} S{season:02d} - extra"
    base = new_episode(f"{show}-{season}-{number}".replace(" ", "_") + "_" * 10, None, show, label, kind,
                       season, number, label, size)
    return base.__class__(**{**base.__dict__, "state": state, "retry_at": retry_at})


class PlannerTest(unittest.TestCase):
    def setUp(self):
        self.episodes = [
            _ep("Scrubs", 1, 2, size=300), _ep("Scrubs", 1, 1, size=300),
            _ep("Glee", 1, 1, size=100), _ep("Glee", 1, 2, size=100),
            _ep("Girls", 1, 1, size=50),
        ]

    def test_round_robin_across_allowed_shows_smallest_first(self):
        shows = planner.by_show(self.episodes)
        policy = PlayerPolicy("P", ("Scrubs", "Glee"), per_show=2)
        got = [e.label for e in planner.next_for_player(shows, set(), policy)]
        self.assertEqual(got, ["Glee S01E01", "Scrubs S01E01", "Glee S01E02", "Scrubs S01E02"])

    def test_episodes_a_player_already_had_are_never_offered_again(self):
        shows = planner.by_show(self.episodes)
        had = {e.drive_id for e in self.episodes if e.label == "Scrubs S01E01"}
        got = planner.next_for_player(shows, had, PlayerPolicy("P", ("Scrubs",)))
        self.assertEqual([e.label for e in got], ["Scrubs S01E02"])

    def test_a_permanently_failed_file_is_skipped_but_a_backing_off_one_keeps_its_place(self):
        eps = [_ep("Scrubs", 1, 1, state="failed"), _ep("Scrubs", 1, 2, retry_at=999), _ep("Scrubs", 1, 3)]
        wanted = planner.next_for_player(planner.by_show(eps), set(), PlayerPolicy("P", ("Scrubs",)))
        self.assertEqual([e.label for e in wanted], ["Scrubs S01E02"])
        self.assertEqual(planner.staging_queue([wanted], now=100), [])
        self.assertEqual(len(planner.staging_queue([wanted], now=1000)), 1)

    def test_extras_are_used_only_when_a_show_has_no_numbered_episodes(self):
        eps = [_ep("Buffy", 6, None, kind="extra"), _ep("Scrubs", 1, None, kind="extra"), _ep("Scrubs", 1, 1)]
        shows = planner.by_show(eps)
        self.assertEqual([e.kind for e in shows["Buffy"]], ["extra"])
        self.assertEqual([e.label for e in shows["Scrubs"]], ["Scrubs S01E01"])


class StoreTest(unittest.TestCase):
    def test_new_database_is_created_at_the_current_schema(self):
        with tempfile.TemporaryDirectory() as tmp:
            store = Store(Path(tmp) / "p.db")
            self.assertEqual(store.schema_version(), SCHEMA_VERSION)
            store.close()

    def test_refuses_a_database_from_a_newer_version(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "p.db"
            with sqlite3.connect(path) as conn:
                conn.execute(f"PRAGMA user_version = {SCHEMA_VERSION + 1}")
            with self.assertRaises(StoreError):
                Store(path)

    def test_backup_restores_representative_state(self):
        """Invariant: what was recorded survives backup -> loss -> restore without losing meaning."""
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "p.db"
            store = Store(path)
            store.insert_episode(new_episode("EpisodeId123456", None, "Scrubs", "S1/a.mkv", "episode", 1, 1,
                                             "Scrubs S01E01"), 10)
            store.mark_staged("EpisodeId123456", 1000, "ab" * 32, 12.5, 11)
            store.set_copy("player-1", "EpisodeId123456", "verified", 12, 1000, 3.0)
            backup = store.backup()
            store.close()
            path.unlink()
            backup.replace(path)
            restored = Store(path)
            ep = restored.episode("EpisodeId123456")
            self.assertEqual((ep.state, ep.amv_sha256), ("staged", "ab" * 32))
            self.assertEqual(restored.copies("player-1"), {"EpisodeId123456": "verified"})
            restored.close()

    def test_failures_back_off_then_give_up(self):
        with tempfile.TemporaryDirectory() as tmp:
            store = Store(Path(tmp) / "p.db")
            store.insert_episode(new_episode("EpisodeId123456", None, "S", "p", "episode", 1, 1, "S S01E01"), 0)
            first = store.record_failure("EpisodeId123456", "boom", 100)
            self.assertEqual((first.state, first.retry_at), ("discovered", 100 + 15 * 60))
            for _ in range(4):
                last = store.record_failure("EpisodeId123456", "boom", 100)
            self.assertEqual(last.state, "failed")
            self.assertEqual(store.retry(["EpisodeId123456"], 200), 1)
            self.assertEqual(store.episode("EpisodeId123456").state, "discovered")
            store.close()


if __name__ == "__main__":
    unittest.main()
