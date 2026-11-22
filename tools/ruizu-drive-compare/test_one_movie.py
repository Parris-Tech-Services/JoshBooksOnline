import importlib.util
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

module_path=Path(__file__).parent/'one_movie.py'
spec=importlib.util.spec_from_file_location('one_movie',module_path)
mod=importlib.util.module_from_spec(spec);spec.loader.exec_module(mod)

class MovieTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.home=Path(self.temp.name)/'home';self.home.mkdir()
        self.downloads=self.home/'storage/downloads';self.downloads.mkdir(parents=True)
        self.player=Path(self.temp.name)/'player';(self.player/'VIDEO').mkdir(parents=True)
        self.patches=[patch.dict(os.environ,{'HOME':str(self.home)}),patch.object(Path,'home',return_value=self.home),patch.object(mod,'PLAYER',self.player)]
        for p in self.patches:p.start()
        self.args=type('Args',(),{'source':None,'no_copy':False})()
    def tearDown(self):
        for p in reversed(self.patches):p.stop()
        self.temp.cleanup()
    def test_no_movie_is_safe_and_explains_drive_download(self):
        with self.assertRaisesRegex(RuntimeError,'tap Download'):
            mod.run(self.args)
        self.assertEqual(list((self.player/'VIDEO').iterdir()),[])
    def test_corrupt_mp4_is_rejected_before_writing(self):
        (self.downloads/mod.NAME).write_bytes(b'not a movie')
        with self.assertRaisesRegex(RuntimeError,'not recognised'):
            mod.run(self.args)
        self.assertEqual(list((self.player/'VIDEO').iterdir()),[])
    @unittest.skipUnless(__import__('shutil').which('ffmpeg') and __import__('shutil').which('ffprobe'),'ffmpeg not installed')
    def test_convert_copy_hash_and_never_overwrite(self):
        import subprocess
        source=self.downloads/mod.NAME
        subprocess.run(['ffmpeg','-hide_banner','-loglevel','error','-y','-f','lavfi','-i','testsrc2=size=640x360:rate=24','-f','lavfi','-i','sine=frequency=330','-t','1.2','-c:v','mpeg4','-c:a','aac',str(source)],check=True)
        mod.run(self.args)
        result=self.downloads/mod.OUTPUT;copy=self.player/'VIDEO'/mod.OUTPUT
        self.assertTrue(result.is_file());self.assertTrue(copy.is_file())
        self.assertEqual(mod.sha256(result),mod.sha256(copy))
        before=copy.stat().st_mtime_ns
        mod.run(self.args)
        self.assertEqual(before,copy.stat().st_mtime_ns)

if __name__=='__main__':unittest.main()