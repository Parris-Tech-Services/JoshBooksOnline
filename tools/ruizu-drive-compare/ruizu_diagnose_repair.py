#!/usr/bin/env python3
"""Read-only RUIZU forensic diagnosis + create-only MJPEG/AVI compatibility clip.

The default mode NEVER writes to the RUIZU. It does not alter or remove media.
Only --copy-test or --restore-marconi can create files on the player; both
are explicit, no-overwrite and hash-verified. No network or Google credentials.
"""
import argparse
import datetime as dt
from fractions import Fraction
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import sys

ROOT = Path('/mnt/media_rw/0000-006F')
VENDOR = 'All DX.avi'
AUDIO_EXTS = {'.mp3', '.wav', '.m4a', '.aac', '.wma', '.flac', '.ape', '.ogg'}
VIDEO_EXTS = {'.avi', '.mp4', '.mkv', '.mov', '.amv'}


def sha256(path):
    h = hashlib.sha256()
    with path.open('rb') as f:
        for b in iter(lambda: f.read(1024 * 1024), b''):
            h.update(b)
    return h.hexdigest()


def safe_files(folder, recursive=False, allow_folder_symlink=False):
    # Termux ~/storage/downloads is a symlink to Android's shared Downloads.
    # Only callers explicitly reading phone Downloads may follow that directory;
    # player/media scanning still refuses symlink directories and symlink files.
    if not folder.is_dir() or (folder.is_symlink() and not allow_folder_symlink):
        return []
    try:
        if recursive:
            return [p for p in folder.rglob('*') if p.is_file() and not p.is_symlink()]
        return [p for p in folder.iterdir() if p.is_file() and not p.is_symlink()]
    except OSError:
        return []


def ffprobe(path, timeout=55):
    if not shutil.which('ffprobe'):
        return {'error': 'ffprobe not installed (pkg install ffmpeg)'}
    cmd = ['ffprobe', '-v', 'error', '-show_format', '-show_streams', '-of', 'json', str(path)]
    try:
        p = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
        if p.returncode:
            return {'error': (p.stderr or 'ffprobe failed').strip()[-400:]}
        obj = json.loads(p.stdout)
        fields = ('codec_type', 'codec_name', 'codec_tag_string', 'width', 'height',
                  'pix_fmt', 'r_frame_rate', 'avg_frame_rate', 'sample_rate',
                  'channels', 'block_align', 'duration', 'nb_frames')
        return {'size_bytes': path.stat().st_size,
                'format': {k: obj.get('format', {}).get(k) for k in ('format_name', 'duration', 'size')},
                'streams': [{k: st[k] for k in fields if k in st}
                            for st in obj.get('streams', [])]}
    except (ValueError, OSError, subprocess.TimeoutExpired) as e:
        return {'error': str(e)}


def streams(probe):
    a = probe.get('streams', [])
    return next((s for s in a if s.get('codec_type') == 'video'), {}), next(
        (s for s in a if s.get('codec_type') == 'audio'), {})


def avi_structure(path):
    """Jump over huge AVI movi data; inspect RIFF/LIST and index headers without loading film."""
    result = {'size_bytes': path.stat().st_size, 'chunks': {}, 'riff_types': [],
              'avih': [], 'issues': []}
    size = result['size_bytes']
    max_nodes = 30000
    nodes = 0
    try:
        with path.open('rb') as f:
            def walk(start, stop, depth=0):
                nonlocal nodes
                pos = start
                if depth > 8:
                    return
                while pos + 8 <= stop and nodes < max_nodes:
                    nodes += 1
                    f.seek(pos)
                    hdr = f.read(8)
                    if len(hdr) != 8:
                        break
                    tag, length = struct.unpack('<4sI', hdr)
                    name = tag.decode('ascii', 'replace')
                    end = pos + 8 + length
                    if length == 0xffffffff:
                        end = min(size, stop)
                    if end > stop or end <= pos:
                        result['issues'].append(f'Invalid {name} chunk length at offset {pos}')
                        break
                    if name in ('idx1', 'indx') or re.fullmatch(r'ix[0-9a-fA-F]{2}', name):
                        result['chunks'][name] = result['chunks'].get(name, 0) + 1
                    if name == 'avih' and length >= 40:
                        f.seek(pos + 8)
                        block = f.read(40)
                        if len(block) == 40:
                            words = struct.unpack('<10I', block)
                            result['avih'].append({'microseconds_per_frame': words[0],
                                'flags_hex': hex(words[3]), 'has_index_flag': bool(words[3] & 0x10),
                                'total_frames': words[4], 'width': words[8], 'height': words[9]})
                    if name in ('LIST', 'RIFF') and length >= 4:
                        f.seek(pos + 8)
                        subtype = f.read(4).decode('ascii', 'replace')
                        if name == 'RIFF':
                            result['riff_types'].append(subtype)
                        if subtype != 'movi':
                            walk(pos + 12, end, depth + 1)
                    pos = end + (length & 1)
            walk(0, size)
    except OSError as e:
        result['issues'].append(str(e))
    result['nodes_scanned'] = nodes
    result['has_idx1'] = result['chunks'].get('idx1', 0) > 0
    result['has_opendml_index'] = any(k.startswith('ix') or k == 'indx' for k in result['chunks'])
    return result


