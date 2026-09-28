const listMock = jest.fn();
const getMock = jest.fn();

jest.mock('googleapis', () => ({
  google: {
    auth: { OAuth2: jest.fn(() => ({ setCredentials: jest.fn() })) },
    drive: jest.fn(() => ({ files: { list: listMock, get: getMock } })),
  },
}));

import { getAudiobookTracks } from '@/lib/googleDrive';

const folderMime = 'application/vnd.google-apps.folder';
const shortcutMime = 'application/vnd.google-apps.shortcut';

type DriveFile = Record<string, unknown>;

/** Serves `files.list` from a folder map; `pages` splits a folder into pages. */
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

const audio = (id: string, name: string, extra: DriveFile = {}) => ({
  id,
  name,
  mimeType: 'audio/mpeg',
  size: '100',
  ...extra,
});

describe('getAudiobookTracks: loose-file audiobooks', () => {
  beforeEach(() => {
    listMock.mockReset();
    getMock.mockReset();
  });

  it('returns just the file when it has no parent folder', async () => {
    getMock.mockResolvedValue({ data: { id: 'f1', name: 'Solo Book.mp3', size: '42' } });
    await expect(getAudiobookTracks('token', 'f1', false)).resolves.toEqual([
      { id: 'f1', name: 'Solo Book.mp3', size: 42 },
    ]);
    expect(listMock).not.toHaveBeenCalled();
  });

  it('gathers same-book sibling chapters across pages, in natural order', async () => {
    getMock.mockResolvedValue({
      data: { id: 'c1', name: 'Great Book 1.mp3', size: '10', parents: ['p'] },
    });
    serveFolders({
      p: [
        [audio('c10', 'Great Book 10.mp3'), audio('c1', 'Great Book 1.mp3'), audio('x', 'Other Book 1.mp3')],
        [audio('c2', 'Great Book 2.mp3'), { id: 'img', name: 'Great Book cover.jpg', mimeType: 'image/jpeg' }],
      ],
    });

    const tracks = await getAudiobookTracks('token', 'c1', false);
    expect(tracks.map((t) => t.name)).toEqual(['Great Book 1.mp3', 'Great Book 2.mp3', 'Great Book 10.mp3']);
    expect(listMock).toHaveBeenCalledTimes(2);
  });

  it('groups by the manual group key when one is set, and streams shortcut targets', async () => {
    getMock.mockResolvedValue({
      data: {
        id: 's1',
        name: 'Anything.mp3',
        parents: ['p'],
        mimeType: shortcutMime,
        shortcutDetails: { targetId: 'real1', targetMimeType: 'audio/mpeg' },
        appProperties: { m_audio_group: 'my-group' },
      },
    });
    serveFolders({
      p: [
        {
          id: 's1',
          name: 'Anything.mp3',
          mimeType: shortcutMime,
          shortcutDetails: { targetId: 'real1', targetMimeType: 'audio/mpeg' },
          appProperties: { m_audio_group: 'my-group' },
        },
        audio('other', 'Unrelated.mp3', { appProperties: { m_audio_group: 'my-group' } }),
        audio('nope', 'Anything 2.mp3'),
      ],
    });

    const tracks = await getAudiobookTracks('token', 's1', false);
    expect(tracks).toEqual([
      { id: 'real1', name: 'Anything.mp3', size: 0 },
      { id: 'other', name: 'Unrelated.mp3', size: 100 },
    ]);
  });

  it('falls back to the file itself when no sibling matches', async () => {
    getMock.mockResolvedValue({
      data: { id: 'c1', name: 'Lonely 1.mp3', size: '7', parents: ['p'] },
    });
    serveFolders({ p: [audio('x', 'Something Else.mp3')] });
    await expect(getAudiobookTracks('token', 'c1', false)).resolves.toEqual([
      { id: 'c1', name: 'Lonely 1.mp3', size: 7 },
    ]);
  });
});

describe('getAudiobookTracks: folder audiobooks', () => {
  beforeEach(() => {
    listMock.mockReset();
    getMock.mockReset();
    getMock.mockImplementation(async ({ fileId }: { fileId: string }) =>
      fileId === 'shortcut-folder'
        ? { data: { mimeType: shortcutMime, shortcutDetails: { targetId: 'book' } } }
        : { data: { mimeType: folderMime } },
    );
  });

  it('collects audio from the folder and nested disc folders, sorted naturally overall', async () => {
    serveFolders({
      book: [
        [
          { id: 'disc2', name: 'Disc 2', mimeType: folderMime },
          audio('t10', 'Track 10.mp3'),
          { id: 'notes', name: 'notes.txt', mimeType: 'text/plain' },
        ],
        [
          {
            id: 'disc1-shortcut',
            name: 'Disc 1',
            mimeType: shortcutMime,
            shortcutDetails: { targetId: 'disc1', targetMimeType: folderMime },
          },
          audio('t2', 'Track 2.mp3', { size: undefined }),
        ],
      ],
      disc1: [audio('d1a', 'Disc1 Track 1.mp3')],
      disc2: [
        {
          id: 'short-audio',
          name: 'Disc2 Track 1.mp3',
          mimeType: shortcutMime,
          shortcutDetails: { targetId: 'real-audio', targetMimeType: 'audio/mpeg' },
        },
      ],
    });

    const tracks = await getAudiobookTracks('token', 'book', true);
    expect(tracks).toEqual([
      { id: 'd1a', name: 'Disc1 Track 1.mp3', size: 100 },
      { id: 'real-audio', name: 'Disc2 Track 1.mp3', size: 0 },
      { id: 't2', name: 'Track 2.mp3', size: 0 },
      { id: 't10', name: 'Track 10.mp3', size: 100 },
    ]);
  });

  it('resolves a shortcut to a folder before listing it', async () => {
    serveFolders({ book: [audio('t1', 'Track 1.mp3')] });
    const tracks = await getAudiobookTracks('token', 'shortcut-folder', true);
    expect(tracks.map((t) => t.id)).toEqual(['t1']);
    expect(listMock.mock.calls[0][0].q).toContain("'book' in parents");
  });

  it('returns no tracks for an empty folder', async () => {
    serveFolders({ book: [] });
    await expect(getAudiobookTracks('token', 'book', true)).resolves.toEqual([]);
  });
});
