import {
  buildYtSuggestions,
  normaliseSuggestionText,
} from '@/lib/librarySuggestions';
import type { Audiobook, AudiobookEntry, BookEntry } from '@/types/books';

function yt(
  id: string,
  title: string,
  author: string,
  catalogueMatches: string[] = [],
): Audiobook {
  return { id, title, author, catalogueMatches } as Audiobook;
}

const book = {
  id: 'book-1',
  name: 'Clean Code.pdf',
  title: 'Clean Code',
  authors: ['Robert C. Martin'],
  source: 'IT PD Ebooks',
} as BookEntry;

describe('library YouTube suggestions', () => {
  it('normalises punctuation and case', () => {
    expect(normaliseSuggestionText('Clean-Code!')).toBe('cleancode');
  });

  it('prioritises direct catalogue title matches', () => {
    const suggestions = buildYtSuggestions(
      [book],
      [],
      [],
      [],
      [yt('yt-1', 'Clean Code Audiobook', 'Robert C. Martin', ['Clean Code'])],
    );

    expect(suggestions[0]).toMatchObject({
      score: 20,
      matchedBookTitle: 'Clean Code',
    });
  });

  it('falls back to title keywords and then author matches', () => {
    const titleMatch = yt('title', 'Clean Code Full Audiobook', 'Someone Else');
    const authorMatch = yt('author', 'Software Craft', 'Robert Martin');

    const suggestions = buildYtSuggestions(
      [book],
      [] as AudiobookEntry[],
      [],
      [],
      [authorMatch, titleMatch],
    );

    expect(suggestions.map((item) => [item.audiobook.id, item.score])).toEqual([
      ['title', 12],
      ['author', 8],
    ]);
  });

  it('excludes already-added and explicitly removed catalogue items', () => {
    const candidate = yt('yt-1', 'Clean Code', 'Robert Martin', ['Clean Code']);
    expect(
      buildYtSuggestions([book], [], [candidate], [], [candidate]),
    ).toEqual([]);
    expect(
      buildYtSuggestions([book], [], [], ['yt-1'], [candidate]),
    ).toEqual([]);
  });

  it('uses Drive audiobook authors as matching context', () => {
    const drive = {
      id: 'drive-1',
      title: 'Other Book',
      source: 'Audiobooks',
      authors: ['Jane Austen'],
    } as AudiobookEntry;

    const suggestions = buildYtSuggestions(
      [],
      [drive],
      [],
      [],
      [yt('austen', 'Persuasion', 'Jane Austen')],
    );

    expect(suggestions[0]?.score).toBe(8);
  });
});