def describe_video(path):
    d = {'filename': path.name, 'bytes': path.stat().st_size, 'probe': ffprobe(path)}
    if path.suffix.lower() == '.avi':
        d['riff'] = avi_structure(path)
    return d


def gather_marconi(root, home):
    rows = []
    for f in safe_files(root, recursive=True):
        if 'marconi' in f.name.casefold() or f.name.casefold() == 'p.mp3':
            try:
                rows.append({'relative_path': str(f.relative_to(root)), 'size': f.stat().st_size,
                             'sha256': sha256(f)})
            except OSError as e:
                rows.append({'relative_path': str(f.relative_to(root)), 'error': str(e)})
    log = home / '.ruizu-investigate'
    records = []
    if log.is_dir():
        for f in sorted(safe_files(log, recursive=True))[:100]:
            if f.suffix.lower() not in ('.tsv', '.txt', '.csv', '.json', '.log'):
                continue
            try:
                if f.stat().st_size > 8_000_000:
                    continue
                with f.open('r', encoding='utf-8', errors='replace') as fp:
                    for i, line in enumerate(fp, 1):
                        if i > 150000:
                            break
                        if 'marconi' in line.casefold() or 'p.mp3' in line.casefold():
                            records.append({'log': f.name, 'line': i, 'entry': line.strip()[:350]})
                            if len(records) >= 75:
                                break
            except OSError:
                pass
            if len(records) >= 75:
                break
    src = root / '__1' / 'Marconi Union - Weightless (Official Extended Version).mp3'
    dst = root / '_Duplicates & partials' / 'marconi-union-weightless.mp3'
    return {'matching_files': rows, 'move_log_matches': records,
            'original_named_copy_exists': src.is_file(),
            'missing_holding_destination': not dst.exists(),
            'notice': 'Only files on disk can be hash-confirmed. Do not infer a deleted original from a move log alone.'}


def fps_from_vendor(video):
    for name in ('r_frame_rate', 'avg_frame_rate'):
        val = video.get(name, '')
        try:
            frac = Fraction(val)
            if 5 <= frac <= 30:
                return str(frac)
        except (ValueError, ZeroDivisionError):
            pass
    return '20'


def candidate_sources(root, downloads):
    candidates = []
    for folder in (downloads, root / 'VIDEO'):
        for p in safe_files(folder, allow_folder_symlink=(folder == downloads)):
            if p.suffix.lower() not in VIDEO_EXTS or p.name.casefold() == VENDOR.casefold():
                continue
            rank = (0 if ('wreck' in p.name.casefold() or 'ralph' in p.name.casefold()) else
                    1 if 'player-test' in p.name.casefold() else 2)
            candidates.append((rank, -p.stat().st_size, p))
    return [z[2] for z in sorted(candidates, key=lambda z: (z[0], z[1], z[2].name))]


def available_name(folder, base):
    folder.mkdir(parents=True, exist_ok=True)
    stamp = dt.datetime.now().strftime('%Y%m%d-%H%M%S')
    for suffix in range(100):
        name = base.format(stamp=stamp, n=f'-{suffix}' if suffix else '')
        p = folder / name
        if not p.exists() and not p.is_symlink():
            return p
    raise RuntimeError('No available destination name; refusing overwrite')


