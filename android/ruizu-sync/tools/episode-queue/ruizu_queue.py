#!/usr/bin/env python3
"""Persistent RUIZU episode worker; state lives on the phone, never the player."""
import argparse
import ctypes
import fcntl
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import sqlite3
import subprocess
import sys
import time
import uuid

VIDEO = {'.avi', '.mp4', '.mkv', '.mov', '.m4v', '.webm', '.mpg', '.mpeg'}
MARKER = '.ruizu-sync-device.json'
PROFILE = 'scale=160:128:force_original_aspect_ratio=decrease,pad=160:128:(ow-iw)/2:(oh-ih)/2:black'
SE = re.compile(r'(?i)(?<![a-z0-9])(?:s(\d{1,2})[ ._-]?e(\d{1,3})|(\d{1,2})x(\d{1,3}))(?!\d)')

def command(args, timeout=1800):
    return subprocess.run(args, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=timeout).stdout

def digest(path):
    with open(path, 'rb') as f:
        return hashlib.file_digest(f, 'sha256').hexdigest() if hasattr(hashlib, 'file_digest') else hash_chunks(f)

def hash_chunks(f):
    h=hashlib.sha256()
    for chunk in iter(lambda:f.read(1024*1024), b''): h.update(chunk)
    return h.hexdigest()

def label_for(path):
    p=PurePosixPath(path); m=SE.search(p.stem)
    if not m or len(p.parts)<2: return None
    s,e=m.group(1,2) if m.group(1) else m.group(3,4)
    return f'{p.parts[0]} S{int(s):02}E{int(e):02}'

def safe_label(s):
    s=re.sub(r'[<>:"/\\|?*\x00-\x1f]', '_', s).strip(' .')
    if not s or len(s.encode('utf-8'))>180: raise ValueError('invalid or overlong episode label')
    return s

def probe(p, frames=False):
    return json.loads(command(['ffprobe','-v','error',*(['-count_frames'] if frames else []),'-show_streams','-show_format','-of','json',str(p)]))

def duration(p):
    info=probe(p)
    if not any(s['codec_type']=='video' for s in info['streams']) or not any(s['codec_type']=='audio' for s in info['streams']): raise ValueError('source lacks video or audio')
    d=float(info['format']['duration'])
    if not 0<d<86400: raise ValueError('invalid duration')
    return d

def verify_amv(p, seconds=None):
    info=probe(p,True); v=next(s for s in info['streams'] if s['codec_type']=='video'); a=next(s for s in info['streams'] if s['codec_type']=='audio')
    n=int(v['nb_read_frames']); rate=v.get('avg_frame_rate','0/1'); num,den=map(int,rate.split('/'))
    if (v['codec_name'],v['width'],v['height'],a['codec_name'],a['sample_rate'],a['channels'])!=('amv',160,128,'adpcm_ima_amv','22050',1) or den==0 or abs(num/den-15)>0.02 or n<1 or (seconds is not None and not .95*seconds*15<=n<=1.05*seconds*15+15): raise ValueError('AMV profile or duration mismatch')
    return digest(p)

def flush_file(path):
    with open(path,'rb') as f: os.fsync(f.fileno())

def rename_no_replace(src,dst,dirfd=-100):
    # Linux/Android renameat2 is atomic and refuses an existing destination.
    libc=ctypes.CDLL(None,use_errno=True)
    fn=getattr(libc,"renameat2",None)
    if fn is None: raise RuntimeError("atomic no-replace rename unavailable; refusing final write")
    fn.argtypes=[ctypes.c_int,ctypes.c_char_p,ctypes.c_int,ctypes.c_char_p,ctypes.c_uint]; fn.restype=ctypes.c_int
    if fn(dirfd,os.fsencode(src),dirfd,os.fsencode(dst),1)!=0:
        error=ctypes.get_errno(); raise OSError(error,os.strerror(error),str(dst))

def digest_at(fd,name):
    with os.fdopen(os.open(name,os.O_RDONLY|os.O_NOFOLLOW,dir_fd=fd),'rb') as f: return hash_chunks(f)

