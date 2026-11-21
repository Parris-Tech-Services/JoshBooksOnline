#!/usr/bin/env python3
"""Create a PRIVATE offline podcast sampler from public publishers' RSS enclosures.

Runs on GitHub Actions as a temporary artifact, not as a public Release or committed audio.
One different programme per category. No paid or authenticated feeds, no DRM bypass.
"""
import concurrent.futures
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import unicodedata
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
import zipfile
from datetime import datetime, timezone
from pathlib import Path

NOW = datetime(2026, 11, 21, tzinfo=timezone.utc)
MAX_BYTES_PER_SOURCE = 85 * 1024 * 1024
MAX_SECONDS = 150 * 60
MAX_EPISODES = 20
USER_AGENT = 'RUIZUOfflinePack/1.0 (personal podcast player; fetches public RSS enclosures)'
SHOWS = [
  ('Design & everyday objects', [('99% Invisible','99% Invisible'),('Twenty Thousand Hertz','Twenty Thousand Hertz')]),
  ('Cybersecurity & hacking', [('Darknet Diaries','Darknet Diaries'),('Risky Business','Risky Business')]),
  ('Christian questions & biblical history', [('The Bible for Normal People','The Bible for Normal People'),('Ask NT Wright Anything','Ask NT Wright Anything')]),
  ('History & surprising past', [('The Rest Is History','The Rest Is History'),('The Ancients','The Ancients')]),
  ('Parenting & family', [('Good Inside with Dr. Becky','Good Inside with Dr. Becky'),('The Dad Edge Podcast','The Dad Edge')]),
  ('Science & unusual experts', [('Ologies with Alie Ward','Ologies'),('Science Vs','Science Vs')]),
  ('Science fiction audio stories', [('Escape Pod','Escape Pod'),('Clarkesworld Magazine','Clarkesworld')]),
  ('Dungeons & Dragons actual-play', [('Not Another D&D Podcast','Not Another D&D Podcast'),('Spout Lore','Spout Lore'),('The Glass Cannon Podcast','The Glass Cannon Podcast'),('Dungeons and Daddies','Dungeons and Daddies'),('Worlds Beyond Number','Worlds Beyond Number')]),
  ('Music explained by the artists', [('Song Exploder','Song Exploder'),('Strong Songs','Strong Songs')]),
  ('Everyday economics', [('Planet Money','Planet Money'),('The Indicator from Planet Money','The Indicator')]),
  ('Curious mysteries & the internet', [('Search Engine','Search Engine'),('Endless Thread','Endless Thread')]),
  ('Comedy & trivia', [('No Such Thing As A Fish','No Such Thing As A Fish'),('The Weirdest Thing I Learned This Week','Weirdest Thing')]),
  ('Real-life storytelling', [('The Moth','The Moth'),('Snap Judgment','Snap Judgment')]),
  ('Language & words', [('The Allusionist','The Allusionist'),('Because Language','Because Language')]),
  ('Space & the universe', [('Astronomy Cast','Astronomy Cast'),('NASA\'s Curious Universe','NASA Curious Universe')]),
  ('Men’s lives & character', [('The Art of Manliness','The Art of Manliness'),('The One You Feed','The One You Feed')]),
  ('Australian conversations', [('Conversations','Conversations ABC Radio'),('The Imperfects','The Imperfects')]),
  ('Programming & web development', [('Syntax - Tasty Web Development Treats','Syntax'),('CodeNewbie','CodeNewbie')]),
  ('Outdoors & exploration', [('Outside Podcast','Outside Podcast'),('The Adventure Podcast','The Adventure Podcast')]),
  ('Philosophy & practical wisdom', [('Philosophize This!','Philosophize This'),('The Daily Stoic','The Daily Stoic')]),
  ('Bonus - kids storytime', [('Story Pirates','Story Pirates'),('Little Stories for Tiny People','Little Stories for Tiny People')]),
  ('Bonus - mindful living', [('Ten Percent Happier with Dan Harris','Ten Percent Happier'),('The Mindful Minute','The Mindful Minute')]),
  ('Psychology & behaviour', [('Hidden Brain','Hidden Brain'),('You Are Not So Smart','You Are Not So Smart')]),
  ('Books & reading', [('Overdue','Overdue'),('The New Yorker: Fiction Podcast','The New Yorker Fiction Podcast')]),
  ('Food & curious cooking', [('The Sporkful','The Sporkful'),('Home Cooking','Home Cooking')]),
  ('Christianity & contemporary culture', [('The Holy Post','The Holy Post'),('Unbelievable?','Unbelievable')]),
  ('Wildlife & natural world', [('The Science of Birds','The Science of Birds'),('The Wild with Chris Morgan','The Wild')]),
  ('Education & teaching', [('Cult of Pedagogy','Cult of Pedagogy'),('Teaching in Higher Ed','Teaching in Higher Ed')]),
  ('Film & cinema', [('The Rewatchables','The Rewatchables'),('The Big Picture','The Big Picture')]),
  ('Technology & human stories', [('Decoder with Nilay Patel','Decoder'),('Waveform: The MKBHD Podcast','Waveform')]),
  ('Video games & gaming culture', [('The Besties','The Besties'),('Triple Click','Triple Click')]),
  ('Mathematics & puzzles', [('The Numberphile Podcast','The Numberphile Podcast'),('The Joy of Why','The Joy of Why')]),
  ('Medical myths & evidence', [('Maintenance Phase','Maintenance Phase'),('The Drive with Peter Attia','The Drive')]),
  ('Australian comedy', [('TOFOP','TOFOP'),('The Little Dum Dum Club','The Little Dum Dum Club')]),
  ('Deep interviews', [('The Ezra Klein Show','The Ezra Klein Show'),('On Being with Krista Tippett','On Being')]),
  ('Independent pop culture', [('Switched on Pop','Switched on Pop'),('KEXP Song of the Day','KEXP Song of the Day')]),
  ('True-crime storytelling', [('Criminal','Criminal'),('Casefile True Crime','Casefile')]),
  ('Sustainable living & environment', [('Outside/In','Outside/In'),('Climate One','Climate One')]),
  ('Creative practice', [('Creative Pep Talk','Creative Pep Talk'),('The Writer Files','The Writer Files')]),
  ('Unusual science & research', [('Every Little Thing','Every Little Thing'),('The Weirdest Thing I Learned This Week','The Weirdest Thing I Learned This Week')]),
]


