const getMock = jest.fn();
const listMock = jest.fn();

jest.mock('googleapis', () => ({
  google: {
    auth: { OAuth2: jest.fn(() => ({ setCredentials: jest.fn() })) },
    drive: jest.fn(() => ({ files: { get: getMock, list: listMock } })),
  },
}));

import { getAudiobookTracks } from '@/lib/googleDrive';

const folderMime = 'application/vnd.google-apps.folder';
const shortcutMime = 'application/vnd.google-apps.shortcut';
const audioMime = 'audio/mpeg';

describe('Drive audiobook track discovery', () => {
  beforeEach(() => {
    getMock.mockReset();
    listMock.mockReset();
  });

  it('groups a loose audio file with matching sibling chapters and sorts naturally', async () => {
    getMock.mockResolvedValue({
      data: {
        id: 'chapter-1',
        name: 'Example Book 1.mp3',
        size: '11',
        parents: ['parent'],
        mimeType: audioMime,
      },
    });

    listMock.mockResolvedValue({
      data: {
        files: [
          { id: 'chapter-10', name: 'Example Book 10.mp3', size: '10', mimeType: audioMime },
          { id: 'other', name: 'Different Book 1.mp3', size: '20', mimeType: audioMime },
          { id: 'chapter-2', name: 'Example Book 2.mp3', size: '12', mimeType: audioMime },
        ],
      },
    });

    await expect(getAudiobookTracks('token', 'chapter-1', false)).resolves.toEqual([
      { id: 'chapter-2', name: 'Example Book 2.mp3', size: 12 },
      { id: 'chapter-10', name: 'Example Book 10.mp3', size: 10 },
    ]);
  });

  it('follows folder shortcuts and recursively collects nested tracks', async () => {
    getMock.mockResolvedValue({
      data: {
        mimeType: shortcutMime,
        shortcutDetails: { targetId: 'real-folder', targetMimeType: folderMime },
      },
    });

    listMock.mockImplementation(({ q }: { q: string }) => {
      const parentId = q.match(/^'([^']+)' in parents/)?.[1];
      if (parentId === 'real-folder') {
        return Promise.resolve({
          data: {
            files: [
              { id: 'track-10', name: 'Track 10.mp3', size: '10', mimeType: audioMime },
              { id: 'disc-2', name: 'Disc 2', mimeType: folderMime },
            ],
          },
        });
      }
      if (parentId === 'disc-2') {
        return Promise.resolve({
          data: {
            files: [
              { id: 'track-2', name: 'Track 2.mp3', size: '2', mimeType: audioMime },
            ],
          },
        });
      }
      return Promise.resolve({ data: { files: [] } });
    });

    await expect(getAudiobookTracks('token', 'shortcut-folder', true)).resolves.toEqual([
      { id: 'track-2', name: 'Track 2.mp3', size: 2 },
      { id: 'track-10', name: 'Track 10.mp3', size: 10 },
    ]);
  });
});