def exists_at(fd,name):
    try: os.stat(name,dir_fd=fd,follow_symlinks=False); return True
    except FileNotFoundError: return False

def atomic_move(src,dst):
    flush_file(src); os.replace(src,dst)
    fd=os.open(dst.parent,os.O_RDONLY)
    try: os.fsync(fd)
    finally: os.close(fd)

class Worker:
    def __init__(self, state, readonly=False):
        self.state=Path(state); self.state.mkdir(mode=0o700,parents=True,exist_ok=True)
        self.lock=None
        if readonly:
            self.db=sqlite3.connect(f"file:{self.state/'queue.sqlite3'}?mode=ro",uri=True); self.db.row_factory=sqlite3.Row; return
        self.lock=open(self.state/'worker.lock','a'); fcntl.flock(self.lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        self.db=sqlite3.connect(self.state/'queue.sqlite3'); self.db.row_factory=sqlite3.Row
        self.db.executescript('''PRAGMA journal_mode=WAL; PRAGMA synchronous=FULL;
          CREATE TABLE IF NOT EXISTS devices (target TEXT PRIMARY KEY, token TEXT NOT NULL UNIQUE);
          CREATE TABLE IF NOT EXISTS catalog (id TEXT PRIMARY KEY, label TEXT NOT NULL, source TEXT NOT NULL, bytes INTEGER NOT NULL);
          CREATE TABLE IF NOT EXISTS jobs (target TEXT, id TEXT, label TEXT, source TEXT, bytes INTEGER, stage TEXT DEFAULT 'queued', sha TEXT, attempts INTEGER DEFAULT 0, retry REAL DEFAULT 0, error TEXT DEFAULT '', PRIMARY KEY(target,id), UNIQUE(target,label));
          CREATE TABLE IF NOT EXISTS scans (source TEXT PRIMARY KEY, stamp REAL NOT NULL);''')
        self.work=self.state/'media'; self.work.mkdir(exist_ok=True)
    def close(self):
        self.db.close()
        if self.lock: self.lock.close()
    def catalog(self, rows):
        with self.db:
            for id,label,source,size in rows:
                if int(size)<=0: continue
                label=safe_label(label)
                self.db.execute('INSERT OR IGNORE INTO catalog VALUES(?,?,?,?)',(id,label,source,int(size)))
    def import_tsv(self,path,order):
        rows=[]
        for line in Path(path).read_text().splitlines():
            if not line.strip() or line.startswith('#'): continue
            a,b,c=line.split('\t')
            if order=='legacy':
                label=label_for(a)
                if not label: continue
                id,size=c,b
            else: id,label,size=a,b,c
            if not re.fullmatch(r'[A-Za-z0-9_-]{10,}',id): raise ValueError('invalid Drive ID')
            rows.append((id,label,'drive:'+id,int(size)))
        self.catalog(rows)
    def discover(self,remote,ttl,force=False):
        if ':' not in remote or remote.startswith('-'): raise ValueError('use an authenticated rclone remote and folder, e.g. drive:TV')
        previous=self.db.execute('SELECT stamp FROM scans WHERE source=?',(remote,)).fetchone()
        if previous and not force and time.time()-previous[0]<ttl: return
        items=json.loads(command(['rclone','lsjson','--recursive','--files-only',remote],900))
        if not isinstance(items,list): raise ValueError('invalid listing')
        counts={}
        for item in items: counts[item['Path']]=counts.get(item['Path'],0)+1
        rows=[]
        for item in items:
            path=item['Path']; label=label_for(path); p=PurePosixPath(path)
            if not label or p.suffix.lower() not in VIDEO or counts[path]!=1 or item['Size']<=0: continue
            id=item.get('ID') or remote+'/'+path
            # changed remote files get a new revision key; same-label conflict remains visible
            id=id+'@'+str(item.get('ModTime',''))+'@'+str(item['Size'])
            rows.append((id,label,'rclone:'+remote.rstrip('/')+'/'+path,item['Size']))
        self.catalog(rows)
        with self.db: self.db.execute('INSERT OR REPLACE INTO scans VALUES(?,?)',(remote,time.time()))
    def enqueue(self,target,all_episodes=False):
        rows=self.db.execute('SELECT * FROM catalog ORDER BY label COLLATE NOCASE,id').fetchall()
        seen={r[0].casefold() for r in self.db.execute('SELECT label FROM jobs WHERE target=?',(target,))}
        shows={x.rsplit(' s',1)[0] for x in seen}
        with self.db:
            for r in rows:
                label=r['label'].casefold(); show=label.rsplit(' s',1)[0]
                if label in seen or (not all_episodes and show in shows): continue
                self.db.execute('INSERT OR IGNORE INTO jobs(target,id,label,source,bytes) VALUES(?,?,?,?,?)',(target,r['id'],r['label'],r['source'],r['bytes'])); seen.add(label); shows.add(show)
    def marker_id(self,mount):
        path=mount/MARKER
        if path.stat().st_size>1024: raise ValueError('oversized player marker')
        data=json.loads(path.read_text()); token=data.get('id')
        if data.get('version',1)!=1 or not isinstance(token,str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,64}',token): raise ValueError('invalid player marker')
        return token
    def mount(self,mount,target,require=True):
        mount=Path(mount)
        if not os.path.ismount(mount) or not (mount/'VIDEO').is_dir(): raise RuntimeError('player not mounted')
        if require:
            row=self.db.execute('SELECT token FROM devices WHERE target=?',(target,)).fetchone()
            if not row or self.marker_id(mount)!=row[0]: raise RuntimeError('wrong or unregistered player')
        return mount
    def register(self,mount,target):
        mount=self.mount(mount,target,False); marker=mount/MARKER
        row=self.db.execute('SELECT token FROM devices WHERE target=?',(target,)).fetchone()
        if row: self.mount(mount,target); return
        if marker.exists(): token=self.marker_id(mount)
        else:
            token=str(uuid.uuid4())
            with marker.open('x') as f:
                json.dump({'version':1,'id':token,'name':target,'firstSeenMs':int(time.time()*1000)},f); f.flush(); os.fsync(f.fileno())
            os.sync()
        if self.db.execute('SELECT 1 FROM devices WHERE token=?',(token,)).fetchone(): raise RuntimeError('player already registered under another name')
        with self.db: self.db.execute('INSERT INTO devices VALUES(?,?)',(target,token))
    def update(self,r,**kw):
        with self.db: self.db.execute('UPDATE jobs SET '+','.join(k+'=?' for k in kw)+' WHERE target=? AND id=?',(*kw.values(),r['target'],r['id']))
    def paths(self,r):
        key=hashlib.sha256(r['id'].encode()).hexdigest(); return self.work/(key+'.src'), self.work/(key+'.amv')
    def download(self,r,src):
        if shutil.disk_usage(self.work).free<r['bytes']*2+128*1024**2: raise RuntimeError('insufficient phone space')
        temp=src.with_suffix('.download.part'); temp.unlink(missing_ok=True)
        if r['source'].startswith('drive:'):
            id=r['source'][6:]; command(['curl','--fail','--location','--max-time','1800','--output',str(temp),'https://drive.usercontent.google.com/download?id='+id+'&export=download&confirm=t'],1850)
        else: command(['rclone','copyto',r['source'][7:],str(temp),'--retries','2'],1850)
        if temp.stat().st_size!=r['bytes']: raise ValueError('download byte count mismatch')
        duration(temp); atomic_move(temp,src)
    def encode(self,src,out):
        d=duration(src); temp=out.with_suffix('.part.amv'); temp.unlink(missing_ok=True)
        command(['ffmpeg','-nostdin','-hide_banner','-loglevel','error','-xerror','-y','-i',str(src),'-map','0:v:0','-map','0:a:0','-vf',PROFILE,'-r','15','-c:v','amv','-c:a','adpcm_ima_amv','-block_size','1470','-ar','22050','-ac','1','-threads','4',str(temp)],3600)
        sha=verify_amv(temp,d); atomic_move(temp,out); return sha
    def prepare(self,r):
        src,out=self.paths(r)
        if out.exists() and r['sha'] and digest(out)==r['sha']: return
        if r['stage']=='staged' and not src.exists(): self.update(r,stage='queued',sha=None)
        if not src.exists() or src.stat().st_size!=r['bytes']: self.download(r,src)
        self.update(r,stage='downloaded'); sha=self.encode(src,out)
        self.update(r,stage='staged',sha=sha,attempts=0,retry=0,error=''); src.unlink(missing_ok=True)
    def adopt(self,target,folder,names):
        for name in names:
            if safe_label(name)!=name: raise ValueError('unsafe staged name')
            p=Path(folder)/(name+'.amv'); sha=verify_amv(p); id='legacy:'+sha
            r={'id':id}; _,out=self.paths(r)
            if not out.exists():
                temp=out.with_suffix('.import.amv'); shutil.copyfile(p,temp); atomic_move(temp,out)
            if digest(out)!=sha: raise ValueError('import checksum mismatch')
            with self.db:
                self.db.execute("INSERT OR IGNORE INTO jobs(target,id,label,source,bytes,stage,sha) VALUES(?,?,?,?,?,'staged',?)",(target,id,name,'legacy',p.stat().st_size,sha))
    def transfer(self,r,mount,reserve):
        mount=self.mount(mount,r['target']); _,out=self.paths(r); expected=r['sha']
        if not expected or not out.is_file() or digest(out)!=expected:
            if r['source']!='legacy': self.update(r,stage='queued',sha=None)
            raise ValueError('staged checksum mismatch')
        video=mount/'VIDEO'; dest=r['label']+' - RUIZU.amv'; temp='.ruizu-'+hashlib.sha256((r['target']+r['id']).encode()).hexdigest()+'.part'
        fd=os.open(video,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW)
        try:
            def check():
                self.mount(mount,r['target']); before=os.fstat(fd); now=video.stat()
                if (before.st_dev,before.st_ino)!=(now.st_dev,now.st_ino): raise RuntimeError('player changed during transfer')
            check()
            if exists_at(fd,dest):
                if digest_at(fd,dest)!=expected: raise RuntimeError('destination conflict; left untouched')
            else:
                fs=os.fstatvfs(fd)
                if fs.f_bavail*fs.f_frsize<out.stat().st_size+reserve: raise RuntimeError('player space below reserve')
                if exists_at(fd,temp): os.unlink(temp,dir_fd=fd)
                with out.open('rb') as source,os.fdopen(os.open(temp,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600,dir_fd=fd),'wb') as target:
                    shutil.copyfileobj(source,target,1024*1024); target.flush(); os.fsync(target.fileno())
                check()
                if digest_at(fd,temp)!=expected: raise RuntimeError('copy checksum mismatch')
                rename_no_replace(temp,dest,fd); os.fsync(fd); check()
                if digest_at(fd,dest)!=expected: raise RuntimeError('post-copy checksum mismatch; file left for inspection')
            check(); self.update(r,stage='verified',attempts=0,retry=0,error='')
        finally: os.close(fd)
    def run(self,target,mount,max_jobs,reserve,copy):
        rows=self.db.execute("SELECT * FROM jobs WHERE target=? AND stage!='verified' AND retry<=? ORDER BY CASE stage WHEN 'staged' THEN 0 WHEN 'downloaded' THEN 1 ELSE 2 END,label",(target,time.time())).fetchall()
        processed=0
        try: self.mount(mount,target); ready=copy
        except (OSError,RuntimeError,ValueError): ready=False
        for r in rows:
            if processed>=max_jobs: break
            if r['stage']=='staged' and not ready: continue
            processed+=1
            try:
                if r['stage']!='staged': self.prepare(r)
                if copy:
                    r=self.db.execute('SELECT * FROM jobs WHERE target=? AND id=?',(target,r['id'])).fetchone()
                    try: self.mount(mount,target)
                    except (OSError,RuntimeError,ValueError): continue
                    self.transfer(r,mount,reserve)
            except Exception as exc:
                r=self.db.execute('SELECT * FROM jobs WHERE target=? AND id=?',(target,r['id'])).fetchone(); attempts=r['attempts']+1
                msg=str(exc).splitlines()[0][:300]; self.update(r,attempts=attempts,retry=time.time()+min(3600,30*2**min(attempts,7)),error=msg); print(r['label']+': '+msg,file=sys.stderr)
        return processed
    def audit(self,target,mount):
        mount=self.mount(mount,target); bad=0
        for r in self.db.execute("SELECT * FROM jobs WHERE target=? AND stage='verified'",(target,)):
            self.mount(mount,target); p=mount/'VIDEO'/(r['label']+' - RUIZU.amv')
            ok=p.is_file() and digest(p)==r['sha']; print(('OK ' if ok else 'MISSING OR CHANGED ')+r['label']); bad+=not ok
        return bad
    def status(self,target):
        for r in self.db.execute('SELECT label,stage,attempts,error FROM jobs WHERE target=? ORDER BY label',(target,)): print(f"{r['stage']:10} {r['label']}"+(f" [retry {r['attempts']}: {r['error']}]" if r['error'] else ''))
        print('catalog:',self.db.execute('SELECT count(*) FROM catalog').fetchone()[0])

def target_name(s):
    if not re.fullmatch(r"[a-z0-9_-]{1,48}",s): raise argparse.ArgumentTypeError("use a short lowercase player identifier")
    return s

def main():
    p=argparse.ArgumentParser(description=__doc__); p.add_argument('--state',default=str(Path.home()/'.ruizu-queue')); p.add_argument('--mount',default='/mnt/media_rw/0000-006F'); p.add_argument('--target',required=True,type=target_name); sub=p.add_subparsers(dest='cmd',required=True)
    q=sub.add_parser('register'); q.add_argument('--confirm',required=True)
    q=sub.add_parser('import-tsv'); q.add_argument('file'); q.add_argument('--order',choices=['legacy','pick'],default='pick')
    q=sub.add_parser('adopt'); q.add_argument('folder'); q.add_argument('names',nargs='+')
    q=sub.add_parser('run'); q.add_argument('--remote'); q.add_argument('--refresh',action='store_true'); q.add_argument('--ttl-hours',type=float,default=24); q.add_argument('--all',action='store_true'); q.add_argument('--max-jobs',type=int,default=3); q.add_argument('--copy',action='store_true'); q.add_argument('--reserve-mib',type=int,default=512); q.add_argument('--watch',action='store_true'); q.add_argument('--interval',type=int,default=60)
    sub.add_parser('status'); sub.add_parser('audit'); a=p.parse_args(); w=Worker(a.state,readonly=a.cmd=='status')
    try:
        if a.cmd=='register':
            if a.confirm!=a.target: p.error('confirm the physically connected player by name')
            w.register(a.mount,a.target)
        elif a.cmd=='import-tsv': w.import_tsv(a.file,a.order)
        elif a.cmd=='adopt': w.adopt(a.target,a.folder,a.names)
        elif a.cmd=='status': w.status(a.target)
        elif a.cmd=='audit':
            if w.audit(a.target,a.mount): sys.exit(1)
        elif a.cmd=='run':
            if min(a.max_jobs,a.reserve_mib,a.interval,a.ttl_hours)<=0: p.error('limits must be positive')
            next_scan=0
            while True:
                if a.remote and time.time()>=next_scan:
                    try: w.discover(a.remote,a.ttl_hours*3600,a.refresh)
                    except Exception as e:
                        next_scan=time.time()+300; print('Discovery failed; using cached catalog:',str(e).splitlines()[0],file=sys.stderr)
                a.refresh=False; w.enqueue(a.target,a.all); w.run(a.target,a.mount,a.max_jobs,a.reserve_mib*1024**2,a.copy)
                if not a.watch: break
                time.sleep(a.interval)
            w.status(a.target)
    finally: w.close()

if __name__=='__main__': main()
