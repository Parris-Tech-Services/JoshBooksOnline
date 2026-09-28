import { getBaseYoutubeCatalog } from '@/lib/youtubeCatalog';
import type { Audiobook, AudiobookEntry, BookEntry } from '@/types/books';

export interface YtSuggestion {
  kind: 'youtube';
  audiobook: Audiobook;
  score: number;
  reason: string;
  matchedBookTitle?: string;
}

export function normaliseSuggestionText(value: string): string {
  return value.toLowerCase().replace(/[^a-z0-9 ]/g, '').trim();
}

function wordsOf(value: string): string[] {
  return normaliseSuggestionText(value)
    .split(/\s+/)
    .filter((word) => word.length > 3);
}

function overlapCount(left: string[], right: string[]): number {
  const rightWords = new Set(right);
  return left.filter((word) => rightWords.has(word)).length;
}

function bookDisplayTitle(book: BookEntry): string {
  return book.title ?? book.name.replace(/\.[^.]+$/, '');
}

function libraryAuthors(
  books: BookEntry[],
  driveAudiobooks: AudiobookEntry[],
): string[] {
  return [
    ...books.flatMap((book) => book.authors ?? []),
    ...driveAudiobooks.flatMap((book) => book.authors ?? []),
  ].map(normaliseSuggestionText);
}

function catalogueTitleMatch(
  youtubeBook: Audiobook,
  books: BookEntry[],
): string | undefined {
  for (const candidate of youtubeBook.catalogueMatches) {
    const candidateKey = normaliseSuggestionText(candidate);

    for (const book of books) {
      const title = bookDisplayTitle(book);
      const titleKey = normaliseSuggestionText(title);
      const firstTitleWord = titleKey.split(' ')[0] ?? '';
      if (
        candidateKey === titleKey ||
        titleKey.includes(candidateKey) ||
        (firstTitleWord && candidateKey.includes(firstTitleWord))
      ) {
        return book.title ?? book.name;
      }
    }
  }
  return undefined;
}

function hasTitleKeywordMatch(
  youtubeBook: Audiobook,
  ebookTitles: string[],
): boolean {
  const youtubeWords = wordsOf(youtubeBook.title);
  return ebookTitles.some(
    (title) => overlapCount(youtubeWords, wordsOf(title)) >= 2,
  );
}

function hasAuthorMatch(
  youtubeBook: Audiobook,
  ebookAuthors: string[],
): boolean {
  const youtubeAuthorWords = wordsOf(youtubeBook.author);
  return ebookAuthors.some((author) => {
    const authorWords = wordsOf(author);
    return authorWords.length > 0 && overlapCount(youtubeAuthorWords, authorWords) >= 1;
  });
}

function scoreYoutubeSuggestion(
  youtubeBook: Audiobook,
  books: BookEntry[],
  ebookTitles: string[],
  ebookAuthors: string[],
): YtSuggestion | null {
  const matchedBookTitle = catalogueTitleMatch(youtubeBook, books);
  if (matchedBookTitle) {
    return {
      kind: 'youtube',
      audiobook: youtubeBook,
      score: 20,
      reason: `Matches your ebook "${matchedBookTitle}"`,
      matchedBookTitle,
    };
  }

  if (hasTitleKeywordMatch(youtubeBook, ebookTitles)) {
    return {
      kind: 'youtube',
      audiobook: youtubeBook,
      score: 12,
      reason: 'Title keywords match your library',
    };
  }

  if (hasAuthorMatch(youtubeBook, ebookAuthors)) {
    return {
      kind: 'youtube',
      audiobook: youtubeBook,
      score: 8,
      reason: `By ${youtubeBook.author} — author in your library`,
    };
  }

  return null;
}

export function buildYtSuggestions(
  books: BookEntry[],
  driveAudiobooks: AudiobookEntry[],
  ytCatalog: Audiobook[],
  removedIds: string[],
  baseCatalog: Audiobook[] = getBaseYoutubeCatalog(),
): YtSuggestion[] {
  const existingIds = new Set(ytCatalog.map((book) => book.id));
  const removedSet = new Set(removedIds);
  const ebookTitles = books.map((book) =>
    normaliseSuggestionText(bookDisplayTitle(book)),
  );
  const ebookAuthors = libraryAuthors(books, driveAudiobooks);
  const suggestions: YtSuggestion[] = [];

  for (const youtubeBook of baseCatalog) {
    if (existingIds.has(youtubeBook.id) || removedSet.has(youtubeBook.id)) continue;
    const suggestion = scoreYoutubeSuggestion(
      youtubeBook,
      books,
      ebookTitles,
      ebookAuthors,
    );
    if (suggestion) suggestions.push(suggestion);
  }

  return suggestions.sort((a, b) => b.score - a.score).slice(0, 60);
}
