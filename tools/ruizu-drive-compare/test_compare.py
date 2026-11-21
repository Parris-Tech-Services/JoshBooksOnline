import csv
import json
import tempfile
import unittest
from pathlib import Path

from compare import (audit_source, compare_files, csv_text, load_drive, load_player,
                     main, norm_name, safe_output_dir, scan_player)


class CompareTests(unittest.TestCase):
    @staticmethod
    def drive(name="Artist - Song.mp3", size=123, ident="drive_1", **extra):
        return {"drive_file_id": ident, "file_name": name, "relative_path": name,
                "source_root": "Music", "size_bytes": size, "comparison_name_key": norm_name(name), **extra}

    @staticmethod
    def player(name="Artist - Song.mp3", size=123, **extra):
        return {"file_name": Path(name).name, "relative_path": name,
                "size_bytes": size, "comparison_name_key": norm_name(name), "sha256": None, **extra}

    def test_normalization_accents_and_punctuation(self):
        self.assertEqual(norm_name("José_-_A Song.MP3"), "jose a song")
        self.assertEqual(norm_name("Mix.Track.m4a"), "mix track")

    def test_exact_name_and_size_only_likely(self):
        result, rows = compare_files([self.drive()], [self.player("MUSIC/artist - song.MP3")], True)
        self.assertEqual(rows[0]["status"], "LIKELY_PRESENT")
        self.assertIn("unverified", rows[0]["reason"])

    def test_same_title_changed_size_possible(self):
        _, rows = compare_files([self.drive()], [self.player(size=200)], True)
        self.assertEqual(rows[0]["status"], "POSSIBLE_MATCH")

    def test_renamed_same_size_not_false_positive(self):
        _, rows = compare_files([self.drive()], [self.player(name="Unrelated.mp3")], True)
        self.assertEqual(rows[0]["status"], "NOT_FOUND_IN_SCAN")

    def test_unconfirmed_inventory_never_reports_missing(self):
        _, rows = compare_files([self.drive()], [], False)
        self.assertEqual(rows[0]["status"], "UNCONFIRMED")

    def test_no_files_scan_not_complete(self):
        with tempfile.TemporaryDirectory() as t:
            player, errors = scan_player(Path(t))
            self.assertEqual((player, errors), ([], []))

    def test_zero_byte_source_is_not_music(self):
        _, rows = compare_files([self.drive(size=0)], [self.player(size=0)], True)
        self.assertEqual(rows[0]["status"], "SOURCE_EMPTY")

    def test_hash_confirmation_with_renamed_file(self):
        digest = "f" * 64
        _, rows = compare_files([self.drive(content_sha256=digest)],
                                [self.player(name="Changed Name.mp3", sha256=digest)], True)
        self.assertEqual(rows[0]["status"], "VERIFIED_PRESENT")

    def test_same_hash_with_conflicting_known_sizes_is_not_verified(self):
        # A full-file digest cannot describe two different known byte lengths.
        digest = "a" * 64
        _, rows = compare_files([self.drive(size=123, content_sha256=digest)],
                                [self.player(size=456, sha256=digest)], True)
        self.assertEqual(rows[0]["status"], "POSSIBLE_MATCH")
        self.assertIn("sizes contradict", rows[0]["reason"])

    def test_same_hash_with_multiple_candidates_prefers_size_consistent(self):
        digest = "b" * 64
        _, rows = compare_files([self.drive(size=123, content_sha256=digest)],
                                [self.player(name="Wrong.mp3", size=999, sha256=digest),
                                 self.player(name="Correct.mp3", size=123, sha256=digest)], True)
        self.assertEqual(rows[0]["status"], "VERIFIED_PRESENT")
        self.assertEqual(rows[0]["player_paths"], ["Correct.mp3"])

    def test_matching_digest_with_unknown_size_still_matches(self):
        digest = "c" * 64
        _, rows = compare_files([self.drive(size=None, content_sha256=digest)],
                                [self.player(size=123, sha256=digest)], True)
        self.assertEqual(rows[0]["status"], "VERIFIED_PRESENT")

    def test_conflicting_hash_cannot_be_likely(self):
        _, rows = compare_files([self.drive(content_sha256="a"*64)],
                                [self.player(sha256="b"*64)], True)
        self.assertEqual(rows[0]["status"], "POSSIBLE_MATCH")

    def test_matching_hash_preferred_over_same_filename(self):
        _, rows = compare_files([self.drive(content_sha256="a"*64)],
                                [self.player(name="mismatch.mp3", sha256="a"*64), self.player(size=123, sha256="b"*64)], True)
        self.assertEqual(rows[0]["status"], "VERIFIED_PRESENT")
        self.assertEqual(rows[0]["player_paths"], ["mismatch.mp3"])

    def test_zip_manifest_import(self):
        import zipfile
        with tempfile.TemporaryDirectory() as td:
            p = Path(td) / "bundle.zip"
            with zipfile.ZipFile(p, "w") as f:
                f.writestr("manifest.json", json.dumps({"files": [self.drive()]}))
            self.assertEqual(load_drive(p)[0]["drive_file_id"], "drive_1")

    def test_duplicate_drive_ids_rejected(self):
        with tempfile.TemporaryDirectory() as td:
            p = Path(td) / "input.json"
            p.write_text(json.dumps({"files": [self.drive(), self.drive()]}))
            with self.assertRaisesRegex(ValueError, "repeated Drive ID"):
                load_drive(p)

    def test_negative_size_rejected(self):
        with tempfile.TemporaryDirectory() as td:
            p = Path(td) / "x.json"
            p.write_text(json.dumps({"files": [self.drive(size=-1)]}))
            with self.assertRaisesRegex(ValueError, "negative byte count"):
                load_drive(p)

    def test_player_json_and_csv_input(self):
        with tempfile.TemporaryDirectory() as td:
            p = Path(td) / "p.json"
            p.write_text(json.dumps({"files": [{"relative_path":"Music/Song.mp3","size_bytes":150}]}))
            self.assertEqual(load_player(p)[0]["file_name"], "Song.mp3")
            p = Path(td) / "p.csv"
            p.write_text("relative_path,size_bytes\nMusic/Song.mp3,150\n")
            self.assertEqual(load_player(p)[0]["size_bytes"], 150)

    def test_audit_excludes_empty_duplication(self):
        values=[self.drive(size=0), self.drive(size=0, ident="drive_2"),
                self.drive(size=100, ident="drive_3"), self.drive(size=100, ident="drive_4")]
        report, candidates=audit_source(values)
        self.assertEqual(report["nonempty_same_name_size_groups"], 1)
        self.assertEqual(len(report["zero_byte_drive_ids"]), 2)
        self.assertEqual(len(candidates), 2)

    def test_audit_mime_conflict_flagged(self):
        report, _=audit_source([self.drive(name="song.m4a", mime_type="video/mp4")])
        self.assertEqual(report["m4a_video_mime_drive_ids"], ["drive_1"])

    def test_no_writes_to_player_directory(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td) / "mounted"; root.mkdir()
            with self.assertRaisesRegex(ValueError, "Refusing"):
                safe_output_dir(root / "reports", root)
            self.assertFalse((root / "reports").exists())

    def test_symlink_not_followed(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td) / "player";root.mkdir()
            secret=Path(td)/"private.mp3";secret.write_bytes(b"sensitive")
            (root/"private.mp3").symlink_to(secret)
            result, errors = scan_player(root)
            self.assertFalse(result)
            self.assertTrue(errors)

    def test_scan_audio_only_no_media_changes(self):
        with tempfile.TemporaryDirectory() as td:
            root=Path(td)/"player";root.mkdir()
            (root/"A.mp3").write_bytes(b"abc")
            (root/"MUSIC.LIB").write_bytes(b"do-not-touch")
            before=(root/"MUSIC.LIB").read_bytes()
            result,errors=scan_player(root)
            self.assertEqual(len(result), 1)
            self.assertEqual(result[0]["size_bytes"], 3)
            self.assertEqual((root/"MUSIC.LIB").read_bytes(), before)
            self.assertFalse(errors)

    def test_csv_formula_injection_protected(self):
        output=csv_text(["filename"], [["=1+1"], ["@cmd"], ["-2"], ["normal"]])
        r=list(csv.reader(output.splitlines()))
        self.assertEqual(r[1][0], "'=1+1")
        self.assertEqual(r[2][0], "'@cmd")
        self.assertEqual(r[3][0], "'-2")

    def test_cli_comparison_produces_reports_and_preserves_source(self):
        with tempfile.TemporaryDirectory() as td:
            root=Path(td)
            d=root/"drive.json";d.write_text(json.dumps({"files":[self.drive()]}))
            p=root/"player";p.mkdir();(p/"Artist - Song.mp3").write_bytes(b"x"*123)
            out=root/"output"
            self.assertEqual(main(["compare","--drive-manifest",str(d),"--player-root",str(p),"--output-dir",str(out)]),0)
            self.assertEqual(json.loads((out/"comparison-summary.json").read_text())["status_counts"],{"LIKELY_PRESENT":1})
            self.assertEqual((p/"Artist - Song.mp3").stat().st_size,123)

    def test_cli_rejects_output_inside_player(self):
        with tempfile.TemporaryDirectory() as td:
            root=Path(td)
            d=root/"drive.json";d.write_text(json.dumps({"files":[self.drive()]}))
            p=root/"player";p.mkdir();(p/"Artist - Song.mp3").write_bytes(b"x"*123)
            self.assertEqual(main(["compare","--drive-manifest",str(d),"--player-root",str(p),"--output-dir",str(p/"report")]),2)
            self.assertFalse((p/"report").exists())

    def test_external_manifest_unconfirmed_by_default(self):
        with tempfile.TemporaryDirectory() as td:
            root=Path(td);d=root/"drive.json";d.write_text(json.dumps({"files":[self.drive()]}))
            p=root/"player.json";p.write_text(json.dumps({"files":[{"file_name":"Another.mp3","size_bytes":200}]}))
            output=root/"out"
            self.assertEqual(main(["compare","--drive-manifest",str(d),"--player-manifest",str(p),"--output-dir",str(output)]),0)
            self.assertEqual(json.loads((output/"comparison-summary.json").read_text())["status_counts"],{"UNCONFIRMED":1})


if __name__ == "__main__":
    unittest.main()