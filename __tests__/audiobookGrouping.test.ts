const listMock = jest.fn();
const getMock = jest.fn();
const updateMock = jest.fn();

jest.mock('googleapis', () => ({
  google: {
    auth: { OAuth2: jest.fn(() => ({ setCredentials: jest.fn() })) },
    drive: jest.fn(() => ({ files: { list: listMock, get: getMock, update: updateMock } })),
  },
}));

import { groupAudiobooks, ungroupAudiobook } from '@/lib/googleDrive';

const folderMime = 'application/vnd.google-apps.folder';

type DriveFile = { id: string; name: string; mimeType: string; parents?: string[]; appProperties?: Record<string, string> };

/**
 * A tiny in-memory Drive: files keyed by id, children found by parent, and
 * appProperties updates merged the way Drive does ('' / null clear a key).
 */
function fakeDrive(files: DriveFile[]) {
  const byId = new Map(files.map((f) => [f.id, { ...f, appProperties: { ...(f.appProperties ?? {}) } }]));
  getMock.mockImplementation(async ({ fileId }: { fileId: string }) => {
    const file = byId.get(fileId);
    if (!file) throw new Error(`no file ${fileId}`);
    return { data: file };
  });
  listMock.mockImplementation(async ({ q }: { q: string }) => {
    const parent = /'([^']+)' in parents/.exec(q)![1];
    return { data: { files: [...byId.values()].filter((f) => f.parents?.includes(parent)) } };
  });
  updateMock.mockImplementation(async ({ fileId, requestBody }: { fileId: string; requestBody: { appProperties: Record<string, string | null> } }) => {
    const file = byId.get(fileId)!;
    for (const [key, value] of Object.entries(requestBody.appProperties)) {
      if (value === null || value === '') delete file.appProperties[key];
      else file.appProperties[key] = value;
    }
    return { data: { id: fileId } };
  });
  return byId;
}

const mp3 = (id: string, name: string, extra: Partial<DriveFile> = {}): DriveFile => ({
  id,
  name,
  mimeType: 'audio/mpeg',
  parents: ['shelf'],
  ...extra,
});

beforeEach(() => {
  listMock.mockReset();
  getMock.mockReset();
  updateMock.mockReset();
  jest.spyOn(Date, 'now').mockReturnValue(1_700_000_000_000);
});

afterEach(() => jest.restoreAllMocks());

describe('groupAudiobooks', () => {
  it('labels every chapter of every selected audiobook with one group id and title', async () => {
    const drive = fakeDrive([
      mp3('a1', 'Book A 1.mp3'),
      mp3('a2', 'Book A 2.mp3'),
      mp3('b1', 'Book B 1.mp3'),
      mp3('other', 'Unrelated.mp3'),
    ]);

    await groupAudiobooks('token', ['a1', 'b1'], '  My Box Set  ');

    const expectedId = `my-box-set-${(1_700_000_000_000).toString(36)}`;
    for (const id of ['a1', 'a2', 'b1']) {
      expect(drive.get(id)!.appProperties).toEqual({ m_audio_group: expectedId, m_audio_group_title: 'My Box Set' });
    }
    expect(drive.get('other')!.appProperties).toEqual({});
    expect(updateMock).toHaveBeenCalledTimes(3);
  });

  it.each([
    [['a1'], 'Title', 'at least two audiobooks'],
    [['a1', 'b1'], '   ', 'at least two audiobooks'],
  ])('rejects %j titled %j before touching Drive', async (ids, title, message) => {
    fakeDrive([mp3('a1', 'A.mp3'), mp3('b1', 'B.mp3')]);
    await expect(groupAudiobooks('token', ids, title)).rejects.toThrow(message);
    expect(updateMock).not.toHaveBeenCalled();
  });

  it('refuses to merge a folder audiobook', async () => {
    fakeDrive([mp3('a1', 'A.mp3'), { id: 'dir', name: 'Series', mimeType: folderMime }]);
    await expect(groupAudiobooks('token', ['a1', 'dir'], 'Set')).rejects.toThrow('Only loose audio files');
    expect(updateMock).not.toHaveBeenCalled();
  });

  it('refuses when the selection resolves to fewer than two files', async () => {
    fakeDrive([mp3('a1', 'Same Book 1.mp3')]);
    await expect(groupAudiobooks('token', ['a1', 'a1'], 'Set')).rejects.toThrow('at least two audio files');
    expect(updateMock).not.toHaveBeenCalled();
  });
});

describe('ungroupAudiobook', () => {
  it('group then ungroup leaves no grouping behind', async () => {
    const drive = fakeDrive([mp3('a1', 'Book A 1.mp3'), mp3('a2', 'Book A 2.mp3'), mp3('b1', 'Book B 1.mp3')]);
    await groupAudiobooks('token', ['a1', 'b1'], 'Set');
    await ungroupAudiobook('token', 'a1');
    for (const id of ['a1', 'a2', 'b1']) expect(drive.get(id)!.appProperties).toEqual({});
  });

  it('only clears audio files in the same group', async () => {
    const drive = fakeDrive([
      mp3('g1', 'x.mp3', { appProperties: { m_audio_group: 'g', m_audio_group_title: 'G' } }),
      mp3('g2', 'y.mp3', { appProperties: { m_audio_group: 'g', m_audio_group_title: 'G' } }),
      mp3('h1', 'z.mp3', { appProperties: { m_audio_group: 'h', m_audio_group_title: 'H' } }),
      { id: 'pdf', name: 'notes.pdf', mimeType: 'application/pdf', parents: ['shelf'], appProperties: { m_audio_group: 'g' } },
    ]);
    await ungroupAudiobook('token', 'g1');
    expect(drive.get('g2')!.appProperties).toEqual({});
    expect(drive.get('h1')!.appProperties).toEqual({ m_audio_group: 'h', m_audio_group_title: 'H' });
    expect(drive.get('pdf')!.appProperties).toEqual({ m_audio_group: 'g' });
  });

  it.each([
    ['a folder', { id: 'x', name: 'Dir', mimeType: folderMime, parents: ['shelf'] }, 'cannot be unmerged'],
    ['an ungrouped file', mp3('x', 'Solo.mp3'), 'not a manual group'],
    ['a grouped file with no parent', mp3('x', 'Solo.mp3', { parents: undefined, appProperties: { m_audio_group: 'g' } }), 'not a manual group'],
  ])('rejects %s', async (_label, file, message) => {
    fakeDrive([file as DriveFile]);
    await expect(ungroupAudiobook('token', 'x')).rejects.toThrow(message);
    expect(updateMock).not.toHaveBeenCalled();
  });
});
