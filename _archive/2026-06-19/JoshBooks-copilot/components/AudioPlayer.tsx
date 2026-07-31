'use client';

import { useEffect, useRef, useState } from 'react';
import type { AudiobookEntry } from '@/types/books';

function mimeFromName(name: string): string {
  const ext = name.toLowerCase().split('.').pop() ?? '';
  if (ext === 'mp3') return 'audio/mpeg';
  if (ext === 'm4a' || ext === 'm4b' || ext === 'mp4' || ext === 'aac') return 'audio/mp4';
  if (ext === 'ogg' || ext === 'oga') return 'audio/ogg';
  if (ext === 'wav') return 'audio/wav';
  if (ext === 'flac') return 'audio/flac';
  return 'audio/mpeg';
}

function fmt(seconds: number): string {
  if (!Number.isFinite(seconds) || seconds < 0) return '0:00';
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = Math.floor(seconds % 60);
  const mm = h > 0 ? String(m).padStart(2, '0') : String(m);
  return `${h > 0 ? `${h}:` : ''}${mm}:${String(s).padStart(2, '0')}`;
}

const SPEEDS = [0.75, 1, 1.25, 1.5, 1.75, 2, 2.5, 3];
const SPEED_STORAGE_KEY = 'joshbooks-audio-speed';

type SleepMode = 0 | 15 | 30 | 45 | 60 | 'track';

