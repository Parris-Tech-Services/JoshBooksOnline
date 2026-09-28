const listMock = jest.fn();

jest.mock('googleapis', () => ({
  google: {
    auth: { OAuth2: jest.fn(() => ({ setCredentials: jest.fn() })) },
    drive: jest.fn(() => ({ files: { list: listMock } })),
  },
}));

import { listFilesInFolder, listFilesInFolderRecursive } from '@/lib/googleDrive';

const pdf = 'application/pdf';
const epub = 'application/epub+zip';
const folderMime = 'application/vnd.google-apps.folder';
const shortcutMime = 'application/vnd.google-apps.shortcut';

type DriveFile = Record<string, unknown>;

/** Serves `files.list` from a folder map; nested arrays are separate result pages. */
function serveFolders(children: Record<string, DriveFile[][] | DriveFile[]>) {
  listMock.mockImplementation(async ({ q, pageToken }: { q: string; pageToken?: string }) => {
    const folderId = /'([^']+)' in parents/.exec(q)![1];
    const entry = children[folderId] ?? [];
    const pages = Array.isArray(entry[0]) ? (entry as DriveFile[][]) : [entry as DriveFile[]];
    const index = pageToken ? Number(pageToken) : 0;
    return {
      data: {
        files: pages[index],
        nextPageToken: index + 1 < pages.length ? String(index + 1) : undefined,
      },
    };
  });
}

const book = (id: string, name: string, extra: DriveFile = {}) => ({
  id,
  name,
  mimeType: pdf,
  size: '2048',
  modifiedTime: '2026-01-01T00:00:00Z',
  ...extra,
});

describe('listFilesInFolder', () => {
  beforeEach(() => listMock.mockReset());

  it('maps Drive files to library entries with progress and stored metadata', async () => {
    serveFolders({
      f: [
        book('b1', 'Dune.pdf', {
          thumbnailLink: 'https://thumb',
          appProperties: {
            progressPercentage: '42',
            lastLocation: 'epubcfi(/6/4)',
            lastOpened: '2026-02-02',
            m_src: 'google',
            m_title: 'Dune',
            m_authors: 'Frank Herbert; ',
            m_seriesIdx: '1',
            m_pages: 'lots',
            m_gbid: 'abc',
          },
        }),
      ],
    });

    const [entry] = await listFilesInFolder('token', 'f', 'Unsorted');
    expect(entry).toMatchObject({
      id: 'b1',
      name: 'Dune.pdf',
      mimeType: pdf,
      size: 2048,
      modifiedTime: '2026-01-01T00:00:00Z',
      thumbnailLink: 'https://thumb',
      source: 'Unsorted',
      format: 'pdf',
      readingProgress: 42,
      lastLocation: 'epubcfi(/6/4)',
      lastOpened: '2026-02-02',
      title: 'Dune',
      authors: ['Frank Herbert'],
      seriesIndex: 1,
      pageCount: undefined,
      coverUrl: expect.stringContaining('id=abc'),
      metadataSource: 'google',
    });
  });

  it('defaults progress, location and size when Drive omits them', async () => {
    serveFolders({ f: [book('b1', 'Plain.epub', { mimeType: epub, size: undefined })] });
    const [entry] = await listFilesInFolder('token', 'f', 'Unsorted');
    expect(entry).toMatchObject({ format: 'epub', size: 0, readingProgress: 0, lastLocation: '' });
    expect(entry.thumbnailLink).toBeUndefined();
    expect(entry.metadataSource).toBeUndefined();
  });

  it('follows shortcuts to their target and shows each book once', async () => {
    serveFolders({
      f: [
        [
          {
            id: 's1',
            name: 'Shortcut to Dune',
            mimeType: shortcutMime,
            shortcutDetails: { targetId: 'b1', targetMimeType: pdf },
            modifiedTime: 't',
          },
          { id: 'broken', name: 'Broken shortcut', mimeType: shortcutMime, modifiedTime: 't' },
        ],
        [book('b1', 'Dune.pdf')],
      ],
    });

    const entries = await listFilesInFolder('token', 'f', 'Unsorted');
    expect(entries.map((e) => [e.id, e.name, e.mimeType])).toEqual([['b1', 'Shortcut to Dune', pdf]]);
  });

  it('skips unsupported formats and books hidden from the library', async () => {
    serveFolders({
      f: [
        book('img', 'cover.jpg', { mimeType: 'image/jpeg' }),
        book('gone', 'Removed.pdf', { appProperties: { m_hidden: '1' } }),
        book('kept', 'Kept.pdf', { appProperties: { m_hidden: '0' } }),
      ],
    });
    const entries = await listFilesInFolder('token', 'f', 'Unsorted');
    expect(entries.map((e) => e.id)).toEqual(['kept']);
  });
});

describe('listFilesInFolderRecursive', () => {
  beforeEach(() => listMock.mockReset());

  it('includes books from nested folders, tagged with the requested source', async () => {
    serveFolders({
      root: [
        [book('top', 'Top.pdf'), { id: 'sub', name: 'Series', mimeType: folderMime }],
        [book('hidden', 'Hidden.pdf', { appProperties: { m_hidden: '1' } })],
      ],
      sub: [book('nested', 'Nested.epub', { mimeType: epub }), book('txt', 'x.jpg', { mimeType: 'image/png' })],
    });

    const entries = await listFilesInFolderRecursive('token', 'root', 'Unsorted');
    expect(entries.map((e) => [e.id, e.format, e.source])).toEqual([
      ['top', 'pdf', 'Unsorted'],
      ['nested', 'epub', 'Unsorted'],
    ]);
  });

  it('does not follow shortcuts (documented difference from listFilesInFolder)', async () => {
    serveFolders({
      root: [
        {
          id: 's1',
          name: 'Shortcut',
          mimeType: shortcutMime,
          shortcutDetails: { targetId: 'b1', targetMimeType: pdf },
        },
      ],
    });
    await expect(listFilesInFolderRecursive('token', 'root')).resolves.toEqual([]);
  });
});
