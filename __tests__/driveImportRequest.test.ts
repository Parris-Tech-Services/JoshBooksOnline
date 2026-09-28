import { parseDriveImportRequest } from '@/lib/driveImportRequest';

const item = {
  id: 'file-1',
  name: 'Book.epub',
  mimeType: 'application/epub+zip',
  type: 'file' as const,
};

describe('Drive import request validation', () => {
  it('accepts a valid request and defaults target to auto', () => {
    expect(parseDriveImportRequest({ items: [item] })).toEqual({
      ok: true,
      items: [item],
      target: 'auto',
    });
  });

  it('accepts explicit ebook and audiobook targets', () => {
    expect(parseDriveImportRequest({ items: [item], target: 'ebooks' })).toMatchObject({
      ok: true,
      target: 'ebooks',
    });
    expect(parseDriveImportRequest({ items: [item], target: 'audiobooks' })).toMatchObject({
      ok: true,
      target: 'audiobooks',
    });
  });

  it('rejects malformed items and targets', () => {
    expect(parseDriveImportRequest({ items: [{ ...item, id: '' }] })).toMatchObject({
      ok: false,
    });
    expect(parseDriveImportRequest({ items: [item], target: 'everything' })).toMatchObject({
      ok: false,
    });
    expect(parseDriveImportRequest({ items: [] })).toMatchObject({
      ok: false,
    });
  });

  it('bounds per-request work', () => {
    expect(
      parseDriveImportRequest({
        items: Array.from({ length: 201 }, (_, index) => ({
          ...item,
          id: `file-${index}`,
        })),
      }),
    ).toMatchObject({
      ok: false,
      error: expect.stringContaining('at most 200 items'),
    });
  });
});
