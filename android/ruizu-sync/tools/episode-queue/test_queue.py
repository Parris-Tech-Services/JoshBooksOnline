import json, tempfile, time, unittest, subprocess
from pathlib import Path
from unittest.mock import patch
from ruizu_queue import Worker, digest, verify_amv, MARKER, rename_no_replace

class QueueTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory(); self.p=Path(self.tmp.name); self.w=Worker(self.p/'state'); self.mount=self.p/'player'; (self.mount/'VIDEO').mkdir(parents=True)
        self.mountpatch=patch('ruizu_queue.os.path.ismount',lambda p:Path(p)==self.mount); self.mountpatch.start()
    def tearDown(self): self.mountpatch.stop(); self.w.close(); self.tmp.cleanup()
    def seed(self):
        self.w.catalog([('a','Show S01E01','drive:abcdefghijk',100),('b','Show S01E02','drive:bbbbbbbbbbb',100),('c','Other S01E01','drive:ccccccccccc',100)])
        self.w.enqueue('player-a')
    def row(self): return self.w.db.execute("SELECT * FROM jobs WHERE target='player-a' ORDER BY label LIMIT 1").fetchone()
    def staged(self):
        self.seed(); r=self.row(); _,out=self.w.paths(r); out.write_bytes(b'fixture AMV bytes'); self.w.update(r,stage='staged',sha=digest(out)); return self.row(),out
    def test_persistence_and_variety(self):
        self.seed(); self.w.enqueue('player-a'); self.assertEqual(2,self.w.db.execute('SELECT count(*) FROM jobs').fetchone()[0]); self.w.enqueue('player-b',True); self.assertEqual(5,self.w.db.execute('SELECT count(*) FROM jobs').fetchone()[0]); self.w.close(); self.w=Worker(self.p/'state'); self.assertEqual(5,self.w.db.execute('SELECT count(*) FROM jobs').fetchone()[0])
    def test_discovery_cache(self):
        listing=[{'Path':'Show/Season 1/Show S01E01.mp4','Size':100,'ID':'id123','ModTime':'2026-01-01'}]
        with patch('ruizu_queue.command',return_value=json.dumps(listing)) as call:
            self.w.discover('drive:TV',86400); self.w.discover('drive:TV',86400); self.assertEqual(1,call.call_count)
        self.assertEqual(1,self.w.db.execute('SELECT count(*) FROM catalog').fetchone()[0])
    def test_wrong_device(self):
        self.w.register(self.mount,'player-b'); r,_=self.staged()
        with self.assertRaises(RuntimeError): self.w.transfer(r,self.mount,0)
        self.assertEqual([],list((self.mount/'VIDEO').iterdir()))
    def test_transfer_and_idempotence(self):
        self.w.register(self.mount,'player-a'); r,out=self.staged(); self.w.transfer(r,self.mount,0); dest=self.mount/'VIDEO'/(r['label']+' - RUIZU.amv'); self.assertEqual(digest(out),digest(dest)); self.assertEqual('verified',self.row()['stage']); self.w.transfer(self.row(),self.mount,0); self.assertEqual(0,self.w.audit('player-a',self.mount))
    def test_conflict_preserved(self):
        self.w.register(self.mount,'player-a'); r,_=self.staged(); dest=self.mount/'VIDEO'/(r['label']+' - RUIZU.amv'); dest.write_bytes(b'foreign')
        with self.assertRaises(RuntimeError): self.w.transfer(r,self.mount,0)
        self.assertEqual(b'foreign',dest.read_bytes()); self.assertEqual('staged',self.row()['stage'])
    def test_atomic_rename_no_overwrite(self):
        a=self.p/'a'; b=self.p/'b'; a.write_text('a'); b.write_text('b')
        with self.assertRaises(FileExistsError): rename_no_replace(a,b)
        self.assertEqual('b',b.read_text())
    def test_disconnect_does_not_starve_downloads(self):
        self.staged()
        with patch.object(self.w,'prepare') as prep: self.w.run('player-a',self.mount,1,0,True); self.assertEqual(1,prep.call_count); self.assertEqual('Show S01E01',prep.call_args[0][0]['label'])
    def test_error_backoff_isolated(self):
        self.seed()
        with patch.object(self.w,'prepare',side_effect=RuntimeError('network down')) as prep: self.w.run('player-a',self.mount,2,0,False); self.assertEqual(2,prep.call_count)
        self.assertEqual(2,self.w.db.execute('SELECT count(*) FROM jobs WHERE retry>? AND attempts=1',(time.time(),)).fetchone()[0])
    def test_real_conversion(self):
        source=self.p/'source.mp4'; out=self.p/'out.amv'; subprocess.run(['ffmpeg','-v','error','-f','lavfi','-i','testsrc2=size=320x240:rate=25','-f','lavfi','-i','sine=frequency=440:sample_rate=44100','-t','4','-c:v','mpeg4','-c:a','aac',str(source)],check=True)
        self.assertEqual(self.w.encode(source,out),verify_amv(out,4)); self.assertFalse(out.with_suffix('.part.amv').exists())
    def test_download_byte_mismatch(self):
        self.seed(); r=self.row(); src,_=self.w.paths(r)
        def fake(args,timeout): Path(args[args.index('--output')+1]).write_bytes(b'HTML'); return ''
        with patch('ruizu_queue.command',side_effect=fake):
            with self.assertRaises(ValueError): self.w.download(r,src)
        self.assertFalse(src.exists())
    def test_existing_app_marker_is_preserved(self):
        marker=self.mount/MARKER; original='{"version":1,"id":"existing-123","name":"Existing","firstSeenMs":1234}'
        marker.write_text(original); self.w.register(self.mount,'player-a'); self.assertEqual(original,marker.read_text())
        with self.assertRaises(RuntimeError): self.w.register(self.mount,'player-b')
    def test_interrupted_copy_retries(self):
        self.w.register(self.mount,'player-a'); r,out=self.staged()
        with patch('ruizu_queue.rename_no_replace',side_effect=OSError('simulated interruption')):
            with self.assertRaises(OSError): self.w.transfer(r,self.mount,0)
        self.assertEqual('staged',self.row()['stage']); self.assertFalse((self.mount/'VIDEO'/(r['label']+' - RUIZU.amv')).exists())
        self.w.transfer(self.row(),self.mount,0); self.assertEqual('verified',self.row()['stage']); self.assertEqual([],list((self.mount/'VIDEO').glob('*.part')))
    def test_read_only_status_during_worker(self):
        self.seed(); reader=Worker(self.p/'state',readonly=True); self.assertEqual(2,reader.db.execute('SELECT count(*) FROM jobs').fetchone()[0]); reader.close()

if __name__=='__main__': unittest.main()