def movie_ffmpeg_args(source, output, sample_probe, duration):
    v, a = streams(sample_probe)
    width, height = int(v.get('width', 208)), int(v.get('height', 176))
    if width > 640 or height > 480 or width < 80 or height < 64:
        raise RuntimeError('Unexpected vendor dimensions; refusing auto-detected settings')
    fps = fps_from_vendor(v)
    rate = str(a.get('sample_rate', '22050'))
    if int(rate) not in (11025, 16000, 22050, 32000, 44100):
        rate = '22050'
    block = a.get('block_align')
    # The FFmpeg ADPCM encoder needs a block size divisible by 4, within 32..8192.
    block = int(block) if str(block).isdigit() else 1024
    if not (32 <= block <= 8192) or block % 4:
        block = 1024
    synthetic = source is None
    audio_present = False
    if not synthetic:
        source_probe = ffprobe(source)
        sv, sa = streams(source_probe)
        if sv.get('codec_type') != 'video':
            raise RuntimeError(f'No video stream in {source.name}')
        audio_present = sa.get('codec_type') == 'audio'
    cmd = ['ffmpeg', '-hide_banner', '-loglevel', 'error', '-nostdin', '-n']
    if synthetic:
        cmd += ['-f', 'lavfi', '-i', f'testsrc2=size={width}x{height}:rate={fps}',
                '-f', 'lavfi', '-i', f'sine=frequency=440:sample_rate={rate}']
        audio_index = '1:a:0'
    else:
        cmd += ['-i', str(source)]
        if not audio_present:
            cmd += ['-f', 'lavfi', '-i', f'anullsrc=channel_layout=mono:sample_rate={rate}']
        audio_index = '0:a:0' if audio_present else '1:a:0'
    filters = (f'scale={width}:{height}:force_original_aspect_ratio=decrease,'
               f'pad={width}:{height}:(ow-iw)/2:(oh-ih)/2:black,format=yuvj420p')
    cmd += ['-map', '0:v:0', '-map', audio_index,
            '-vf', filters, '-r', fps,
            '-c:v', 'mjpeg', '-q:v', '8', '-pix_fmt', 'yuvj420p',
            '-c:a', 'adpcm_ima_wav', '-ar', rate, '-ac', '1', '-block_size', str(block)]
    if duration is not None:
        cmd += ['-t', str(duration)]
    cmd += ['-f', 'avi', str(output)]
    return cmd, {'target_width': width, 'target_height': height,
                 'target_fps': fps, 'target_audio_hz': rate, 'target_audio_block_size': block,
                 'source': str(source) if source else 'synthetic test pattern'}


def validate_movie(output, sample_probe, strict_index=True):
    actual = describe_video(output)
    sample_v, sample_a = streams(sample_probe)
    v, a = streams(actual['probe'])
    problems = []
    for key, expected in (('codec_name', 'mjpeg'), ('width', sample_v.get('width', 208)),
                          ('height', sample_v.get('height', 176))):
        if v.get(key) != expected:
            problems.append(f'Video {key}: wanted {expected}, got {v.get(key)}')
    if a.get('codec_name') != 'adpcm_ima_wav':
        problems.append('Audio codec mismatch: expected adpcm_ima_wav')
    if str(a.get('sample_rate')) != str(sample_a.get('sample_rate', '22050')):
        problems.append('Audio sample rate differs from vendor')
    if actual.get('riff', {}).get('issues'):
        problems.append('AVI RIFF chunk scanner reported invalid chunk lengths')
    sample_index = sample_probe.get('riff', {}).get('has_idx1')
    if strict_index and sample_index and not actual['riff']['has_idx1']:
        problems.append('Vendor uses legacy idx1, new test AVI has no legacy idx1')
    return actual, problems


