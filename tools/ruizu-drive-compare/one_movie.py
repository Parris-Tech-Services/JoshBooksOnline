#!/usr/bin/env python3
"""Prepare one locally owned movie for RUIZU X02-style 160x128 AMV playback.
Never touches any existing file. No external service or Drive credentials.
"""
import argparse, hashlib, os, shutil, subprocess, sys
from pathlib import Path

NAME = 'Wreck It Ralph (2012).mp4'
OUTPUT = 'Wreck It Ralph (2012) - RUIZU 160x128.amv'
DRIVE_URL = 'https://drive.google.com/file/d/1KZTV7ewwysQvulofmFTc9Etw9Yf2g7Z-/view'
PLAYER = Path('/mnt/media_rw/0000-006F')

def sha256(p):
    h = hashlib.sha256()
    with p.open('rb') as f:
        for b in iter(lambda:f.read(1024*1024),b''):h.update(b)
    return h.hexdigest()

def run(args):
    downloads = Path.home()/'storage/downloads'
    if not downloads.is_dir():
        raise RuntimeError('Termux shared Downloads unavailable. Run termux-setup-storage once, then rerun.')
    src = Path(args.source).expanduser() if args.source else downloads/NAME
    if not src.is_file() or src.is_symlink():
        raise RuntimeError(f'Movie not downloaded yet: {src}\nOpen {DRIVE_URL} in Google Drive, tap Download, then rerun. No files changed.')
    if not shutil.which('ffmpeg') or not shutil.which('ffprobe'):
        raise RuntimeError('Missing FFmpeg. Run: pkg install ffmpeg')
    if subprocess.run(['ffprobe','-v','error','-select_streams','v:0','-show_entries','stream=codec_type','-of','csv=p=0',str(src)],capture_output=True,text=True).stdout.strip()!='video':
        raise RuntimeError('Source not recognised as a playable video. No conversion attempted.')
    output = downloads/OUTPUT
    if output.exists():
        print(f'EXISTS: {output} (not overwritten; validating before player copy)')
    else:
        tmp = downloads/(output.stem + '.incomplete.amv')
        if tmp.exists():
            raise RuntimeError(f'Unfinished conversion exists at {tmp}. Not touching it.')
        cmd = ['ffmpeg','-hide_banner','-nostdin','-loglevel','error','-n','-i',str(src),'-map','0:v:0','-map','0:a:0','-vf', 'scale=160:128:force_original_aspect_ratio=decrease,pad=160:128:(ow-iw)/2:(oh-ih)/2:black','-r','15','-c:v','amv','-c:a','adpcm_ima_amv','-block_size','1470','-ar','22050','-ac','1','-threads','2',str(tmp)]
        print('Converting ONE film to 160x128 AMV. Source stays untouched.',flush=True)
        result=subprocess.run(cmd)
        if result.returncode:
            tmp.unlink(missing_ok=True)
            raise RuntimeError('FFmpeg conversion failed. Original preserved.')
        verify=subprocess.run(['ffprobe','-v','error','-show_entries','stream=codec_name,width,height,codec_type','-of','default=noprint_wrappers=1',str(tmp)],capture_output=True,text=True)
        if verify.returncode or 'codec_name=amv' not in verify.stdout or 'width=160' not in verify.stdout or 'height=128' not in verify.stdout or 'codec_name=adpcm_ima_amv' not in verify.stdout:
            tmp.unlink(missing_ok=True)
            raise RuntimeError('Video/audio codec verification failed; no file published.')
        tmp.rename(output)
        print('VERIFIED CONVERSION:',output,round(output.stat().st_size/1e6,1),'MB',flush=True)
    checked=subprocess.run(['ffprobe','-v','error','-show_entries','stream=codec_name,width,height,codec_type','-of','default=noprint_wrappers=1',str(output)],capture_output=True,text=True)
    if checked.returncode or 'codec_name=amv' not in checked.stdout or 'codec_name=adpcm_ima_amv' not in checked.stdout or 'width=160' not in checked.stdout or 'height=128' not in checked.stdout:
        raise RuntimeError('Existing or newly converted AMV has incorrect codec/resolution; no player copy attempted')
    if args.no_copy:
        print('Converted film saved locally; no player copy requested.')
        return
    target_dir = PLAYER/'VIDEO'
    if not PLAYER.is_dir() or not target_dir.is_dir() or not os.access(target_dir,os.W_OK):
        print(f'PLAYER NOT WRITABLE at {target_dir}. Film remains in Downloads. This does not verify physical playback.')
        return
    dst = target_dir/output.name
    try:
        fd=os.open(dst,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o666)
    except FileExistsError:
        print(f'PLAYER FILE ALREADY EXISTS (not overwritten): {dst}')
        return
    try:
        with os.fdopen(fd,'wb') as out, output.open('rb') as inp:
            shutil.copyfileobj(inp,out,1024*1024)
            out.flush();os.fsync(out.fileno())
        if output.stat().st_size!=dst.stat().st_size or sha256(output)!=sha256(dst):
            raise RuntimeError('Copied movie hash/size did not match original')
    except Exception:
        dst.unlink(missing_ok=True) # only file this invocation created via O_EXCL
        raise
    print('VERIFIED COPY:',dst,flush=True)
    print('Movie is present on the RUIZU with matching SHA-256. Actual video playback still needs a player test.')

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--source',help='Path to downloaded MP4 (default: Downloads/Wreck It Ralph (2012).mp4)')
    p.add_argument('--no-copy',action='store_true',help='Convert only; do not copy to player')
    a=p.parse_args()
    try:run(a)
    except (RuntimeError, OSError) as e:
        print('ERROR:',e,file=sys.stderr);sys.exit(1)