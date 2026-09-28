const listMock = jest.fn();
const updateMock = jest.fn();

jest.mock('googleapis', () => ({
  google: {
    auth: { OAuth2: jest.fn(() => ({ setCredentials: jest.fn() })) },
    drive: jest.fn(() => ({
      files: {
        list: listMock,
        update: updateMock,
      },
    })),
  },
}));

import {
  listFilesInFolder,
  updateBookMetadata,
} from '@/lib/googleDrive';
import type { BookMetadata } from '@/types/books';

const pdfMime = 'application/pdf';
const epubMime = 'application/epub+zip';
const shortcutMime = 'application/vnd.google-apps.shortcut';

describe('Google Drive library helpers', () => {
  beforeEach(() => {
    listMock.mockReset();
    updateMock.mockReset();
    updateMock.mockResolvedValue({ data: {} });
  });

  it('lists supported books across pages, resolves shortcuts, and skips hidden/duplicates', async () => {
    listMock
      .mockResolvedValueOnce({
        data: {
          nextPageToken: 'page-2',
          files: [
            {
              id: 'pdf-1',
              name: 'One.pdf',
              mimeType: pdfMime,
              size: '123',
              modifiedTime: '2026-09-01T00:00:00Z',
              appProperties: {
                progressPercentage: '25',
                lastLocation: 'page-4',
                m_title: 'One',
              },
            },
            {
              id: 'shortcut-1',
              name: 'Two shortcut',
              mimeType: shortcutMime,
              shortcutDetails: {
                targetId: 'epub-2',
                targetMimeType: epubMime,
              },
            },
            {
              id: 'hidden',
              name: 'Hidden.pdf',
              mimeType: pdfMime,
              appProperties: { m_hidden: '1' },
            },
            {
              id: 'unsupported',
              name: 'Image.png',
              mimeType: 'image/png',
            },
          ],
        },
      })
      .mockResolvedValueOnce({
        data: {
          files: [
            {
              id: 'shortcut-duplicate',
              name: 'Two again',
              mimeType: shortcutMime,
              shortcutDetails: {
                targetId: 'epub-2',
                targetMimeType: epubMime,
              },
            },
          ],
        },
      });

    const books = await listFilesInFolder('token', 'folder', 'Unsorted');

    expect(books).toHaveLength(2);
    expect(books[0]).toMatchObject({
      id: 'pdf-1',
      name: 'One.pdf',
      format: 'pdf',
      readingProgress: 25,
      lastLocation: 'page-4',
      title: 'One',
      source: 'Unsorted',
    });
    expect(books[1]).toMatchObject({
      id: 'epub-2',
      name: 'Two shortcut',
      format: 'epub',
      source: 'Unsorted',
    });
    expect(listMock).toHaveBeenCalledTimes(2);
    expect(listMock.mock.calls[1][0].pageToken).toBe('page-2');
  });

  it('persists normalized metadata while keeping Drive app properties within limits', async () => {
    const metadata: BookMetadata = {
      title: 'Clean Code',
      authors: ['Robert C. Martin'],
      publishedDate: '2008',
      publisher: 'Prentice Hall',
      description: 'x'.repeat(500),
      categories: ['Software', 'Engineering'],
      series: 'Craft',
      seriesIndex: 2,
      pageCount: 464,
      language: 'en',
      isbn: '9780132350884',
      coverUrl: 'https://books.google.com/books/content?id=abc123&printsec=frontcover',
      metadataSource: 'manual',
    };

    await updateBookMetadata('token', 'file-1', metadata);

    const call = updateMock.mock.calls[0][0];
    expect(call.fileId).toBe('file-1');
    expect(call.requestBody.appProperties).toMatchObject({
      m_title: 'Clean Code',
      m_authors: 'Robert C. Martin',
      m_published: '2008',
      m_categories: 'Software; Engineering',
      m_seriesIdx: '2',
      m_pages: '464',
      m_gbid: 'abc123',
      m_cover: null,
      m_src: 'manual',
    });
    expect(Buffer.byteLength(`m_desc${call.requestBody.appProperties.m_desc}`, 'utf8')).toBeLessThanOrEqual(124);
  });

  it('stores Open Library cover ids and clears omitted metadata fields', async () => {
    await updateBookMetadata('token', 'file-2', {
      coverUrl: 'https://covers.openlibrary.org/b/id/12345-L.jpg',
    } as BookMetadata);

    expect(updateMock.mock.calls[0][0].requestBody.appProperties).toMatchObject({
      m_title: null,
      m_authors: null,
      m_olcid: '12345',
      m_cover: null,
      m_src: 'manual',
    });
  });

  it('stores a short manual cover URL only when no compact provider id is available', async () => {
    await updateBookMetadata('token', 'file-3', {
      coverUrl: 'https://example.com/cover.jpg',
      metadataSource: 'manual',
    } as BookMetadata);

    expect(updateMock.mock.calls[0][0].requestBody.appProperties.m_cover).toBe(
      'https://example.com/cover.jpg',
    );
  });
});
