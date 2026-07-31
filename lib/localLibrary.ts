import { readFile, writeFile } from 'node:fs/promises';
import { existsSync, statSync } from 'node:fs';
import path from 'node:path';
import type { BookEntry, BookFormat } from '@/types/books';

const MIME_TO_FORMAT: Record<string, BookFormat> = {
  'application/pdf': 'pdf',
  'application/epub+zip': 'epub',
  'application/vnd.openxmlformats-officedocument.wordprocessingml.document': 'docx',
  'text/plain': 'txt',
};

interface RescueBook {
  id: string;
  name: string;
  mimeType: string;
  size: number | null;
  modifiedTime: string | null;
  source: string;
  readingProgress: number;
  lastLocation: string;
  lastOpened?: string;
  rescuedPath?: string;
  status?: string;
}

interface RescueManifest {
  books: RescueBook[];
}

function configuredRoot() {
  return process.env.BOOKSHELF_LOCAL_LIBRARY_PATH?.trim() || '';
}

function manifestPath(root: string) {
  return path.join(root, 'bookshelf-rescue.json');
}

function progressPath(root: string) {
  return path.join(root, 'bookshelf-progress.local.json');
}

function encodeLocalId(rescuedPath: string) {
  return `local:${Buffer.from(rescuedPath, 'utf8').toString('base64url')}`;
}

export function decodeLocalId(fileId: string) {
  if (!fileId.startsWith('local:')) return null;
  return Buffer.from(fileId.slice('local:'.length), 'base64url').toString('utf8');
}

export function hasLocalLibrary() {
  const root = configuredRoot();
  return !!root && existsSync(manifestPath(root));
}

async function readProgress(root: string) {
  try {
    return JSON.parse(await readFile(progressPath(root), 'utf8')) as Record<
      string,
      { progressPercentage?: string; lastLocation?: string; lastOpened?: string }
    >;
  } catch {
    return {};
  }
}

async function readManifest() {
  const root = configuredRoot();
  if (!root) return null;
  const file = manifestPath(root);
  if (!existsSync(file)) return null;
  return { root, manifest: JSON.parse(await readFile(file, 'utf8')) as RescueManifest };
}

export async function getLocalLibraryFiles(): Promise<BookEntry[]> {
  const data = await readManifest();
  if (!data) return [];
  const progress = await readProgress(data.root);

  return data.manifest.books
    .filter((book) => book.status === 'rescued' && book.rescuedPath && MIME_TO_FORMAT[book.mimeType])
    .map((book) => {
      const saved = progress[book.rescuedPath!] ?? {};
      return {
        id: encodeLocalId(book.rescuedPath!),
        name: book.name,
        mimeType: book.mimeType,
        size: book.size ?? 0,
        modifiedTime: book.modifiedTime ?? new Date(0).toISOString(),
        source: 'Local Books',
        format: MIME_TO_FORMAT[book.mimeType],
        readingProgress: saved.progressPercentage
          ? Number.parseInt(saved.progressPercentage, 10) || 0
          : book.readingProgress,
        lastLocation: saved.lastLocation ?? book.lastLocation ?? '',
        lastOpened: saved.lastOpened ?? book.lastOpened,
      };
    });
}

export async function getLocalBook(fileId: string) {
  const rescuedPath = decodeLocalId(fileId);
  const data = await readManifest();
  if (!rescuedPath || !data) return null;
  const book = data.manifest.books.find((item) => item.rescuedPath === rescuedPath);
  if (!book?.rescuedPath || !MIME_TO_FORMAT[book.mimeType]) return null;

  const fullPath = path.resolve(data.root, book.rescuedPath);
  const root = path.resolve(data.root);
  if (!fullPath.startsWith(root + path.sep)) return null;
  const stats = statSync(fullPath);
  if (!stats.isFile()) return null;
  const progress = await readProgress(data.root);
  const saved = progress[book.rescuedPath] ?? {};

  return {
    path: fullPath,
    metadata: {
      name: book.name,
      mimeType: book.mimeType,
      appProperties: {
        lastLocation: saved.lastLocation ?? book.lastLocation ?? '',
        progressPercentage: saved.progressPercentage ?? String(book.readingProgress ?? 0),
        lastOpened: saved.lastOpened ?? book.lastOpened,
      },
    },
  };
}

export async function updateLocalBookProgress(fileId: string, progress: number, location: string) {
  const rescuedPath = decodeLocalId(fileId);
  const data = await readManifest();
  if (!rescuedPath || !data) return false;
  const existing = await readProgress(data.root);
  existing[rescuedPath] = {
    progressPercentage: String(Math.round(progress)),
    lastLocation: location,
    lastOpened: new Date().toISOString(),
  };
  await writeFile(progressPath(data.root), JSON.stringify(existing, null, 2));
  return true;
}
