const listMock = jest.fn();

jest.mock('googleapis', () => ({
  google: {
    auth: { OAuth2: jest.fn(() => ({ setCredentials: jest.fn() })) },
    drive: jest.fn(() => ({ files: { list: listMock } })),
  },
}));

import { getAllLibraryFiles } from '@/lib/googleDrive';

const UNSORTED = '0B9UqG6BQI95fb0xsOElucWx3LUE';
const BOOK_CLUB = '1FxuWDsjoRK9DUxdCoPefxea0eqR6EblU';
const IEC_27001 = '1X70Y14d15t3nqw5AxZ9V3XGGBUmRvTdZ';
const NONFICTION = '1o2tU1SKcvcuToRxQ-yILZuxWrMvmvH-W';

const pdf = (id: string) => ({ id, name: `${id}.pdf`, mimeType: 'application/pdf', modifiedTime: 't' });

/** Folder id -> listing; a function throws to simulate an unreachable folder. */
function serve(folders: Record<string, object[] | (() => never)>, localBooksId: string | null) {
  listMock.mockImplementation(async ({ q }: { q: string }) => {
    if (q.startsWith("name = 'Local Books'")) {
      return { data: { files: localBooksId ? [{ id: localBooksId }] : [] } };
    }
    const folderId = /'([^']+)' in parents/.exec(q)![1];
    const entry = folders[folderId] ?? [];
    if (typeof entry === 'function') entry();
    return { data: { files: entry } };
  });
}

beforeEach(() => {
  listMock.mockReset();
  jest.spyOn(console, 'error').mockImplementation(() => {});
});

afterEach(() => jest.restoreAllMocks());

describe('getAllLibraryFiles', () => {
  it('combines every source and lists a book found in several places once, from the first source', async () => {
    serve(
      {
        [UNSORTED]: [pdf('shared'), pdf('u1')],
        [BOOK_CLUB]: [pdf('shared'), pdf('club1')],
        [NONFICTION]: [pdf('shared'), pdf('n1')],
        local: [pdf('shared'), pdf('l1')],
      },
      'local',
    );

    const books = await getAllLibraryFiles('token');
    const ids = books.map((b) => b.id);
    expect(ids.sort()).toEqual(['club1', 'l1', 'n1', 'shared', 'u1']);
    expect(books.find((b) => b.id === 'shared')!.source).toBe('Book Club');
    expect(books.find((b) => b.id === 'n1')!.source).toBe('Nonfiction');
    expect(books.find((b) => b.id === 'l1')!.source).toBe('Local Books');
  });

  it('still returns the other sources when one folder fails, and logs the failure', async () => {
    serve(
      {
        [UNSORTED]: [pdf('u1')],
        [IEC_27001]: () => {
          throw new Error('403 forbidden');
        },
        [NONFICTION]: () => {
          throw new Error('timeout');
        },
      },
      null,
    );

    const books = await getAllLibraryFiles('token');
    expect(books.map((b) => b.id)).toEqual(['u1']);
    expect(console.error).toHaveBeenCalledWith('Failed to fetch from IEC 27001:', expect.any(Error));
    expect(console.error).toHaveBeenCalledWith('Failed to fetch from Nonfiction:', expect.any(Error));
  });

  it('carries on without Local Books when looking it up fails', async () => {
    serve({ [UNSORTED]: [pdf('u1')] }, null);
    const original = listMock.getMockImplementation()!;
    listMock.mockImplementation(async (args: { q: string }) => {
      if (args.q.startsWith("name = 'Local Books'")) throw new Error('offline');
      return original(args);
    });

    const books = await getAllLibraryFiles('token');
    expect(books.map((b) => b.id)).toEqual(['u1']);
    expect(console.error).toHaveBeenCalledWith('Failed to fetch from Local Books:', expect.any(Error));
  });
});
