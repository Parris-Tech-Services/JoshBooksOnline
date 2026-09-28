export const USERDATA_STORAGE_KEYS = [
  'joshbooks-meta',
  'joshbooks-hidden',
  'joshbooks-links',
  'joshbooks-audiogroups',
  'joshbooks-youtube-links',
  'joshbooks-youtube-removed',
  'joshbooks-youtube-edits',
  'joshbooks-youtube-custom',
  'joshbooks-view',
  'joshbooks-sort-field',
  'joshbooks-sort-dir',
  'joshbooks-columns',
  'joshbooks-reader-theme',
  'joshbooks-tab',
  'joshbooks-audio-sort-field',
  'joshbooks-audio-sort-dir',
  'joshbooks-audio-speed',
  'bookshelf-reader-fontSize',
  'joshbooks-smart-folders',
  'joshbooks-pdf-zoom',
] as const;

const WATCH_PROGRESS_PREFIX = 'joshbooks-watch-progress:';

type StorageReader = Pick<Storage, 'getItem' | 'key' | 'length'>;
type StorageWriter = Pick<Storage, 'setItem'>;

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function serialiseStoredValue(value: unknown): string {
  return typeof value === 'string' ? value : JSON.stringify(value);
}

function validWatchProgress(value: unknown): value is Record<string, number> {
  if (!isRecord(value)) return false;
  return Object.entries(value).every(
    ([key, position]) =>
      key.startsWith(WATCH_PROGRESS_PREFIX) &&
      typeof position === 'number' &&
      Number.isFinite(position) &&
      position >= 0,
  );
}

export function createUserdataBackup(
  storage: StorageReader,
  exportedAt = new Date().toISOString(),
): Record<string, unknown> {
  const data: Record<string, unknown> = {
    _version: 1,
    _exported: exportedAt,
  };

  for (const key of USERDATA_STORAGE_KEYS) {
    const value = storage.getItem(key);
    if (value === null) continue;
    try {
      data[key] = JSON.parse(value);
    } catch {
      data[key] = value;
    }
  }

  const watchProgress: Record<string, number> = {};
  for (let index = 0; index < storage.length; index += 1) {
    const key = storage.key(index);
    if (!key?.startsWith(WATCH_PROGRESS_PREFIX)) continue;

    const position = Number.parseInt(storage.getItem(key) ?? '', 10);
    if (Number.isFinite(position) && position >= 0) {
      watchProgress[key] = position;
    }
  }

  if (Object.keys(watchProgress).length > 0) {
    data._watch_progress = watchProgress;
  }

  return data;
}

export function parseUserdataBackup(raw: string): Record<string, unknown> {
  const parsed: unknown = JSON.parse(raw);
  if (!isRecord(parsed)) throw new Error('Backup root must be an object');

  if (parsed._version !== undefined && parsed._version !== 1) {
    throw new Error('Unsupported backup version');
  }

  if (
    parsed._watch_progress !== undefined &&
    !validWatchProgress(parsed._watch_progress)
  ) {
    throw new Error('Invalid watch progress data');
  }

  return parsed;
}

export function restoreUserdataBackup(
  storage: StorageWriter,
  raw: string,
): void {
  const data = parseUserdataBackup(raw);

  for (const key of USERDATA_STORAGE_KEYS) {
    if (!(key in data)) continue;
    storage.setItem(key, serialiseStoredValue(data[key]));
  }

  const watchProgress = data._watch_progress;
  if (!validWatchProgress(watchProgress)) return;

  for (const [key, position] of Object.entries(watchProgress)) {
    storage.setItem(key, String(position));
  }
}
