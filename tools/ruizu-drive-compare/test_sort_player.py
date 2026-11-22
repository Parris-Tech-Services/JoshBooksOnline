import importlib.util
import tempfile
import unittest
from pathlib import Path

spec=importlib.util.spec_from_file_location('sort_player',Path(__file__).parent/'sort_player.py')
sorter=importlib.util.module_from_spec(spec)
spec.loader.exec_module(sorter)

class SorterTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory()
        self.root=Path(self.tmp.name)/'0000-006F'
        self.root.mkdir()

    def tearDown(self):self.tmp.cleanup()

    def put(self,rel,content=b'audio'):
        p=self.root/rel
        p.parent.mkdir(parents=True,exist_ok=True)
        p.write_bytes(content)
        return p

    def statuses(self):return {row['current_path']:row for row in sorter.plan(self.root)}

    def test_known_music_and_audiobook_moves_leave_podcasts_and_lib_untouched(self):
        self.put('__1/James Bay/01 Craving.m4a',b'james')
        self.put('__1/_Outlander/Voyager 3/001_Intro.mp3',b'outlander')
        self.put('Marconi Union - Weightless.mp3',b'weightless')
        self.put('Podcasts/Episode.mp3',b'podcast')
        lib=self.put('Music/.LIB',b'index')
        before=self.statuses()
        self.assertEqual(before['__1/James Bay/01 Craving.m4a']['proposed_path'],'Music/James Bay/01 Craving.m4a')
        self.assertEqual(before['__1/_Outlander/Voyager 3/001_Intro.mp3']['proposed_path'],'Audiobooks/Outlander/Voyager 3/001_Intro.mp3')
        self.assertEqual(before['Marconi Union - Weightless.mp3']['proposed_path'],'Music/Unsorted/Marconi Union - Weightless.mp3')
        self.assertEqual(before['Podcasts/Episode.mp3']['status'],'LEAVE')
        result=sorter.apply(self.root,list(before.values()))
        self.assertEqual(sum(x.startswith('MOVED') for x in result),3)
        self.assertEqual((self.root/'Music/James Bay/01 Craving.m4a').read_bytes(),b'james')
        self.assertEqual((self.root/'Audiobooks/Outlander/Voyager 3/001_Intro.mp3').read_bytes(),b'outlander')
        self.assertEqual((self.root/'Podcasts/Episode.mp3').read_bytes(),b'podcast')
        self.assertEqual(lib.read_bytes(),b'index')

    def test_no_overwrite_and_no_auto_moves_of_unknown_folders(self):
        self.put('__1/Missy Higgins/01 Steer.mp3',b'original')
        dest=self.put('Music/Missy Higgins/01 Steer.mp3',b'existing')
        self.put('__1/drive/2026_timestamp.mp3',b'unknown')
        data=self.statuses()
        self.assertEqual(data['__1/Missy Higgins/01 Steer.mp3']['status'],'COLLISION')
        self.assertEqual(data['__1/drive/2026_timestamp.mp3']['status'],'REVIEW')
        self.assertEqual(sorter.apply(self.root,list(data.values())),[])
        self.assertEqual(dest.read_bytes(),b'existing')
        self.assertTrue((self.root/'__1/Missy Higgins/01 Steer.mp3').exists())

    def test_existing_playlist_protects_referenced_file(self):
        self.put('__1/Birdy/Young Blood.mp3',b'1')
        self.put('Music/Birdy Mix.m3u',b'../__1/Birdy/Young Blood.mp3\n')
        self.assertEqual(self.statuses()['__1/Birdy/Young Blood.mp3']['status'],'PLAYLIST_PROTECTED')
        self.assertEqual(sorter.apply(self.root,list(self.statuses().values())),[])

    def test_symlinks_are_not_followed_and_outside_media_not_accessed(self):
        external=Path(self.tmp.name)/'other'
        external.mkdir()
        (external/'evil.mp3').write_bytes(b'outside')
        (self.root/'__1').symlink_to(external,target_is_directory=True)
        self.assertEqual(self.statuses(),{})
        self.assertEqual((external/'evil.mp3').read_bytes(),b'outside')

    def test_csv_protects_formula_like_names(self):
        from tempfile import TemporaryDirectory
        with TemporaryDirectory() as t:
            path=Path(t)/'plan.csv'
            sorter.write_csv(path,[dict(status='REVIEW',current_path='=HYPERLINK("evil")',proposed_path='',size_bytes=2,reason='unknown')])
            self.assertIn("'=HYPERLINK",path.read_text(encoding='utf-8-sig'))

if __name__=='__main__':unittest.main()