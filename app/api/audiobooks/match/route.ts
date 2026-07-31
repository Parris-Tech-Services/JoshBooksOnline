import { getServerSession } from 'next-auth/next';
import { NextResponse } from 'next/server';
import authOptions from '@/lib/auth';
import { FIXED_FOLDERS, listFolderItems, listFilesInFolder, fuzzyMatchNames, getAllLibraryFiles } from '@/lib/googleDrive';
import type { BookEntry } from '@/types/books';

const AUDIOBOOK_FOLDERS = [
  {
    audiobookName: 'Outlander Series',
    audiobookFolderId: '1SBqmfghmj5gqxWRnCrxbHP65I23ohlcQ',
  },
  {
    audiobookName: 'Other Audiobooks',
    audiobookFolderId: '1NRY6dXCpILRzfG4yYTpisGqLnqx2ECEQ',
  },
];

interface MatchResult {
  audiobookName: string;
  audiobookFolderId: string;
  matchedEbook: BookEntry | null;
  alreadyImported: boolean;
}

export async function GET() {
  const session = await getServerSession(authOptions);
  if (!session?.accessToken) {
    return NextResponse.json({ error: 'Unauthorized' }, { status: 401 });
  }

  try {
    const libraryBooks = await getAllLibraryFiles(session.accessToken);
    const libraryBookIds = new Set(libraryBooks.map((book) => book.id));
    const results: MatchResult[] = [];

    const allEbooks = await findAllFixedFolderEbooks(session.accessToken);

    for (const audiobook of AUDIOBOOK_FOLDERS) {
      const items = await listFolderItems(session.accessToken, audiobook.audiobookFolderId);
      const searchTerms = [
        audiobook.audiobookName,
        ...items.map((item) => item.name),
      ].filter(Boolean);

      const matchedBook = findBestMatch(searchTerms, allEbooks);
      const alreadyImported = matchedBook ? libraryBookIds.has(matchedBook.id) : false;

      results.push({
        audiobookName: audiobook.audiobookName,
        audiobookFolderId: audiobook.audiobookFolderId,
        matchedEbook: matchedBook,
        alreadyImported,
      });
    }

    return NextResponse.json(results);
  } catch (error) {
    console.error('[/api/audiobooks/match] Error:', error);
    return NextResponse.json(
      { error: 'Failed to match audiobooks' },
      { status: 500 }
    );
  }
}

async function findAllFixedFolderEbooks(accessToken: string): Promise<BookEntry[]> {
  const results: BookEntry[] = [];

  for (const [source, folderId] of Object.entries(FIXED_FOLDERS)) {
    const books = await listFilesInFolder(accessToken, folderId, source as any);
    results.push(...books);
  }

  return results;
}

function findBestMatch(searchTerms: string[], ebooks: BookEntry[]): BookEntry | null {
  for (const term of searchTerms) {
    for (const ebook of ebooks) {
      if (fuzzyMatchNames(term, ebook.name)) {
        return ebook;
      }
    }
  }

  return null;
}