def get(url, max_bytes=6*1024*1024, timeout=24, attempts=2):
    if not url.startswith(('http://', 'https://')):
        raise ValueError('Not http(s): '+url)
    err = None
    for attempt in range(attempts):
        try:
            req=urllib.request.Request(url,headers={'User-Agent':USER_AGENT,'Accept':'application/rss+xml,application/xml,*/*','Accept-Encoding':'identity'})
            with urllib.request.urlopen(req,timeout=timeout) as r:
                data=r.read(max_bytes+1)
                if len(data)>max_bytes: raise ValueError('Oversize resource')
                return data
        except Exception as e:
            err=e;time.sleep(min(attempt+1,2))
    raise err


def norm(s):
    s=unicodedata.normalize('NFKD',s).casefold()
    return re.sub('[^a-z0-9]+',' ',s).strip()


def title_sim(expected, candidate):
    a=set(norm(expected).split())-{'the','podcast','with','and','a','of','show'}
    b=set(norm(candidate).split())-{'the','podcast','with','and','a','of','show'}
    if not a or not b:return 0
    return len(a&b)/len(a)


def resolve_feed(query, expected):
    args=urllib.parse.urlencode({'term':query,'media':'podcast','entity':'podcast','limit':30,'country':'us'})
    data=json.loads(get('https://itunes.apple.com/search?'+args,4*1024*1024).decode('utf-8'))
    matches=[]
    for x in data.get('results',[]):
        u=x.get('feedUrl','')
        if not u.startswith(('http://','https://')):continue
        label=x.get('collectionName') or ''
        score=title_sim(expected,label)
        # High recall on the SHOW title, high precision to reject unrelated programmes
        # merely mentioning it, e.g. '... and the rest is FILM history' or
        # 'Darknet Diaries en espanol' when the English podcast was requested.
        expected_tokens=set(norm(expected).split())-{'the','podcast','with','and','a','of','show'}
        label_tokens=set(norm(label).split())-{'the','podcast','with','and','a','of','show'}
        precision=len(expected_tokens&label_tokens)/max(1,len(label_tokens))
        if score>=0.8 and precision>=0.79:
            matches.append((score, precision, u, label))
    if not matches: raise ValueError(f'No sufficiently exact public feed: {expected} (query={query})')
    matches.sort(key=lambda x: (-x[0], -x[1], len(x[3])))
    return matches[0][2],matches[0][3]