def create_preview(root, downloads, sample, desired_source=None, full=False):
    if not shutil.which('ffmpeg') or not shutil.which('ffprobe'):
        raise RuntimeError('FFmpeg/FFprobe missing. In Termux: pkg install ffmpeg')
    src = Path(desired_source).expanduser() if desired_source else None
    if src and (not src.is_file() or src.is_symlink()):
        raise RuntimeError('Specified movie does not exist or is symlink')
    if not src:
        for maybe in candidate_sources(root, downloads):
            if streams(ffprobe(maybe))[0].get('codec_type') == 'video':
                src = maybe
                break
    vendor_probe = describe_video(sample) if sample and sample.is_file() else None
    if not vendor_probe:
        raise RuntimeError('Vendor VIDEO/All DX.avi unavailable; cannot match the actual player sample')
    if 'error' in vendor_probe['probe']:
        raise RuntimeError('Cannot inspect vendor sample: ' + vendor_probe['probe']['error'])
    # Do not guess codecs from a marketing claim; require the vendor sample.
    sv, sa = streams(vendor_probe['probe'])
    if sv.get('codec_name') != 'mjpeg' or sa.get('codec_name') != 'adpcm_ima_wav':
        raise RuntimeError('Vendor sample codecs differ from expected MJPEG/IMA WAV; manual inspection required')
    base = 'RUIZU-AVI-FIX-{stamp}{n}.avi' if not full else 'RUIZU-AVI-FULL-{stamp}{n}.avi'
    output = available_name(downloads, base)
    # .part.avi is in downloads only. No write to player during conversion.
    temp = output.with_name(output.stem + '.part.avi')
    if temp.exists():
        raise RuntimeError(f'Temporary output already exists: {temp}')
    cmd, settings = movie_ffmpeg_args(src, temp, vendor_probe['probe'], None if full else 12)
    # The sample's RIFF data is attached for index comparison.
    vendor_probe['probe']['riff'] = vendor_probe['riff']
    print(f"Building {'FULL FILM' if full else '12-second test'} from {settings['source']}\nOutput: {output}", flush=True)
    try:
        p = subprocess.run(cmd, timeout=(5 * 60 * 60 if full else 7 * 60), capture_output=True, text=True)
        if p.returncode:
            raise RuntimeError('FFmpeg error: ' + p.stderr[-1200:])
        new, problems = validate_movie(temp, vendor_probe['probe'])
        if problems:
            raise RuntimeError('Generated AVI failed checks: ' + '; '.join(problems))
        if output.exists():
            raise RuntimeError(f'Output name was taken during encode: {output}')
        temp.rename(output)
        return output, {'conversion_settings': settings, 'verified_file': describe_video(output)}
    finally:
        if temp.exists():
            temp.unlink()  # only newly created partial file


def copy_create_only(source, dst):
    if dst.exists() or dst.is_symlink():
        raise FileExistsError(f'Already exists, not overwritten: {dst}')
    if not dst.parent.is_dir() or dst.parent.is_symlink():
        raise RuntimeError('Player VIDEO directory missing or symlinked')
    if source.stat().st_size > shutil.disk_usage(dst.parent).free - 100 * 1024 * 1024:
        raise RuntimeError('Insufficient free space (100MB safety margin)')
    fd = os.open(dst, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o666)
    try:
        with os.fdopen(fd, 'wb') as target, source.open('rb') as inp:
            shutil.copyfileobj(inp, target, 1024 * 1024)
            target.flush()
            os.fsync(target.fileno())
        if dst.stat().st_size != source.stat().st_size or sha256(dst) != sha256(source):
            raise RuntimeError('Copy did not verify byte-for-byte')
    except Exception:
        dst.unlink(missing_ok=True)  # only the file created by O_EXCL above
        raise
    return str(dst)


def restore_marconi(root, report):
    target = root / '_Duplicates & partials' / 'marconi-union-weightless.mp3'
    if target.exists():
        raise RuntimeError('Destination already exists; no restore needed')
    originals = [root / x['relative_path'] for x in report['matching_files']
                 if x.get('sha256') and 'marconi' in x['relative_path'].casefold()]
    if len(originals) < 2:
        raise RuntimeError('Need at least two matching retained copies to justify reconstructing duplicate')
    hashes = {sha256(x) for x in originals}
    if len(hashes) != 1:
        raise RuntimeError('Existing Marconi recordings differ; refused to restore assumed duplicate')
    if not report['move_log_matches']:
        raise RuntimeError('No matching move log evidence; refused to invent a missing copy')
    if not target.parent.is_dir():
        raise RuntimeError('Existing duplicates folder missing; will not create an unexpected player folder')
    return copy_create_only(originals[0], target)


