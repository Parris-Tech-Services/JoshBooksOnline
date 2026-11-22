#!/usr/bin/env python3
"""RUIZU media sorter. PREVIEW by default; --apply moves only safe candidates.
Does not delete audio, change .LIB, touch playlists, or overwrite destinations.
"""
import argparse
import csv
import os
from collections import Counter, defaultdict
from pathlib import Path

AUDIO={'.mp3','.m4a','.aac','.wav','.flac','.ogg','.wma','.ape','.aif','.aiff','.opus','.m4b'}
MUSIC={'bridge to terabithia soundtrack','ice princess','james bay','mamma mia (the movie)','mumford & sons','the fault in our stars','the hunger games_ catching fire [deluxe]','lord of the rings- the fellowship of the ring','shrek 2','ben folds','ben harper','brooke fraser','missy higgins','sia','birdy','soundtracks disney'}

def files(root):
    for base, dirs, names in os.walk(root,followlinks=False):
        dirs[:]=sorted(d for d in dirs if not (Path(base)/d).is_symlink())
        for n in sorted(names):
            p=Path(base)/n
            if p.is_file() and not p.is_symlink():yield p.relative_to(root)

def playlist_links(root,paths):
    protected=set()
    for p in paths:
        if p.suffix.lower() not in ('.m3u','.m3u8'):continue
        f=root/p
        try:
            if f.stat().st_size>2_000_000:continue
            lines=f.read_text(encoding='utf-8-sig',errors='replace').splitlines()
        except OSError:continue
        for line in lines:
            line=line.strip().replace('\\','/')
            if not line or line.startswith('#') or '://' in line or ':' in line:continue
            for base in (p.parent,Path('.')):
                parts=[]
                for el in (base / line.lstrip('/')).parts:
                    if el=='.':continue
                    if el=='..':
                        if parts:parts.pop()
                        else:break
                    else:parts.append(el)
                q=Path(*parts)
                if (root/q).is_file():protected.add(q)
    return protected

def destination(rel):
    parts=rel.parts
    if len(parts)==1:return Path('Music/Unsorted')/rel.name,'Loose file in player root'
    top=parts[0]
    if top.casefold() in ('music','podcasts','audiobooks'):return None,'Already organised'
    if top!='__1':return None,'Unknown folder - review'
    if len(parts)<3:return None,'Legacy loose audio - review'
    category=parts[1];name=category.casefold();tail=Path(*parts[2:])
    if name in ('_outlander','outlander'):return Path('Audiobooks/Outlander')/tail,'Outlander audiobook'
    if name in ('podcasts','cornerstone','cornerstone community'):return Path('Podcasts')/category.strip('_')/tail,'Podcast archive'
    if name in MUSIC or name.startswith('triple j hottest 100 of 2013'):
        return Path('Music')/category/tail,'Recognised artist or soundtrack'
    return None,'Unknown legacy folder - review'

def plan(root):
    allfiles=list(files(root));protected=playlist_links(root,allfiles)
    rows=[]
    for rel in allfiles:
        if rel.suffix.lower() not in AUDIO:continue
        dest,reason=destination(rel)
        if dest is None:status='LEAVE' if reason=='Already organised' else 'REVIEW'
        elif rel in protected:status='PLAYLIST_PROTECTED';reason='Existing playlist references this file'
        elif (root/dest).exists():status='COLLISION';reason='Destination exists - not overwritten'
        else:status='READY'
        rows.append(dict(status=status,current_path=rel.as_posix(),proposed_path=dest.as_posix() if dest else '',size_bytes=(root/rel).stat().st_size,reason=reason))
    return rows

def write_csv(path,rows):
    path.parent.mkdir(parents=True,exist_ok=True)
    with path.open('w',newline='',encoding='utf-8-sig') as f:
        writer=csv.DictWriter(f,fieldnames=('status','current_path','proposed_path','size_bytes','reason'))
        writer.writeheader()
        for row in rows:
            writer.writerow({k:("'"+v if isinstance(v,str) and v[:1] in ('=','+','-','@','\t') else v) for k,v in row.items()})

def apply(root,rows):
    lines=[]
    for row in rows:
        if row['status']!='READY':continue
        a=root/row['current_path'];b=root/row['proposed_path']
        if a.is_symlink() or not a.is_file():lines.append('SKIP unavailable: '+row['current_path']);continue
        # Never traverse an intervening symlink.
        components=Path(row['proposed_path']).parts
        if any((root/Path(*components[:i])).is_symlink() for i in range(1,len(components))):
            lines.append('SKIP symlink: '+row['current_path']);continue
        if b.exists():lines.append('SKIP collision: '+row['current_path']);continue
        try:
            b.parent.mkdir(parents=True,exist_ok=True)
            if b.exists():lines.append('SKIP collision: '+row['current_path']);continue
            a.rename(b) # Same FAT32 filesystem; original data not rewritten.
            lines.append('MOVED: '+row['current_path']+' -> '+row['proposed_path'])
        except OSError as e:lines.append('ERROR '+row['current_path']+': '+str(e))
    return lines

def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--root',type=Path,default=Path('/mnt/media_rw/0000-006F'))
    ap.add_argument('--report',type=Path,default=Path.home()/'storage/downloads/RUIZU-sort-plan.csv')
    ap.add_argument('--apply',action='store_true',help='Actually move READY files without deleting/overwriting')
    args=ap.parse_args()
    if not args.root.is_dir():ap.error('Player not accessible at '+str(args.root)+'; check USB mount')
    root=args.root.resolve()
    rows=plan(root);write_csv(args.report,rows)
    counts=Counter(x['status'] for x in rows)
    print('Audio:',len(rows),'|', ' | '.join(k+': '+str(counts[k]) for k in ('READY','LEAVE','REVIEW','COLLISION','PLAYLIST_PROTECTED')))
    print('Plan:',args.report)
    if not args.apply:
        print('PREVIEW ONLY — nothing on the RUIZU changed. Add --apply after reviewing the CSV.')
        return
    result=apply(root,rows)
    log=args.report.parent/'RUIZU-sort-changes.txt'
    log.write_text('\n'.join(result)+'\n',encoding='utf-8')
    print('Moved',sum(x.startswith('MOVED') for x in result),'files. Log:',log)
if __name__=='__main__':main()