def feed_episodes(feed_url, category=""):
    data=get(feed_url,max_bytes=12*1024*1024,timeout=30)
    root=ET.fromstring(data)
    eps=[]
    for elem in root.findall('.//item')[:100]:
        title=(elem.findtext('title') or 'Untitled').strip()
        title_key=norm(title)
        if any(x in title_key for x in ('trailer','bonus subscription','ad free preview','subscription only','premium preview','announcement','touring','live show','buy tickets','one off live','come see us','ticket sales','get ready')):continue
        if category=='Dungeons & Dragons actual-play' and any(x in title_key for x in ('fireside','class action','chat with','q a','interlude','welcome to worlds','horner corner','aftershow','talkback','behind the scenes')):continue
        if category=='Parenting & family' and ('squishy drop' in title_key):continue
        if category=='Christian questions & biblical history' and any(x in title_key for x in ('bonus episode','holiday roast','coming soon')):continue
        encs=elem.findall('enclosure')
        encs+=[x for x in elem if x.tag.endswith('}enclosure')]
        for e in encs:
            url=e.get('url','')
            typ=e.get('type','')
            if not url.startswith(('https://','http://')):continue
            if typ and not (typ.startswith('audio/') or typ=='application/octet-stream'): continue
            try: size=int(e.get('length') or 0)
            except: size=0
            if size>MAX_BYTES_PER_SOURCE:continue
            # Avoid 2-hour episodes, prioritise sufficiently short, substantive episodes.
            duration=''
            for sub in elem:
                if sub.tag.endswith('duration'): duration=(sub.text or '').strip()
            seconds=0
            if duration:
                try:
                    parts=duration.split(':')
                    if len(parts)==3: seconds=int(parts[0])*3600+int(parts[1])*60+int(parts[2])
                    elif len(parts)==2: seconds=int(parts[0])*60+int(parts[1])
                    else: seconds=int(duration)
                except:pass
            if seconds>MAX_SECONDS: continue
            if seconds and seconds<240:continue
            eps.append({'title':title,'url':url,'bytes':size,'seconds':seconds,'guid':(elem.findtext('guid') or '').strip()})
            break
    # Prefer shorter substantial episodes within most recent 35, not all-day background recordings.
    eligible=eps[:35]
    eligible.sort(key=lambda e: (0 if 600<=e['seconds']<=2100 else 1,0 if 0<e['bytes']<=35*1024*1024 else 1, e['seconds'] if e['seconds'] else 2100, eps.index(e)))
    return eligible[:6]


def slug(s,n=75):
    s=unicodedata.normalize('NFKD',s).encode('ascii','ignore').decode('ascii')
    s=re.sub(r'[^A-Za-z0-9 -]','',s)
    s=re.sub(r'\s+',' ',s).strip()[:n].strip()
    return s or 'Untitled'


def download(url,path):
    req=urllib.request.Request(url,headers={'User-Agent':USER_AGENT,'Accept':'audio/*,*/*','Accept-Encoding':'identity'})
    total=0
    with urllib.request.urlopen(req,timeout=45) as r,open(path,'wb') as w:
        while True:
            chunk=r.read(128*1024)
            if not chunk:break
            total+=len(chunk)
            if total>MAX_BYTES_PER_SOURCE:raise ValueError('Episode too large')
            w.write(chunk)
    if total<15000:raise ValueError('Not a meaningful audio download')
    return total


def probe(path):
    p=subprocess.run(['ffprobe','-v','error','-select_streams','a:0','-show_entries','stream=codec_name:format=duration','-of','json',str(path)],capture_output=True,text=True,timeout=50)
    if p.returncode:raise ValueError('ffprobe failed: '+p.stderr[:250])
    j=json.loads(p.stdout)
    if not j.get('streams'):raise ValueError('No audio stream')
    duration=float(j.get('format',{}).get('duration') or 0)
    if duration<200 or duration>MAX_SECONDS+90:raise ValueError('Duration invalid: '+str(duration))
    return duration,j['streams'][0].get('codec_name')


