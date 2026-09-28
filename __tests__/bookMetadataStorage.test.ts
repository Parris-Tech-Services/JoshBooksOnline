const listMock = jest.fn();
const updateMock = jest.fn();

jest.mock('googleapis', () => ({
  google: {
    auth: { OAuth2: jest.fn(() => ({ setCredentials: jest.fn() })) },
    drive: jest.fn(() => ({ files: { list: listMock, update: updateMock } })),
  },
}));

import { listFilesInFolder, updateBookMetadata } from '@/lib/googleDrive';
import type { BookMetadata } from '@/types/books';

/** What updateBookMetadata sent to Drive for this call. */
async function savedProperties(metadata: BookMetadata): Promise<Record<string, string | null>> {
  updateMock.mockReset();
  updateMock.mockResolvedValue({});
  await updateBookMetadata('token', 'file-1', metadata);
  expect(updateMock).toHaveBeenCalledTimes(1);
  const [{ fileId, requestBody }] = updateMock.mock.calls[0];
  expect(fileId).toBe('file-1');
  return requestBody.appProperties;
}

/** Reads a book back the way the library does, from properties Drive would store. */
async function readBack(properties: Record<string, string | null>) {
  const stored = Object.fromEntries(
    Object.entries(properties).filter((entry): entry is [string, string] => entry[1] !== null),
  );
  listMock.mockResolvedValue({
    data: { files: [{ id: 'file-1', name: 'b.pdf', mimeType: 'application/pdf', modifiedTime: 't', appProperties: stored }] },
  });
  const [entry] = await listFilesInFolder('token', 'folder', 'Unsorted');
  return entry;
}

const full: BookMetadata = {
  title: 'The Fellowship of the Ring',
  authors: ['J. R. R. Tolkien', 'Christopher Tolkien'],
  publishedDate: '1954',
  publisher: 'Allen & Unwin',
  description: 'The first volume.',
  categories: ['Fantasy', 'Classics'],
  series: 'The Lord of the Rings',
  seriesIndex: 1,
  pageCount: 423,
  language: 'en',
  isbn: '9780261102354',
  googleBooksId: 'gb123',
  metadataSource: 'google-books',
} as BookMetadata;

describe('updateBookMetadata storage', () => {
  it('saved metadata reads back unchanged through the library listing', async () => {
    const entry = await readBack(await savedProperties(full));
    expect(entry).toMatchObject({
      title: full.title,
      authors: full.authors,
      publishedDate: full.publishedDate,
      publisher: full.publisher,
      description: full.description,
      categories: full.categories,
      series: full.series,
      seriesIndex: 1,
      pageCount: 423,
      language: 'en',
      isbn: full.isbn,
      metadataSource: 'google-books',
      coverUrl: expect.stringContaining('id=gb123'),
    });
  });

  it('never writes reading-progress keys, so progress survives a metadata edit', async () => {
    const props = await savedProperties(full);
    expect(Object.keys(props).every((key) => key.startsWith('m_'))).toBe(true);
    expect(props).not.toHaveProperty('progressPercentage');
    expect(props).not.toHaveProperty('lastLocation');
  });

  it('deletes cleared fields instead of leaving stale values', async () => {
    const props = await savedProperties({ title: 'Only a title', publisher: '' } as BookMetadata);
    expect(props.m_title).toBe('Only a title');
    for (const key of ['m_publisher', 'm_authors', 'm_desc', 'm_seriesIdx', 'm_gbid', 'm_olcid', 'm_cover']) {
      expect(props[key]).toBeNull();
    }
    expect(props.m_src).toBe('manual');
  });

  it('keeps a series index of 0 rather than treating it as empty', async () => {
    const props = await savedProperties({ seriesIndex: 0, pageCount: 0 } as BookMetadata);
    expect(props.m_seriesIdx).toBe('0');
    expect(props.m_pages).toBe('0');
  });

  describe('cover storage', () => {
    it('stores a Google Books cover URL as its compact id', async () => {
      const props = await savedProperties({
        coverUrl: 'https://books.google.com/books/content?id=XyZ&printsec=frontcover',
      } as BookMetadata);
      expect(props).toMatchObject({ m_gbid: 'XyZ', m_olcid: null, m_cover: null });
    });

    it('stores an Open Library cover URL as its compact id', async () => {
      const props = await savedProperties({ coverUrl: 'https://covers.openlibrary.org/b/id/98765-L.jpg' } as BookMetadata);
      expect(props).toMatchObject({ m_gbid: null, m_olcid: '98765', m_cover: null });
    });

    it('stores another short cover URL as-is, and drops one too long to store', async () => {
      expect((await savedProperties({ coverUrl: 'https://x.io/c.jpg' } as BookMetadata)).m_cover).toBe('https://x.io/c.jpg');
      const long = `https://example.com/${'a'.repeat(200)}.jpg`;
      expect((await savedProperties({ coverUrl: long } as BookMetadata)).m_cover).toBeNull();
    });

    it('prefers explicit cover ids over parsing the URL', async () => {
      const props = await savedProperties({
        openLibraryCoverId: '555',
        coverUrl: 'https://books.google.com/books/content?id=ignored',
      } as BookMetadata);
      expect(props).toMatchObject({ m_olcid: '555', m_gbid: null });
    });
  });

  it('trims long values to fit Drive’s 124-byte key+value limit without splitting characters', async () => {
    const props = await savedProperties({ description: 'é'.repeat(200) } as BookMetadata);
    const value = props.m_desc!;
    expect(Buffer.byteLength('m_desc' + value, 'utf8')).toBeLessThanOrEqual(124);
    expect(value).toBe('é'.repeat(value.length));
    expect(value.length).toBe(Math.floor((124 - 'm_desc'.length) / 2));
  });
});
