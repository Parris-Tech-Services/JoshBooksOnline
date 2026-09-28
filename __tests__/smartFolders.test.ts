import { matchesSmartFolder, type SmartFolder } from '@/lib/useSmartFolders';
import type { AudiobookEntry, BookEntry } from '@/types/books';

const book = {
  id: 'book-1',
  name: 'Clean Code.pdf',
  title: 'Clean Code',
  source: 'IT PD Ebooks',
  authors: ['Robert C. Martin'],
  publishedDate: '2008-08-01',
  readingProgress: 42,
} as BookEntry;

const audiobook = {
  id: 'audio-1',
  title: 'The Pragmatic Programmer',
  source: 'Audiobooks',
  authors: ['Andrew Hunt', 'David Thomas'],
  publishedDate: '1999',
} as AudiobookEntry;

function folder(overrides: Partial<SmartFolder> = {}): SmartFolder {
  return {
    id: 'folder-1',
    name: 'Test',
    mediaType: 'any',
    ...overrides,
  };
}

describe('smart folder matching', () => {
  it('matches title, source and author case-insensitively', () => {
    expect(
      matchesSmartFolder(
        folder({
          titleKeyword: 'clean',
          source: 'it pd',
          author: 'martin',
        }),
        book,
        'ebook',
      ),
    ).toBe(true);

    expect(matchesSmartFolder(folder({ author: 'hunt' }), audiobook, 'audiobook')).toBe(true);
  });

  it('enforces media type and year bounds', () => {
    expect(matchesSmartFolder(folder({ mediaType: 'audiobook' }), book, 'ebook')).toBe(false);
    expect(matchesSmartFolder(folder({ yearMin: 2009 }), book, 'ebook')).toBe(false);
    expect(matchesSmartFolder(folder({ yearMax: 2007 }), book, 'ebook')).toBe(false);
    expect(matchesSmartFolder(folder({ yearMin: 2008, yearMax: 2008 }), book, 'ebook')).toBe(true);
  });

  it('rejects missing publication years when a year filter is active', () => {
    const undated = { ...book, publishedDate: undefined } as BookEntry;
    expect(matchesSmartFolder(folder({ yearMin: 2000 }), undated, 'ebook')).toBe(false);
  });

  it('applies reading-progress filters only to ebooks', () => {
    expect(matchesSmartFolder(folder({ hasProgress: true }), book, 'ebook')).toBe(true);
    expect(matchesSmartFolder(folder({ progressMin: 50 }), book, 'ebook')).toBe(false);
    expect(matchesSmartFolder(folder({ progressMax: 40 }), book, 'ebook')).toBe(false);
    expect(matchesSmartFolder(folder({ progressMin: 40, progressMax: 50 }), book, 'ebook')).toBe(true);

    const unread = { ...book, readingProgress: 0 } as BookEntry;
    expect(matchesSmartFolder(folder({ hasProgress: true }), unread, 'ebook')).toBe(false);

    expect(
      matchesSmartFolder(
        folder({ hasProgress: true, progressMin: 99 }),
        audiobook,
        'audiobook',
      ),
    ).toBe(true);
  });

  it('returns false when text filters do not match', () => {
    expect(matchesSmartFolder(folder({ titleKeyword: 'missing' }), book, 'ebook')).toBe(false);
    expect(matchesSmartFolder(folder({ source: 'Book Club' }), book, 'ebook')).toBe(false);
    expect(matchesSmartFolder(folder({ author: 'someone else' }), book, 'ebook')).toBe(false);
  });
});
