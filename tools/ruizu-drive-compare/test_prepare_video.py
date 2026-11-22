import importlib.util
import tempfile
import unittest
from argparse import Namespace
from pathlib import Path
from unittest import mock

spec=importlib.util.spec_from_file_location('prepare_video',Path(__file__).parent/'prepare_video.py')
video=importlib.util.module_from_spec(spec)
spec.loader.exec_module(video)

class VideoTests(unittest.TestCase):
    def args(self,**changes):
        opts=dict(input=None,sample=True,profile='amv-160x128',full=False,output=None)
        opts.update(changes)
        return Namespace(**opts)

    def test_amv_uses_compatible_audio_block_size_and_frame_rate(self):
        cmd=video.command(None,Path('test.amv'),'amv-160x128',8,True)
        self.assertIn('adpcm_ima_amv',cmd)
        self.assertIn('-block_size',cmd)
        self.assertEqual(cmd[cmd.index('-block_size')+1],'1470')
        self.assertEqual(cmd[cmd.index('-r')+1],'15')

    def test_preview_limits_movie_to_20_seconds(self):
        cmd=video.command(Path('owned.mp4'),Path('out.amv'),'amv-160x128',20,False)
        self.assertEqual(cmd[cmd.index('-t')+1],'20')
        self.assertIn('0:a:0',cmd)

    def test_no_input_or_sample_is_rejected(self):
        with self.assertRaisesRegex(ValueError,'Supply --sample'):
            video.process(self.args(sample=False))

    def test_existing_file_is_never_overwritten(self):
        with tempfile.TemporaryDirectory() as d:
            outfile=Path(d)/'RUIZU-video-test-amv-160x128.amv'
            outfile.write_bytes(b'original')
            with self.assertRaisesRegex(ValueError,'already exists'):
                video.process(self.args(output=str(outfile)))
            self.assertEqual(outfile.read_bytes(),b'original')

    def test_mounted_player_output_rejected(self):
        with self.assertRaisesRegex(ValueError,'mounted player'):
            video.process(self.args(output='/mnt/media_rw/0000-006F/Videos/test.amv'))

if __name__=='__main__':unittest.main()