def diagnostic_report(root, home, downloads):
    doc = {'created_at_local': dt.datetime.now().astimezone().isoformat(),
           'player_root': str(root), 'player_connected': root.is_dir(),
           'actions': ['Read-only scan; no player writes in default mode'],
           'notes': ['Matching sample AVI stream settings does not prove physical playback.',
                     'Changing or recreating duplicate audio copies is never automatic.']}
    if root.is_dir():
        disk = shutil.disk_usage(root)
        doc['disk'] = {'total': disk.total, 'free': disk.free, 'used': disk.used}
        sample = root / 'VIDEO' / VENDOR
        doc['vendor_video'] = describe_video(sample) if sample.is_file() else {'error': 'Vendor sample unavailable'}
        vids = [f for f in safe_files(root / 'VIDEO') if f.suffix.lower() in VIDEO_EXTS]
        doc['player_videos'] = [describe_video(p) for p in sorted(vids, key=lambda p: p.name)[:30]]
        doc['marconi'] = gather_marconi(root, home)
        by_name = {p.name: p for p in vids}
        vendor = doc['vendor_video']
        if vendor.get('riff'):
            observed = {}
            for file in doc['player_videos']:
                if file['filename'] == VENDOR or file.get('riff') is None:
                    continue
                s_v, s_a = streams(vendor['probe'])
                t_v, t_a = streams(file['probe'])
                observed[file['filename']] = {
                    'matches_vendor_video_codec': s_v.get('codec_name') == t_v.get('codec_name'),
                    'matches_vendor_dimensions': (s_v.get('width'),s_v.get('height')) == (t_v.get('width'),t_v.get('height')),
                    'matches_vendor_audio_codec': s_a.get('codec_name') == t_a.get('codec_name'),
                    'vendor_has_idx1': vendor['riff']['has_idx1'],
                    'candidate_has_idx1': file['riff']['has_idx1'],
                    'candidate_has_opendml_index': file['riff']['has_opendml_index']}
            doc['video_comparison'] = observed
    else:
        doc['warnings'] = ['Player not mounted: no access to sample AVI or Marconi files.']
    doc['phone_videos'] = [p.name for p in safe_files(downloads, allow_folder_symlink=True) if p.suffix.lower() in VIDEO_EXTS][:30]
    return doc


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--root', type=Path, default=ROOT, help='Player mount point')
    ap.add_argument('--movie', type=Path, help='Input movie (default: find Wreck-It Ralph / PLAYER-TEST)')
    ap.add_argument('--diagnose-only', action='store_true', help='Do not generate a local test AVI')
    ap.add_argument('--copy-test', action='store_true', help='Explicitly copy verified 12-second AVI to VIDEO/ (create-only)')
    ap.add_argument('--full', action='store_true', help='Encode entire input locally, no player copy; requires --movie')
    ap.add_argument('--restore-marconi', action='store_true', help='Explicitly restore one identical duplicate if log and two copies prove it')
    ap.add_argument('--downloads', type=Path, help='Phone Downloads destination override (for testing)')
    a = ap.parse_args(argv)
    if a.full and (not a.movie or a.copy_test):
        ap.error('--full requires --movie and cannot be combined with --copy-test')
    home = Path.home()
    downloads = (a.downloads or home / 'storage' / 'downloads').expanduser()
    if not downloads.is_dir():
        downloads = home
    report = diagnostic_report(a.root, home, downloads)
    video = report.get('vendor_video', {})
    print('RUIZU connected:', report['player_connected'])
    if video.get('probe'):
        v, au = streams(video['probe'])
        print('VENDOR:', video['filename'], '|', v.get('codec_name'), f"{v.get('width')}x{v.get('height')}",
              '|', au.get('codec_name'), au.get('sample_rate'), 'Hz')
        print('VENDOR RIFF:', video.get('riff', {}).get('chunks', {}))
    print('AVI COMPARISONS:', json.dumps(report.get('video_comparison', {}), ensure_ascii=False))
    m = report.get('marconi', {})
    print('MARCONI COPIES:', len([x for x in m.get('matching_files', []) if 'marconi' in x['relative_path'].casefold()]))
    for row in m.get('matching_files', []):
        print(' -', row['relative_path'], row.get('sha256','')[0:16], row.get('size', ''))
    if a.restore_marconi:
        report.setdefault('actions', []).append('Explicit requested identical Marconi restoration attempted')
        report['restored_duplicate'] = restore_marconi(a.root, m)
        print('RESTORED:', report['restored_duplicate'])
    if not a.diagnose_only:
        try:
            src, made = create_preview(a.root, downloads, a.root / 'VIDEO' / VENDOR,
                                       desired_source=a.movie, full=a.full)
            report['new_video'] = {'local_output': str(src), **made}
            print('VERIFIED LOCAL OUTPUT:', src, src.stat().st_size, 'bytes')
            if a.copy_test:
                dest = a.root / 'VIDEO' / src.name
                report['player_copy'] = copy_create_only(src, dest)
                print('PLAYER COPY HASH VERIFIED:', dest)
        except Exception as e:
            report['conversion_error'] = str(e)
            print('CONVERSION NOT COMPLETED:', e)
    out = available_name(downloads, 'RUIZU-DIAGNOSIS-{stamp}{n}.json')
    with out.open('x', encoding='utf-8') as f:
        json.dump(report, f, indent=2, ensure_ascii=False)
    print('REPORT:', out)
    print('No existing player files were modified or deleted.')
    if 'conversion_error' in report:
        return 2
    return 0

if __name__ == '__main__':
    try:
        sys.exit(main())
    except (OSError, RuntimeError, ValueError, subprocess.SubprocessError) as e:
        print('ERROR:', e, file=sys.stderr)
        sys.exit(1)