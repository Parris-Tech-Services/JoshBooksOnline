const getMock = jest.fn();
const listMock = jest.fn();
const updateMock = jest.fn();
const createMock = jest.fn();
const copyMock = jest.fn();

jest.mock('googleapis', () => ({
  google: {
    auth: { OAuth2: jest.fn(() => ({ setCredentials: jest.fn() })) },
    drive: jest.fn(() => ({
      files: {
        get: getMock,
        list: listMock,
        update: updateMock,
        create: createMock,
        copy: copyMock,
      },
    })),
  },
}));

import {
  getAllLibraryFiles,
  groupAudiobooks,
  importAudioFolderToAudiobooks,
  listFilesInFolderRecursive,
  removeBookFromLibrary,
  ungroupAudiobook,
} from '@/lib/googleDrive';

const folderMime = 'application/vnd.google-apps.folder';
const audioMime = 'audio/mpeg';
const pdfMime = 'application/pdf';
const epubMime = 'application/epub+zip';

describe('Google Drive library operations', () => {
  beforeEach(() => {
    getMock.mockReset();
    listMock.mockReset();
    updateMock.mockReset();
    createMock.mockReset();
    copyMock.mockReset();
    updateMock.mockResolvedValue({ data: {} });
    createMock.mockResolvedValue({ data: { id: 'shortcut' } });
    copyMock.mockResolvedValue({ data: { id: 'copy' } });
  });

  it('ungroups every audio sibling carrying the same manual group id', async () => {
    getMock.mockResolvedValue({
      data: {
        id: 'a1',
        name: 'Book Part 1.mp3',
        mimeType: audioMime,
        parents: ['parent'],
        appProperties: { m_audio_group: 'group-1' },
      },
    });
    listMock.mockResolvedValue({
      data: {
        files: [
          { id: 'a1', mimeType: audioMime, appProperties: { m_audio_group: 'group-1' } },
          { id: 'a2', mimeType: audioMime, appProperties: { m_audio_group: 'group-1' } },
          { id: 'other', mimeType: audioMime, appProperties: { m_audio_group: 'group-2' } },
          { id: 'folder', mimeType: folderMime, appProperties: { m_audio_group: 'group-1' } },
        ],
      },
    });

    await ungroupAudiobook('token', 'a1');

    expect(updateMock).toHaveBeenCalledTimes(2);
    expect(updateMock.mock.calls.map(([arg]) => arg.fileId).sort()).toEqual(['a1', 'a2']);
    expect(updateMock.mock.calls[0][0].requestBody.appProperties).toEqual({
      m_audio_group: '',
      m_audio_group_title: '',
    });
  });

  it('groups loose audiobook tracks under one manual group id', async () => {
    getMock
      .mockResolvedValueOnce({
        data: {
          id: 'a1',
          name: 'Example Book Part 1.mp3',
          mimeType: audioMime,
          appProperties: {},
        },
      })
      .mockResolvedValueOnce({
        data: {
          id: 'a1',
          name: 'Example Book Part 1.mp3',
          size: '10',
          mimeType: audioMime,
          parents: ['parent'],
          appProperties: {},
        },
      })
      .mockResolvedValueOnce({
        data: {
          id: 'a2',
          name: 'Example Book Part 2.mp3',
          mimeType: audioMime,
          appProperties: {},
        },
      })
      .mockResolvedValueOnce({
        data: {
          id: 'a2',
          name: 'Example Book Part 2.mp3',
          size: '11',
          mimeType: audioMime,
          parents: ['parent'],
          appProperties: {},
        },
      });

    listMock.mockResolvedValue({
      data: {
        files: [
          { id: 'a1', name: 'Example Book Part 1.mp3', size: '10', mimeType: audioMime },
          { id: 'a2', name: 'Example Book Part 2.mp3', size: '11', mimeType: audioMime },
        ],
      },
    });

    await groupAudiobooks('token', ['a1', 'a2'], ' Example Book ');

    expect(updateMock).toHaveBeenCalledTimes(2);
    const properties = updateMock.mock.calls[0][0].requestBody.appProperties;
    expect(properties.m_audio_group_title).toBe('Example Book');
    expect(properties.m_audio_group).toEqual(expect.any(String));
  });

  it('imports a folder containing loose audio as one audiobook folder', async () => {
    listMock.mockResolvedValue({
      data: {
        files: [{ id: 'track-1', name: 'Track 1.mp3', mimeType: audioMime }],
      },
    });
    getMock.mockResolvedValue({
      data: {
        id: 'folder-1',
        name: 'Book Folder',
        mimeType: folderMime,
        parents: [],
      },
    });

    const result = await importAudioFolderToAudiobooks('token', 'folder-1');

    expect(result).toEqual({ importedCount: 1 });
    expect(updateMock).toHaveBeenCalledWith(
      expect.objectContaining({
        fileId: 'folder-1',
        addParents: expect.any(String),
      }),
    );
  });

  it('recursively lists supported ebook files and skips hidden entries', async () => {
    listMock.mockImplementation(({ q }: { q: string }) => {
      const parent = q.match(/^'([^']+)' in parents/)?.[1];
      if (parent === 'root') {
        return Promise.resolve({
          data: {
            files: [
              { id: 'nested', name: 'Nested', mimeType: folderMime },
              {
                id: 'pdf',
                name: 'Root.pdf',
                mimeType: pdfMime,
                size: '100',
                modifiedTime: '2026-09-01T00:00:00Z',
              },
            ],
          },
        });
      }
      if (parent === 'nested') {
        return Promise.resolve({
          data: {
            files: [
              {
                id: 'epub',
                name: 'Nested.epub',
                mimeType: epubMime,
                size: '200',
                modifiedTime: '2026-09-02T00:00:00Z',
                appProperties: { m_src: 'manual', m_title: 'Nested Book' },
              },
              {
                id: 'hidden',
                name: 'Hidden.pdf',
                mimeType: pdfMime,
                appProperties: { m_hidden: '1' },
              },
            ],
          },
        });
      }
      return Promise.resolve({ data: { files: [] } });
    });

    const books = await listFilesInFolderRecursive('token', 'root', 'Unsorted');

    expect(books.map((book) => book.id).sort()).toEqual(['epub', 'pdf']);
    expect(books.find((book) => book.id === 'epub')).toMatchObject({
      title: 'Nested Book',
      format: 'epub',
    });
  });

  it('scans an empty configured library without failing when Local Books is absent', async () => {
    listMock.mockImplementation(({ q }: { q: string }) => {
      if (q.includes("name = 'Local Books'")) {
        return Promise.resolve({ data: { files: [] } });
      }
      return Promise.resolve({ data: { files: [] } });
    });

    await expect(getAllLibraryFiles('token')).resolves.toEqual([]);
    expect(listMock.mock.calls.length).toBeGreaterThan(5);
  });

  it('removes a book non-destructively even when parent detachment fails', async () => {
    updateMock
      .mockRejectedValueOnce(new Error('shared folder cannot detach'))
      .mockResolvedValueOnce({ data: {} });

    await removeBookFromLibrary('token', 'book-1', 'Unsorted');

    expect(updateMock).toHaveBeenCalledTimes(2);
    expect(updateMock.mock.calls[1][0]).toEqual({
      fileId: 'book-1',
      requestBody: { appProperties: { m_hidden: '1' } },
    });
  });
});
