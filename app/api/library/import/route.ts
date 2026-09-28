import { getServerSession } from 'next-auth/next';
import { NextRequest, NextResponse } from 'next/server';
import authOptions from '@/lib/auth';
import {
  listFilesInFolderRecursive,
  getFileMetadata,
  addFileToUnsorted,
  getMimeTypeFormat,
  isAudioMimeType,
  importAudioFileToAudiobooks,
  importAudioFolderToAudiobooks,
} from '@/lib/googleDrive';
import { clearLibraryCache } from '@/lib/libraryCache';
import {
  parseDriveImportRequest,
  type DriveImportItem,
  type ImportTarget,
} from '@/lib/driveImportRequest';
import type { BookEntry } from '@/types/books';

export type { ImportTarget } from '@/lib/driveImportRequest';

type ImportError = { itemName: string; reason: string };

type ImportAccumulator = {
  importedFiles: BookEntry[];
  errors: ImportError[];
  importedAudiobookCount: number;
};

function addError(result: ImportAccumulator, itemName: string, reason: string): void {
  result.errors.push({ itemName, reason });
}

function importModes(target: ImportTarget): {
  ebooks: boolean;
  audiobooks: boolean;
} {
  return {
    ebooks: target === 'auto' || target === 'ebooks',
    audiobooks: target === 'auto' || target === 'audiobooks',
  };
}

async function importFolderEbooks(
  accessToken: string,
  item: DriveImportItem,
  result: ImportAccumulator,
): Promise<void> {
  const folderFiles = await listFilesInFolderRecursive(
    accessToken,
    item.id,
    'Unsorted',
  );

  for (const book of folderFiles) {
    await addFileToUnsorted(accessToken, book.id);
  }
  result.importedFiles.push(...folderFiles);
}

async function importFolderAudiobooks(
  accessToken: string,
  item: DriveImportItem,
  result: ImportAccumulator,
  shouldReportEmpty: boolean,
): Promise<void> {
  const audioResult = await importAudioFolderToAudiobooks(accessToken, item.id);
  result.importedAudiobookCount += audioResult.importedCount;

  if (audioResult.importedCount === 0 && audioResult.reason && shouldReportEmpty) {
    addError(result, item.name, audioResult.reason);
  }
}

async function importFolder(
  accessToken: string,
  item: DriveImportItem,
  target: ImportTarget,
  result: ImportAccumulator,
): Promise<void> {
  const modes = importModes(target);
  if (modes.ebooks) {
    await importFolderEbooks(accessToken, item, result);
  }
  if (modes.audiobooks) {
    await importFolderAudiobooks(
      accessToken,
      item,
      result,
      target === 'audiobooks' || !modes.ebooks,
    );
  }
}

async function importAudioFile(
  accessToken: string,
  item: DriveImportItem,
  result: ImportAccumulator,
): Promise<void> {
  const audioResult = await importAudioFileToAudiobooks(accessToken, item.id);
  if (!audioResult.ok) {
    addError(
      result,
      item.name,
      audioResult.reason ?? 'Audiobook import failed',
    );
    return;
  }
  result.importedAudiobookCount += 1;
}

async function importEbookFile(
  accessToken: string,
  item: DriveImportItem,
  metadata: NonNullable<Awaited<ReturnType<typeof getFileMetadata>>>,
  format: NonNullable<ReturnType<typeof getMimeTypeFormat>>,
  result: ImportAccumulator,
): Promise<void> {
  const linked = await addFileToUnsorted(accessToken, metadata.id);
  if (!linked) {
    addError(result, item.name, 'Could not add file to Unsorted folder');
    return;
  }

  result.importedFiles.push({
    id: metadata.id,
    name: item.name,
    mimeType: metadata.mimeType,
    size: metadata.size,
    modifiedTime: metadata.modifiedTime,
    source: 'Unsorted',
    format,
    readingProgress: 0,
    lastLocation: '',
  });
}

function unsupportedReason(isAudio: boolean, mimeType: string): string {
  if (isAudio) return 'Audio import is only available from the Audiobooks tab';
  return `Unsupported file format: ${mimeType}. Supported: PDF, EPUB, TXT, DOCX, MP3, M4A, M4B, and other audio types.`;
}

async function importFile(
  accessToken: string,
  item: DriveImportItem,
  target: ImportTarget,
  result: ImportAccumulator,
): Promise<void> {
  const metadata = await getFileMetadata(accessToken, item.id);
  if (!metadata) {
    addError(result, item.name, 'Could not retrieve file metadata');
    return;
  }

  const modes = importModes(target);
  const ebookFormat = getMimeTypeFormat(metadata.mimeType);
  const audio = isAudioMimeType(metadata.mimeType, metadata.name);

  if (audio && modes.audiobooks) {
    await importAudioFile(accessToken, item, result);
    return;
  }
  if (ebookFormat && modes.ebooks) {
    await importEbookFile(accessToken, item, metadata, ebookFormat, result);
    return;
  }

  addError(result, item.name, unsupportedReason(audio, metadata.mimeType));
}

async function importItem(
  accessToken: string,
  item: DriveImportItem,
  target: ImportTarget,
  result: ImportAccumulator,
): Promise<void> {
  try {
    if (item.type === 'folder') {
      await importFolder(accessToken, item, target, result);
      return;
    }
    await importFile(accessToken, item, target, result);
  } catch (error) {
    addError(
      result,
      item.name,
      error instanceof Error ? error.message : 'Unknown error',
    );
  }
}

/**
 * POST /api/library/import
 * Import files/folders from Google Drive Picker selection.
 * Ebooks are linked into Unsorted; audio files/folders into Audiobooks.
 */
export async function POST(request: NextRequest) {
  try {
    const session = await getServerSession(authOptions);
    if (!session?.accessToken) {
      return NextResponse.json(
        { error: 'Unauthorized: No access token available' },
        { status: 401 },
      );
    }

    const parsed = parseDriveImportRequest(await request.json());
    if (!parsed.ok) {
      return NextResponse.json({ error: parsed.error }, { status: 400 });
    }

    const result: ImportAccumulator = {
      importedFiles: [],
      errors: [],
      importedAudiobookCount: 0,
    };

    for (const item of parsed.items) {
      await importItem(session.accessToken, item, parsed.target, result);
    }

    await clearLibraryCache(
      session.accessToken,
      session.user?.email ?? undefined,
    );

    return NextResponse.json({
      importedCount: result.importedFiles.length,
      importedAudiobookCount: result.importedAudiobookCount,
      files: result.importedFiles,
      errors: result.errors,
    });
  } catch (error) {
    console.error('[/api/library/import] Error:', error);
    const message =
      error instanceof Error ? error.message : 'Internal server error';
    return NextResponse.json(
      { error: `Import failed: ${message}` },
      { status: 500 },
    );
  }
}
