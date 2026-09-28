export type ImportTarget = 'auto' | 'ebooks' | 'audiobooks';

export type DriveImportItem = {
  id: string;
  name: string;
  mimeType: string;
  type: 'file' | 'folder';
};

export type DriveImportRequest =
  | { ok: true; items: DriveImportItem[]; target: ImportTarget }
  | { ok: false; error: string };

const MAX_IMPORT_ITEMS = 200;

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function validTarget(value: unknown): value is ImportTarget {
  return value === 'auto' || value === 'ebooks' || value === 'audiobooks';
}

function validText(value: unknown, maxLength: number): value is string {
  return typeof value === 'string' && value.length > 0 && value.length <= maxLength;
}

function parseItem(value: unknown): DriveImportItem | null {
  if (!isRecord(value)) return null;
  if (!validText(value.id, 256)) return null;
  if (!validText(value.name, 1024)) return null;
  if (!validText(value.mimeType, 255)) return null;
  if (value.type !== 'file' && value.type !== 'folder') return null;
  return {
    id: value.id,
    name: value.name,
    mimeType: value.mimeType,
    type: value.type,
  };
}

export function parseDriveImportRequest(value: unknown): DriveImportRequest {
  if (!isRecord(value)) {
    return { ok: false, error: 'Invalid request body' };
  }

  const target = value.target === undefined ? 'auto' : value.target;
  if (!validTarget(target)) {
    return { ok: false, error: 'Invalid import target' };
  }

  if (!Array.isArray(value.items) || value.items.length === 0) {
    return {
      ok: false,
      error: 'Invalid request: items array is required and must not be empty',
    };
  }
  if (value.items.length > MAX_IMPORT_ITEMS) {
    return {
      ok: false,
      error: `Invalid request: at most ${MAX_IMPORT_ITEMS} items can be imported at once`,
    };
  }

  const items = value.items.map(parseItem);
  if (items.some((item) => item === null)) {
    return { ok: false, error: 'Invalid request: one or more items are malformed' };
  }

  return {
    ok: true,
    target,
    items: items as DriveImportItem[],
  };
}
