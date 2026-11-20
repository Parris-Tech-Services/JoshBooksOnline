import type { AudioTrack } from '@/types/books';

export const MAX_SHARE_BYTES = 200 * 1024 * 1024;
export const MAX_SHARE_TRACKS = 200;
const AUDIO_TYPES: Record<string, string> = {
  mp3: 'audio/mpeg', m4a: 'audio/mp4', m4b: 'audio/mp4', wav: 'audio/wav',
  flac: 'audio/flac', aac: 'audio/aac', ogg: 'audio/ogg', wma: 'audio/x-ms-wma',
};
export function audioMime(name: string): string | undefined {
  return AUDIO_TYPES[name.split('.').pop()?.toLowerCase() ?? ''];
}
export function exportName(title: string, track: AudioTrack, index: number): string {
  const clean = (value: string) => value.replace(/[\\/:*?"<>|\u0000-\u001f]/g, '_').replace(/[. ]+$/, '').trim();
  const extension = track.name.split('.').pop()?.toLowerCase() ?? 'mp3';
  return `${clean(title).slice(0, 40) || 'Audiobook'} - ${String(index + 1).padStart(4, '0')} - ${clean(track.name.replace(/\.[^.]+$/, '')).slice(0, 60)}.${extension}`;
}
/** Only same-origin authenticated media. No public download URLs or OAuth tokens in the handoff. */
export async function prepareRuizuFiles(
  title: string, tracks: AudioTrack[], signal: AbortSignal,
  progress: (done: number) => void,
): Promise<File[]> {
  if (!tracks.length || tracks.length > MAX_SHARE_TRACKS) throw new Error('Select 1–200 tracks.');
  if (tracks.some((track) => !audioMime(track.name))) throw new Error('Select MP3 or other supported audio files.');
  const declaredSize = tracks.reduce((total, track) => total + Math.max(0, track.size || 0), 0);
  if (declaredSize > MAX_SHARE_BYTES) throw new Error('This selection exceeds 200 MiB. Transfer the originals using the Google Drive picker in RUIZU Sync.');
  const files: File[] = [];
  let bytes = 0;
  for (const [index, track] of tracks.entries()) {
    const response = await fetch(`/api/stream/${encodeURIComponent(track.id)}?mime=${encodeURIComponent(audioMime(track.name)!)}`, {
      credentials: 'same-origin', cache: 'no-store', signal,
    });
    if (!response.ok || response.status === 206) throw new Error('Audio download failed. Sign in again or retry.');
    if (!response.body) throw new Error('Audio download returned no data.');
    const reader = response.body.getReader();
    const chunks: ArrayBuffer[] = [];
    let trackBytes = 0;
    try {
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        bytes += value.byteLength;
        trackBytes += value.byteLength;
        if (bytes > MAX_SHARE_BYTES) throw new Error('Audio exceeds 200 MiB. Use the Google Drive picker in RUIZU Sync.');
        chunks.push(value.slice().buffer);
      }
    } finally { await reader.cancel(); }
    if (!trackBytes || (track.size > 0 && trackBytes !== track.size)) throw new Error('Incomplete audio download. Retry before sharing.');
    files.push(new File(chunks, exportName(title, track, index), { type: audioMime(track.name) }));
    progress(index + 1);
  }
  return files;
}
