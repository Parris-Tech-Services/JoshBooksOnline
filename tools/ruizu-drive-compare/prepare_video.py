#!/usr/bin/env python3
"""Convert a *local* video into a RUIZU test clip or (with --full) full movie.

Video format support depends on physical RUIZU model. No player writes, DRM
bypass, or cloud downloads; output stays on the phone until manually tested.
Requires ffmpeg and ffprobe (Termux: pkg install ffmpeg).
"""
import argparse
import json
from pathlib import Path
import shutil
import subprocess

PROFILES={
    'amv-160x128': (160,128,'amv'),
    'amv-128x160': (128,160,'amv'),
    'amv-128x128': (128,128,'amv'),
    'mp4-320x240': (320,240,'mp4'),
}

def outdir():
    d=Path.home()/'storage/downloads'
    return d if d.is_dir() else Path.home()

def command(inp, out, profile, seconds, sample):
    w,h,fmt=PROFILES[profile]
    cmd=['ffmpeg','-hide_banner','-loglevel','error','-nostdin','-n']
    if sample:
        cmd+=['-f','lavfi','-i',f'testsrc2=size={w}x{h}:rate=15',
              '-f','lavfi','-i','sine=frequency=440:sample_rate=22050']
    else:
        cmd+=['-i',str(inp)]
    cmd+=['-map','0:v:0','-map',('1:a:0' if sample else '0:a:0')]
    cmd+=['-t',str(seconds)] if seconds is not None else []
    cmd+=['-vf',f'scale={w}:{h}:force_original_aspect_ratio=decrease,'
                 f'pad={w}:{h}:(ow-iw)/2:(oh-ih)/2:color=black']
    if fmt=='amv':
        cmd+=['-r','15','-c:v','amv','-c:a','adpcm_ima_amv',
              '-block_size','1470','-ar','22050','-ac','1']
    else:
        cmd+=['-r','24','-c:v','libx264','-profile:v','baseline',
              '-level:v','3.0','-pix_fmt','yuv420p','-crf','28',
              '-c:a','aac','-b:a','64k','-ar','44100','-ac','2','-movflags','+faststart']
    return cmd+[str(out)]

def process(args):
    for exe in ('ffmpeg','ffprobe'):
        if shutil.which(exe) is None:
            raise ValueError(f'{exe} not available. In Termux: pkg install ffmpeg')
    if args.sample and args.input:
        raise ValueError('Choose --sample OR --input, not both')
    if not args.sample and not args.input:
        raise ValueError('Supply --sample or --input /path/to/movie.mp4')
    if args.full and args.sample:
        raise ValueError('--full requires --input (not a synthetic sample)')
    inp=Path(args.input).expanduser() if args.input else None
    if inp and (not inp.is_file() or inp.is_symlink()):
        raise ValueError('Source not found or not a regular file: '+str(inp))
    w,h,fmt=PROFILES[args.profile]
    basename='RUIZU-video-test-'+args.profile if args.sample else inp.stem
    if not args.full and not args.sample:
        basename+='-20s-preview'
    # The output may be the parent directory or an explicit file path.
    dest=Path(args.output).expanduser() if args.output else outdir()/(basename+'.'+fmt)
    if dest.suffix.lower()!=('.'+fmt):
        dest=dest/(basename+'.'+fmt)
    resolved=dest.resolve()
    if str(resolved).startswith('/mnt/media_rw/') or str(resolved).startswith('/storage/'):
        raise ValueError('Direct writes to a mounted player are refused; use phone Downloads')
    if dest.exists():
        raise ValueError('Output already exists; leaving it intact: '+str(dest))
    dest.parent.mkdir(parents=True,exist_ok=True)
    seconds=None if args.full else (8 if args.sample else 20)
    result=subprocess.run(command(inp,dest,args.profile,seconds,args.sample),capture_output=True,text=True)
    if result.returncode:
        dest.unlink(missing_ok=True)
        raise ValueError('FFmpeg failed: '+result.stderr[-600:].strip())
    probe=subprocess.run(['ffprobe','-v','error','-show_streams','-of','json',str(dest)],
                         capture_output=True,text=True)
    if probe.returncode:
        raise ValueError('FFprobe could not verify conversion: '+probe.stderr[-250:])
    streams=json.loads(probe.stdout)['streams']
    videos=[x for x in streams if x.get('codec_type')=='video']
    audios=[x for x in streams if x.get('codec_type')=='audio']
    if len(videos)!=1 or not audios or (videos[0].get('width'),videos[0].get('height'))!=(w,h):
        raise ValueError('Output verification failed: wrong streams or dimensions')
    print('Verified output:',dest)
    print('Size:',dest.stat().st_size,'bytes. Profile:',args.profile)
    print('This verifies the file, NOT compatibility with your RUIZU model.')
    print('Test on the actual player before doing any full-movie conversion.')

def main():
    ap=argparse.ArgumentParser(description=__doc__)
    mx=ap.add_mutually_exclusive_group(required=True)
    mx.add_argument('--sample',action='store_true',help='Create an eight-second non-copyright test pattern')
    mx.add_argument('--input',help='Local movie file already downloaded to phone')
    ap.add_argument('--profile',choices=tuple(PROFILES),default='amv-160x128')
    ap.add_argument('--full',action='store_true',help='Convert entire local movie after test playback succeeds')
    ap.add_argument('--output',help='Output file or directory (default: phone Downloads)')
    args=ap.parse_args()
    try:process(args)
    except ValueError as exc:ap.error(str(exc))
if __name__=='__main__':main()