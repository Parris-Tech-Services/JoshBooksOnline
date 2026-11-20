'use client';
import { useEffect, useRef, useState } from 'react';
import type { AudioTrack } from '@/types/books';
import { prepareRuizuFiles } from '@/lib/ruizuExport';

export function RuizuExport({ title, tracks }: { title: string; tracks: AudioTrack[] }) {
  const [selected, setSelected] = useState(() => tracks.map(() => true));
  const [files, setFiles] = useState<File[]>([]);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('Select tracks, prepare audio, then choose RUIZU Sync in the Android share sheet. Large books can use the Drive picker in RUIZU Sync.');
  const controller = useRef<AbortController | null>(null);
  useEffect(() => () => controller.current?.abort(), []);
  async function prepare() {
    const chosen = tracks.filter((_, index) => selected[index]);
    controller.current = new AbortController();
    setBusy(true); setFiles([]);
    try {
      if (!navigator.share || !navigator.canShare) throw new Error('File sharing is unavailable in this browser. Open RUIZU Sync and select the originals from Google Drive.');
      const ready = await prepareRuizuFiles(title, chosen, controller.current.signal,
        (done) => setMessage(`Prepared ${done} of ${chosen.length} tracks`));
      if (!navigator.canShare({ files: ready })) throw new Error('This browser cannot share these audio files. Use the Drive picker in RUIZU Sync.');
      setFiles(ready); setMessage('Audio ready. Tap Send to RUIZU Sync and select the app.');
    } catch (error) {
      setMessage(error instanceof Error && error.name === 'AbortError' ? 'Preparation cancelled.' : error instanceof Error ? error.message : 'Preparation failed.');
    } finally { setBusy(false); }
  }
  async function share() {
    try { await navigator.share({ files, title }); setMessage('Share sheet closed. Check RUIZU Sync for transfer results.'); }
    catch (error) { setMessage(error instanceof Error && error.name === 'AbortError' ? 'Sharing cancelled; audio remains ready.' : 'Sharing failed. Try again or use the Drive picker.'); }
  }
  return <section className="rounded-2xl border border-white/10 bg-white/5 p-4">
    <h2 className="font-semibold text-white">Export to RUIZU</h2>
    <p role="status" className="my-3 text-sm text-slate-300">{message}</p>
    <div className="max-h-48 overflow-auto">
      {tracks.map((track, index) => <label key={track.id} className="flex min-h-11 items-center gap-3 text-sm text-slate-200">
        <input type="checkbox" checked={selected[index] ?? false} disabled={busy} onChange={(event) => {
          const next = [...selected]; next[index] = event.target.checked; setSelected(next); setFiles([]);
        }} />{index + 1}. {track.name}
      </label>)}
    </div>
    <div className="mt-3 flex flex-wrap gap-3">
      <button type="button" disabled={busy || !selected.some(Boolean)} onClick={prepare} className="min-h-11 rounded-lg bg-blue-600 px-4 text-white disabled:opacity-50">Prepare selected audio</button>
      {busy && <button type="button" onClick={() => controller.current?.abort()} className="min-h-11 px-4 text-white">Cancel</button>}
      {files.length > 0 && <button type="button" onClick={share} className="min-h-11 rounded-lg bg-emerald-700 px-4 text-white">Send to RUIZU Sync</button>}
    </div>
  </section>;
}
