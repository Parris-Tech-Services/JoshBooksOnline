const listMock = jest.fn();
const getMock = jest.fn();
const updateMock = jest.fn();
const createMock = jest.fn();
const copyMock = jest.fn();

jest.mock('googleapis', () => ({
  google: {
    auth: { OAuth2: jest.fn(() => ({ setCredentials: jest.fn() })) },
    drive: jest.fn(() => ({
      files: { list: listMock, get: getMock, update: updateMock, create: createMock, copy: copyMock },
    })),
  },
}));

import { addFileToAudiobooksFolder, addFileToUnsorted, importAudioFolderToAudiobooks } from '@/lib/googleDrive';

const folderMime = 'application/vnd.google-apps.folder';
const shortcutMime = 'application/vnd.google-apps.shortcut';

type Child = { id: string; name: string; mimeType: string; shortcutDetails?: { targetId: string; targetMimeType: string } };

/** Serves folder listings for the import scan, and file lookups for linking. */
function driveWith(folders: Record<string, Child[]>, parentsById: Record<string, string[]> = {}) {
  listMock.mockImplementation(async ({ q }: { q: string }) => {
    const folderId = /'([^']+)' in parents/.exec(q)![1];
    return { data: { files: folders[folderId] ?? [] } };
  });
  getMock.mockImplementation(async ({ fileId }: { fileId: string }) => ({
    data: { id: fileId, name: `name-${fileId}`, mimeType: folderMime, parents: parentsById[fileId] ?? ['elsewhere'] },
  }));
  createMock.mockResolvedValue({ data: { id: 'new-shortcut' } });
}

/** Ids that were linked into a library folder (via shortcut, parent move or copy). */
function linkedIds(): string[] {
  return [
    ...createMock.mock.calls.map(([args]) => args.requestBody.shortcutDetails.targetId),
    ...updateMock.mock.calls.map(([args]) => args.fileId),
    ...copyMock.mock.calls.map(([args]) => args.fileId),
  ];
}

const audio = (id: string): Child => ({ id, name: `${id}.mp3`, mimeType: 'audio/mpeg' });
const folder = (id: string): Child => ({ id, name: id, mimeType: folderMime });

beforeEach(() => {
  for (const mock of [listMock, getMock, updateMock, createMock, copyMock]) mock.mockReset();
  jest.spyOn(console, 'warn').mockImplementation(() => {});
  jest.spyOn(console, 'error').mockImplementation(() => {});
});

afterEach(() => jest.restoreAllMocks());

describe('adding a Drive file to a library folder', () => {
  it('does nothing when the file is already in the folder', async () => {
    getMock.mockResolvedValue({ data: { id: 'f', name: 'f.pdf', mimeType: 'application/pdf', parents: [] } });
    const target = await (async () => {
      await addFileToUnsorted('token', 'f');
      return updateMock.mock.calls[0][0].addParents as string;
    })();
    getMock.mockResolvedValue({ data: { id: 'f', name: 'f.pdf', mimeType: 'application/pdf', parents: [target] } });
    updateMock.mockReset();

    await expect(addFileToUnsorted('token', 'f')).resolves.toBe(true);
    expect(updateMock).not.toHaveBeenCalled();
    expect(createMock).not.toHaveBeenCalled();
  });

  it('moves a parentless file straight into the folder', async () => {
    getMock.mockResolvedValue({ data: { id: 'f', name: 'f.pdf', mimeType: 'application/pdf', parents: [] } });
    await expect(addFileToUnsorted('token', 'f')).resolves.toBe(true);
    expect(updateMock).toHaveBeenCalledWith(expect.objectContaining({ fileId: 'f', addParents: expect.any(String) }));
  });

  it('links a file that lives elsewhere with a shortcut, keeping its original place', async () => {
    getMock.mockResolvedValue({ data: { id: 'f', name: 'Dune.pdf', mimeType: 'application/pdf', parents: ['mine'] } });
    await expect(addFileToUnsorted('token', 'f')).resolves.toBe(true);
    expect(createMock).toHaveBeenCalledWith(
      expect.objectContaining({
        requestBody: expect.objectContaining({
          name: 'Dune.pdf',
          mimeType: shortcutMime,
          shortcutDetails: { targetId: 'f', targetMimeType: 'application/pdf' },
        }),
      }),
    );
    expect(updateMock).not.toHaveBeenCalled();
    expect(copyMock).not.toHaveBeenCalled();
  });

  it('falls back to a copy when a shortcut cannot be created', async () => {
    getMock.mockResolvedValue({ data: { id: 'f', name: 'Dune.pdf', mimeType: 'application/pdf', parents: ['mine'] } });
    createMock.mockRejectedValue(new Error('shortcuts disabled'));
    await expect(addFileToUnsorted('token', 'f')).resolves.toBe(true);
    expect(copyMock).toHaveBeenCalledWith(expect.objectContaining({ fileId: 'f', requestBody: expect.objectContaining({ name: 'Dune.pdf' }) }));
  });

  it('reports failure instead of throwing when Drive refuses', async () => {
    getMock.mockRejectedValue(new Error('404'));
    await expect(addFileToAudiobooksFolder('token', 'missing')).resolves.toBe(false);
    createMock.mockReset();
    getMock.mockResolvedValue({ data: { id: 'f', name: 'x', mimeType: 'audio/mpeg', parents: ['mine'] } });
    createMock.mockRejectedValue(new Error('no'));
    copyMock.mockRejectedValue(new Error('no'));
    await expect(addFileToAudiobooksFolder('token', 'f')).resolves.toBe(false);
  });
});

