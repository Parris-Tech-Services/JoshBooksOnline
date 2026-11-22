import importlib.util
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('ruizu_diagnose_repair', Path(__file__).parent / 'ruizu_diagnose_repair.py')
r = importlib.util.module_from_spec(spec)
spec.loader.exec_module(r)

@unittest.skipUnless(shutil.which('ffmpeg') and shutil.which('ffprobe'), 'requires FFmpeg')
class RuizuDiagnosisTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.base = Path(self.tmp.name)
        self.root = self.base / 'player'
        (self.root / 'VIDEO').mkdir(parents=True)
        self.downloads = self.base / 'Downloads'
        self.downloads.mkdir()
        self.home = self.base / 'home'
        self.home.mkdir()
        self.sample = self.root / 'VIDEO' / 'All DX.avi'
        self.make_avi(self.sample, 208, 176, codec='mjpeg')

    def tearDown(self):
        self.tmp.cleanup()

    def make_avi(self, target, w, h, codec='mpeg4'):
        cmd = ['ffmpeg', '-hide_banner', '-loglevel', 'error', '-nostdin',
               '-f', 'lavfi', '-i', f'testsrc2=size={w}x{h}:rate=20',
               '-f', 'lavfi', '-i', 'sine=frequency=440:sample_rate=22050',
               '-t', '1.5', '-c:v', codec,
               '-c:a', 'adpcm_ima_wav', '-ar', '22050', '-ac', '1',
               '-f', 'avi', str(target)]
        subprocess.run(cmd, check=True, capture_output=True)

    def test_riff_index_found_after_moviedata(self):
        idx = r.avi_structure(self.sample)
        self.assertTrue(idx['has_idx1'])
        self.assertFalse(idx['issues'])
        self.assertGreaterEqual(idx['avih'][0]['total_frames'], 20)
        self.assertEqual(idx['avih'][0]['width'], 208)

    def test_read_only_diagnosis_and_new_preview_preserve_all_existing_files(self):
        source = self.downloads / 'Wreck It Ralph (2012).avi'
        self.make_avi(source, 320, 240)
        orig_source_hash = r.sha256(source)
        orig_vendor_hash = r.sha256(self.sample)
        before = {str(p.relative_to(self.root)): r.sha256(p) for p in r.safe_files(self.root, recursive=True)}
        report = r.diagnostic_report(self.root, self.home, self.downloads)
        self.assertEqual(len(report['player_videos']), 1)
        self.assertTrue(report['vendor_video']['riff']['has_idx1'])
        output, meta = r.create_preview(self.root, self.downloads, self.sample)
        self.assertTrue(output.is_file())
        self.assertNotIn('conversion_error', meta)
        result = r.avi_structure(output)
        self.assertTrue(result['has_idx1'])
        self.assertTrue(result['avih'][0]['has_index_flag'])
        v, a = r.streams(r.ffprobe(output))
        self.assertEqual((v['codec_name'], v['width'], v['height']), ('mjpeg', 208, 176))
        self.assertEqual(a['codec_name'], 'adpcm_ima_wav')
        self.assertEqual(orig_source_hash, r.sha256(source))
        self.assertEqual(orig_vendor_hash, r.sha256(self.sample))
        self.assertEqual(before, {str(p.relative_to(self.root)): r.sha256(p) for p in r.safe_files(self.root, recursive=True)})

    def test_create_only_copy_rejects_existing_path(self):
        source = self.downloads / 'Wreck It Ralph (2012).avi'
        self.make_avi(source, 320, 240)
        target = self.root / 'VIDEO' / 'NEWTEST.avi'
        self.assertEqual(r.copy_create_only(source, target), str(target))
        self.assertEqual(r.sha256(target), r.sha256(source))
        with self.assertRaises(FileExistsError):
            r.copy_create_only(self.sample, target)
        self.assertEqual(r.sha256(target), r.sha256(source))

    def test_duplicate_restoration_requires_two_identical_copies_and_log(self):
        a = self.root / '__1' / 'Marconi Union - Weightless.mp3'
        b = self.root / 'Music' / 'Marconi Union - Weightless.mp3'
        for file in (a, b):
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_bytes(b'identical audio')
        hold = self.root / '_Duplicates & partials'
        hold.mkdir()
        report = r.gather_marconi(self.root, self.home)
        with self.assertRaisesRegex(RuntimeError, 'No matching move log evidence'):
            r.restore_marconi(self.root, report)
        logdir = self.home / '.ruizu-investigate'
        logdir.mkdir()
        (logdir / 'sort.tsv').write_text('__1/marconi-union-weightless.mp3\t_Duplicates & partials/marconi-union-weightless.mp3\n')
        report = r.gather_marconi(self.root, self.home)
        target = r.restore_marconi(self.root, report)
        self.assertTrue(Path(target).is_file())
        self.assertEqual(r.sha256(Path(target)), r.sha256(a))
        self.assertEqual(r.sha256(a), r.sha256(b))
        with self.assertRaisesRegex(RuntimeError, 'already exists'):
            r.restore_marconi(self.root, report)

    def test_no_movie_generates_synthetic_preview_with_vendor_format(self):
        output, meta = r.create_preview(self.root, self.downloads, self.sample)
        self.assertTrue(output.exists())
        self.assertEqual(meta['conversion_settings']['source'], 'synthetic test pattern')
        self.assertTrue(meta['verified_file']['riff']['has_idx1'])

if __name__ == '__main__':
    unittest.main()