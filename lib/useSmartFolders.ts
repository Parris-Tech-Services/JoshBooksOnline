'use client';

import { useCallback, useEffect, useState } from 'react';
import type { BookEntry, AudiobookEntry } from '@/types/books';

export type SmartFolderMediaType = 'ebook' | 'audiobook' | 'any';

export interface SmartFolder {
  id: string;
  name: string;
  /** Media type filter */
  mediaType: SmartFolderMediaType;
  /** Match items whose source includes this string (case-insensitive) */
  source?: string;
  /** Match items whose author includes this string (case-insensitive) */
  author?: string;
  /** Match items whose title includes this string (case-insensitive) */
  titleKeyword?: string;
  /** Published year range */
  yearMin?: number;
  yearMax?: number;
  /** Minimum reading progress % (ebooks only) */
  progressMin?: number;
  /** Maximum reading progress % (ebooks only) */
  progressMax?: number;
  /** Only include items with any progress tracked */
  hasProgress?: boolean;
}

const SMART_FOLDERS_KEY = 'joshbooks-smart-folders';

function newId(): string {
  try {
    return crypto.randomUUID();
  } catch {
    return `sf_${Date.now().toString(36)}`;
  }
}

function includesQuery(value: string, query?: string): boolean {
  if (!query) return true;
  return value.toLowerCase().includes(query.toLowerCase());
}

function itemTitle(
  item: BookEntry | AudiobookEntry,
  kind: 'ebook' | 'audiobook',
): string {
  if (kind === 'audiobook') return (item as AudiobookEntry).title;
  const book = item as BookEntry;
  return book.title ?? book.name ?? '';
}

function itemAuthors(
  item: BookEntry | AudiobookEntry,
  kind: 'ebook' | 'audiobook',
): string[] {
  return kind === 'ebook'
    ? ((item as BookEntry).authors ?? [])
    : ((item as AudiobookEntry).authors ?? []);
}

function authorsMatch(authors: string[], query?: string): boolean {
  if (!query) return true;
  return authors.some((author) => includesQuery(author, query));
}

function itemYear(
  item: BookEntry | AudiobookEntry,
  kind: 'ebook' | 'audiobook',
): number | null {
  const publishedDate =
    kind === 'ebook'
      ? (item as BookEntry).publishedDate
      : (item as AudiobookEntry).publishedDate;
  const parsed = Number.parseInt(publishedDate ?? '', 10);
  return Number.isFinite(parsed) ? parsed : null;
}

function yearMatches(folder: SmartFolder, year: number | null): boolean {
  if (folder.yearMin !== undefined && (year === null || year < folder.yearMin)) {
    return false;
  }
  if (folder.yearMax !== undefined && (year === null || year > folder.yearMax)) {
    return false;
  }
  return true;
}

function ebookProgressMatches(folder: SmartFolder, item: BookEntry): boolean {
  const progress = item.readingProgress ?? 0;
  if (folder.hasProgress && progress === 0) return false;
  if (folder.progressMin !== undefined && progress < folder.progressMin) return false;
  if (folder.progressMax !== undefined && progress > folder.progressMax) return false;
  return true;
}

export function matchesSmartFolder(
  folder: SmartFolder,
  item: BookEntry | AudiobookEntry,
  kind: 'ebook' | 'audiobook'
): boolean {
  if (folder.mediaType !== 'any' && folder.mediaType !== kind) return false;
  if (!includesQuery(itemTitle(item, kind), folder.titleKeyword)) return false;
  if (!includesQuery(item.source, folder.source)) return false;
  if (!authorsMatch(itemAuthors(item, kind), folder.author)) return false;
  if (!yearMatches(folder, itemYear(item, kind))) return false;
  if (kind === 'ebook' && !ebookProgressMatches(folder, item as BookEntry)) return false;
  return true;
}

export function useSmartFolders() {
  const [folders, setFolders] = useState<SmartFolder[]>([]);
  const [loaded, setLoaded] = useState(false);

  useEffect(() => {
    try {
      const raw = window.localStorage.getItem(SMART_FOLDERS_KEY);
      if (raw) setFolders(JSON.parse(raw) as SmartFolder[]);
    } catch {
      // ignore
    }
    setLoaded(true);
  }, []);

  useEffect(() => {
    if (loaded) window.localStorage.setItem(SMART_FOLDERS_KEY, JSON.stringify(folders));
  }, [folders, loaded]);

  const createFolder = useCallback((input: Omit<SmartFolder, 'id'>) => {
    const id = newId();
    setFolders((prev) => [...prev, { ...input, id }]);
    return id;
  }, []);

  const updateFolder = useCallback((id: string, updates: Partial<Omit<SmartFolder, 'id'>>) => {
    setFolders((prev) => prev.map((f) => (f.id === id ? { ...f, ...updates } : f)));
  }, []);

  const deleteFolder = useCallback((id: string) => {
    setFolders((prev) => prev.filter((f) => f.id !== id));
  }, []);

  return { folders, createFolder, updateFolder, deleteFolder };
}