describe('importAudioFolderToAudiobooks', () => {
  it('refuses a folder with no audio anywhere inside it', async () => {
    driveWith({ root: [folder('empty'), { id: 'n', name: 'notes.txt', mimeType: 'text/plain' }], empty: [] });
    await expect(importAudioFolderToAudiobooks('token', 'root')).resolves.toEqual({
      importedCount: 0,
      reason: 'No audio files found in folder',
    });
    expect(linkedIds()).toEqual([]);
  });

  it('imports a single-book folder (loose audio at the top) as one audiobook', async () => {
    driveWith({ root: [audio('t1'), audio('t2'), folder('extras')], extras: [audio('bonus')] });
    await expect(importAudioFolderToAudiobooks('token', 'root')).resolves.toEqual({ importedCount: 1 });
    expect(linkedIds()).toEqual(['root']);
  });

  it('imports each audio-bearing book in a collection folder separately, following shortcuts', async () => {
    driveWith({
      root: [
        folder('book1'),
        { id: 'sc', name: 'Book 2', mimeType: shortcutMime, shortcutDetails: { targetId: 'book2', targetMimeType: folderMime } },
        folder('no-audio'),
      ],
      book1: [audio('a')],
      book2: [folder('disc1')],
      disc1: [{ id: 'sa', name: 'x', mimeType: shortcutMime, shortcutDetails: { targetId: 'real', targetMimeType: 'audio/mpeg' } }],
      'no-audio': [],
    });
    await expect(importAudioFolderToAudiobooks('token', 'root')).resolves.toEqual({ importedCount: 2 });
    expect(linkedIds()).toEqual(['book1', 'book2']);
  });

  it('falls back to importing the collection as one when no book links', async () => {
    driveWith({ root: [folder('book1')], book1: [audio('a')] }, { root: ['elsewhere'] });
    createMock.mockImplementation(async ({ requestBody }: { requestBody: { shortcutDetails: { targetId: string } } }) => {
      if (requestBody.shortcutDetails.targetId === 'book1') throw new Error('no shortcut');
      return { data: { id: 'ok' } };
    });
    copyMock.mockRejectedValue(new Error('no copy'));
    await expect(importAudioFolderToAudiobooks('token', 'root')).resolves.toEqual({ importedCount: 1 });
  });

  it('explains when the folder cannot be linked', async () => {
    driveWith({ root: [audio('t1')] });
    createMock.mockRejectedValue(new Error('no'));
    copyMock.mockRejectedValue(new Error('no'));
    await expect(importAudioFolderToAudiobooks('token', 'root')).resolves.toEqual({
      importedCount: 0,
      reason: 'Could not add folder to Audiobooks',
    });
  });
});