def choose_slot(index,category,alternatives,work,out_dir):
    errors=[]
    for query,expected in alternatives:
        try:
            feed,found=resolve_feed(query,expected)
            eps=feed_episodes(feed,category)
        except Exception as e:
            errors.append(f'{expected} feed: {e}');continue
        if not eps:
            errors.append(f'{expected}: no suitable episodes');continue
        for episode in eps:
            tmp=work/f'{index:02d}_source.audio'
            target=out_dir/f'{index:02d} - {slug(found,28)} - {slug(episode["title"],50)}.mp3'
            try:
                download(episode['url'],tmp)
                duration,codec=probe(tmp)
                # Voice-friendly 64k mono for good capacity on 64GB player.
                p=subprocess.run(['ffmpeg','-v','error','-y','-i',str(tmp),'-map','0:a:0','-vn','-ac','1','-ar','44100','-c:a','libmp3lame','-b:a','64k','-id3v2_version','3',str(target)],capture_output=True,text=True,timeout=240)
                if p.returncode:raise ValueError('MP3 encode failed: '+p.stderr[-250:])
                output_duration,output_codec=probe(target)
                if output_codec!='mp3' or abs(output_duration-duration)>5:raise ValueError('Transcode mismatch')
                sha=hashlib.sha256(target.read_bytes()).hexdigest()
                record={'slot':index,'genre':category,'podcast':found,'episode':episode['title'],'duration_minutes':round(output_duration/60,1),'size_bytes':target.stat().st_size,'filename':target.name,'sha256':sha,'feed':feed,'episode_audio_url':episode['url']}
                print(f'OK {index:02d}/{MAX_EPISODES} {found} - {episode["title"][:50]} ({output_duration/60:.1f}min, {target.stat().st_size/1e6:.1f}MB)',flush=True)
                return record
            except Exception as e:
                errors.append(f'{found} {episode["title"][:42]}: {e}')
            finally:
                tmp.unlink(missing_ok=True)
                if target.exists() and target.stat().st_size<15000:target.unlink()
    print(f'FAILED SLOT {index} {category}: '+ ' | '.join(errors)[-1500:],flush=True)
    return None


def main():
    root=Path(sys.argv[1] if len(sys.argv)>1 else 'ruizu-variety-output').resolve()
    root.mkdir(parents=True,exist_ok=True)
    out=root/'Podcasts'
    out.mkdir(exist_ok=True)
    work=root/'.working';work.mkdir(exist_ok=True)
    records=[]
    # Download six different programmes in parallel per wave, stop after 20 successes.
    # Extra categories are fallbacks, not extra files in the final ZIP.
    for start in range(0,len(SHOWS),6):
        if len(records)>=MAX_EPISODES:break
        with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
            jobs=[(i,pool.submit(choose_slot,i,cat,opts,work,out))
                  for i,(cat,opts) in ((j+1,SHOWS[j]) for j in range(start,min(start+6,len(SHOWS))))]
            for i,fut in jobs:
                try:
                    rec=fut.result()
                    if rec: records.append(rec)
                except Exception as e: print(f'UNEXPECTED SLOT FAILURE {i}: {e}',flush=True)
    unique=[]; seen_shows=set()
    for rec in records:
        key=norm(rec['podcast'])
        if key in seen_shows:continue
        seen_shows.add(key);unique.append(rec)
    records=unique[:MAX_EPISODES]
    # Stable 01..20 numbers regardless of skipped slots.
    for n,rec in enumerate(records,1):
        old=out/rec['filename']
        new=f'{n:02d} - {rec["filename"].split(" - ",1)[1]}'
        old.rename(out/new);rec['filename']=new;rec['number']=n
    (root/'EPISODE_INDEX.json').write_text(json.dumps(records,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    lines=['# 20-Genre RUIZU Podcast Sampler','',f'Successfully downloaded and verified: {len(records)} actual episodes.','Personal offline listening; sourced from free public podcast RSS feeds, no subscription bypass.','Audio transcoded to MP3, 64 kbps mono, for compact compatibility.','', 'Shows are produced by their respective creators; this is not an AI voice recreation.','']
    for r in records: lines.append(f"{r['number']:02d}. {r['genre']} | {r['podcast']} | {r['episode']} | {r['duration_minutes']} min")
    (root/'START_HERE.txt').write_text('\n'.join(lines)+'\n',encoding='utf-8')
    sums=''.join(f'{r["sha256"]}  Podcasts/{r["filename"]}\n' for r in records)
    (root/'SHA256SUMS.txt').write_text(sums)
    (root/'Podcasts'/'LISTEN_IN_ORDER.m3u').write_text('#EXTM3U\n'+'\n'.join(r['filename'] for r in records)+'\n')
    outzip=root/'RUIZU-20-Different-Podcasts-2026-11-21.zip'
    with zipfile.ZipFile(outzip,'w',zipfile.ZIP_STORED,allowZip64=True) as z:
        for rec in records:z.write(out/rec['filename'],f'Podcasts/{rec["filename"]}')
        for f in [root/'START_HERE.txt',root/'SHA256SUMS.txt',root/'EPISODE_INDEX.json',out/'LISTEN_IN_ORDER.m3u']:
            z.write(f, ('Podcasts/' if f.name.endswith('.m3u') else '')+f.name)
    print('RESULT',len(records),'EPISODES',outzip,outzip.stat().st_size,flush=True)
    shutil.rmtree(work)
    # Require 20: never mislabel partial bundle as complete.
    if len(records)!=MAX_EPISODES:sys.exit(f'Only {len(records)}/20 shows downloaded; please inspect log. Partial artifact uploaded for review, not labelled complete.')

if __name__=='__main__':main()