export default function AudioPlayer({ audiobook }: { audiobook: AudiobookEntry }) {
  const tracks = audiobook.tracks ?? [];
  const audioRef = useRef<HTMLAudioElement | null>(null);
  const [index, setIndex] = useState(
    audiobook.audioTrack !== undefined && audiobook.audioTrack < tracks.length ? audiobook.audioTrack : 0
  );
  const [playing, setPlaying] = useState(false);
  const [time, setTime] = useState(0);
  const [duration, setDuration] = useState(0);
  const [rate, setRate] = useState(1);
  const [volume, setVolume] = useState(0.8);
  const [buffering, setBuffering] = useState(false);
  const [sleepMode, setSleepMode] = useState<SleepMode>(0);
  const [sleepEndAt, setSleepEndAt] = useState<number | null>(null);
  const [sleepRemaining, setSleepRemaining] = useState(0);
  const resumePos = useRef(audiobook.audioPosition ?? 0);
  const lastSave = useRef(0);

  const current = tracks[index];

  useEffect(() => {
    if (typeof window === 'undefined') return;
    const saved = window.localStorage.getItem(SPEED_STORAGE_KEY);
    if (saved) {
      const parsed = Number(saved);
      if (SPEEDS.includes(parsed)) setRate(parsed);
    }
  }, []);

  useEffect(() => {
    if (typeof window === 'undefined') return;
    window.localStorage.setItem(SPEED_STORAGE_KEY, String(rate));
  }, [rate]);

  // Load the current track's stream; resume position applies to the first track only.
  useEffect(() => {
    const audio = audioRef.current;
    if (!audio || !current) return;
    audio.src = `/api/stream/${current.id}?mime=${encodeURIComponent(mimeFromName(current.name))}`;
    audio.playbackRate = rate;
    audio.volume = volume;
    audio.load();

    const onLoaded = () => {
      if (resumePos.current > 0) {
        audio.currentTime = resumePos.current;
        resumePos.current = 0;
      }
      if (playing) audio.play().catch(() => {});
    };

    const onWaiting = () => setBuffering(true);
    const onCanPlay = () => setBuffering(false);

    audio.addEventListener('loadedmetadata', onLoaded, { once: true });
    audio.addEventListener('waiting', onWaiting);
    audio.addEventListener('canplay', onCanPlay);
    audio.addEventListener('canplaythrough', onCanPlay);

    return () => {
      audio.removeEventListener('loadedmetadata', onLoaded);
      audio.removeEventListener('waiting', onWaiting);
      audio.removeEventListener('canplay', onCanPlay);
      audio.removeEventListener('canplaythrough', onCanPlay);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [index]);

  useEffect(() => {
    if (audioRef.current) audioRef.current.playbackRate = rate;
  }, [rate]);

  useEffect(() => {
    if (audioRef.current) audioRef.current.volume = volume;
  }, [volume]);

  useEffect(() => {
    if (!sleepEndAt) {
      setSleepRemaining(0);
      return;
    }

    const tick = () => {
      const remaining = Math.max(0, Math.round((sleepEndAt - Date.now()) / 1000));
      setSleepRemaining(remaining);
      if (remaining <= 0) {
        const audio = audioRef.current;
        if (audio && !audio.paused) audio.pause();
        setPlaying(false);
        setSleepMode(0);
        setSleepEndAt(null);
      }
    };

    tick();
    const interval = window.setInterval(tick, 1000);
    return () => window.clearInterval(interval);
  }, [sleepEndAt]);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (!current) return;
      const target = event.target as HTMLElement | null;
      if (
        target &&
        (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.isContentEditable)
      ) {
        return;
      }

      switch (event.key) {
        case ' ':
        case 'Spacebar':
          event.preventDefault();
          togglePlay();
          break;
        case 'ArrowLeft':
          event.preventDefault();
          skip(-30);
          break;
        case 'ArrowRight':
          event.preventDefault();
          skip(30);
          break;
        case 'ArrowUp':
          event.preventDefault();
          setVolume((v) => Math.min(1, Number((v + 0.05).toFixed(2))));
          break;
        case 'ArrowDown':
          event.preventDefault();
          setVolume((v) => Math.max(0, Number((v - 0.05).toFixed(2))));
          break;
        case '[':
          event.preventDefault();
          selectTrack(Math.max(0, index - 1));
          break;
        case ']':
          event.preventDefault();
          selectTrack(Math.min(tracks.length - 1, index + 1));
          break;
        default:
          break;
      }
    };

    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [current, index, tracks.length]);

  const save = (force = false) => {
    const audio = audioRef.current;
    if (!audio) return;
    const now = Date.now();
    if (!force && now - lastSave.current < 8000) return;
    lastSave.current = now;
    fetch('/api/library/audio-progress', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ id: audiobook.id, track: index, position: Math.floor(audio.currentTime) }),
    }).catch(() => {});
  };

  const togglePlay = () => {
    const audio = audioRef.current;
    if (!audio) return;
    if (audio.paused) {
      audio.play().catch(() => {});
      setPlaying(true);
    } else {
      audio.pause();
      setPlaying(false);
      save(true);
    }
  };

  const skip = (delta: number) => {
    const audio = audioRef.current;
    if (!audio) return;
    audio.currentTime = Math.max(0, Math.min(audio.duration || 0, audio.currentTime + delta));
  };

  const selectTrack = (i: number) => {
    resumePos.current = 0;
    setIndex(i);
    setPlaying(true);
  };

  const onEnded = () => {
    if (sleepMode === 'track') {
      setPlaying(false);
      setSleepMode(0);
      setSleepEndAt(null);
      save(true);
      return;
    }

    if (index < tracks.length - 1) {
      setIndex((i) => i + 1);
    } else {
      setPlaying(false);
      save(true);
    }
  };

  const setTimer = (mode: SleepMode) => {
    setSleepMode(mode);
    if (mode === 0 || mode === 'track') {
      setSleepEndAt(null);
      setSleepRemaining(0);
      return;
    }
    setSleepEndAt(Date.now() + mode * 60_000);
  };

  if (tracks.length === 0) {
    return (
      <div className="rounded-3xl border border-white/10 bg-slate-900/80 p-8 text-center text-slate-400">
        No audio tracks found in this audiobook.
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <audio
        ref={audioRef}
        onTimeUpdate={() => {
          const a = audioRef.current;
          if (a) {
            setTime(a.currentTime);
            save();
          }
        }}
        onLoadedMetadata={() => {
          const a = audioRef.current;
          if (a) setDuration(a.duration || 0);
        }}
        onPlay={() => setPlaying(true)}
        onPause={() => setPlaying(false)}
        onEnded={onEnded}
      />

      {/* Now playing */}
      <div className="rounded-3xl border border-white/10 bg-slate-900/80 p-6">
        <div className="flex items-start gap-4">
          {audiobook.coverUrl ? (
            // eslint-disable-next-line @next/next/no-img-element
            <img src={audiobook.coverUrl} alt="" className="h-24 w-16 rounded-xl object-cover" />
          ) : (
            <div className="flex h-24 w-16 items-center justify-center rounded-xl bg-sky-600 text-2xl">🎧</div>
          )}
          <div className="min-w-0 flex-1">
            <h1 className="text-xl font-semibold text-white">{audiobook.title}</h1>
            {audiobook.authors && <p className="text-sm text-slate-400">{audiobook.authors.join(', ')}</p>}
            <p className="mt-1 truncate text-sm text-slate-300">
              Track {index + 1} of {tracks.length}: {current?.name}
            </p>
          </div>
        </div>

        {/* Seek bar */}
        <div className="mt-5">
          <div className="relative">
            <input
              type="range"
              min={0}
              max={duration || 0}
              value={time}
              onChange={(e) => {
                const a = audioRef.current;
                if (a) {
                  a.currentTime = Number(e.target.value);
                  setTime(Number(e.target.value));
                }
              }}
              className="w-full accent-sky-500"
            />
            {buffering && (
              <span className="absolute right-2 top-1/2 -translate-y-1/2 rounded-full bg-slate-950/90 px-2 py-1 text-[10px] font-semibold uppercase tracking-[0.18em] text-slate-200">
                Buffering…
              </span>
            )}
          </div>
          <div className="mt-2 flex items-center justify-between text-xs text-slate-400">
            <span>{fmt(time)}</span>
            <span>{fmt(duration)}</span>
          </div>
        </div>

        <div className="mt-4 grid gap-3 sm:grid-cols-[auto_auto_1fr]">
          <div className="flex flex-wrap items-center gap-3">
            <button
              type="button"
              onClick={() => selectTrack(Math.max(0, index - 1))}
              disabled={index === 0}
              className="rounded-full bg-slate-800 px-3 py-2 text-sm transition hover:bg-slate-700 disabled:opacity-40"
            >
              ⏮
            </button>
            <button
              type="button"
              onClick={() => skip(-30)}
              className="rounded-full bg-slate-800 px-3 py-2 text-sm transition hover:bg-slate-700"
            >
              −30s
            </button>
            <button
              type="button"
              onClick={togglePlay}
              className="rounded-full bg-sky-600 px-6 py-3 text-lg font-semibold text-white transition hover:bg-sky-500"
            >
              {playing ? '❚❚' : '►'}
            </button>
            <button
              type="button"
              onClick={() => skip(30)}
              className="rounded-full bg-slate-800 px-3 py-2 text-sm transition hover:bg-slate-700"
            >
              +30s
            </button>
            <button
              type="button"
              onClick={() => selectTrack(Math.min(tracks.length - 1, index + 1))}
              disabled={index === tracks.length - 1}
              className="rounded-full bg-slate-800 px-3 py-2 text-sm transition hover:bg-slate-700 disabled:opacity-40"
            >
              ⏭
            </button>
          </div>

          <div className="flex items-center gap-3">
            <label className="text-xs uppercase tracking-wide text-slate-500" htmlFor="audio-speed">
              Speed
            </label>
            <select
              id="audio-speed"
              value={rate}
              onChange={(e) => setRate(Number(e.target.value))}
              className="rounded-full border border-white/10 bg-slate-950 px-3 py-2 text-sm text-slate-100"
            >
              {SPEEDS.map((s) => (
                <option key={s} value={s}>
                  {s}×
                </option>
              ))}
            </select>
          </div>

          <div className="flex flex-col gap-2">
            <div className="flex items-center gap-2 text-xs uppercase tracking-wide text-slate-500">
              <span>Volume</span>
              <span className="text-slate-400">{Math.round(volume * 100)}%</span>
            </div>
            <input
              type="range"
              min={0}
              max={1}
              step={0.01}
              value={volume}
              onChange={(e) => setVolume(Number(e.target.value))}
              className="w-full accent-sky-500"
            />
          </div>
        </div>

        <div className="mt-4 flex flex-wrap items-center justify-between gap-3 rounded-2xl border border-white/10 bg-slate-950/70 px-4 py-3">
          <div className="flex items-center gap-2 text-sm text-slate-200">
            <span className="font-semibold text-slate-100">Sleep</span>
            <select
              value={String(sleepMode)}
              onChange={(e) => {
                const value = e.target.value;
                if (value === 'track') setTimer('track');
                else setTimer(Number(value) as SleepMode);
              }}
              className="rounded-full border border-white/10 bg-slate-900 px-3 py-2 text-sm text-slate-100"
            >
              <option value="0">Off</option>
              <option value="15">15 min</option>
              <option value="30">30 min</option>
              <option value="45">45 min</option>
              <option value="60">60 min</option>
              <option value="track">End of track</option>
            </select>
          </div>
          <div className="text-sm text-slate-400">
            {sleepMode === 0 && 'Timer off'}
            {sleepMode === 'track' && 'Stops at end of current track'}
            {sleepMode !== 0 && sleepMode !== 'track' && `${fmt(sleepRemaining)} remaining`}
          </div>
        </div>
      </div>

      {/* Track list */}
      {tracks.length > 1 && (
        <div className="rounded-3xl border border-white/10 bg-slate-900/80 p-4">
          <p className="mb-2 px-2 text-xs uppercase tracking-wider text-slate-500">Tracks</p>
          <ol className="max-h-80 space-y-1 overflow-y-auto">
            {tracks.map((t, i) => (
              <li key={t.id}>
                <button
                  type="button"
                  onClick={() => selectTrack(i)}
                  className={`flex w-full items-center gap-3 rounded-xl px-3 py-2 text-left text-sm transition ${
                    i === index ? 'bg-sky-600/20 text-sky-200' : 'text-slate-300 hover:bg-slate-800'
                  }`}
                >
                  <span className="w-6 text-right text-xs text-slate-500">{i + 1}</span>
                  <span className="truncate">{t.name.replace(/\.[^.]+$/, '')}</span>
                </button>
              </li>
            ))}
          </ol>
        </div>
      )}
    </div>
  );
}
