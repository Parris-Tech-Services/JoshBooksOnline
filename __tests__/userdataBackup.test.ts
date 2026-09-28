import {
  createUserdataBackup,
  parseUserdataBackup,
  restoreUserdataBackup,
} from '@/lib/userdataBackup';

function fakeStorage(seed: Record<string, string> = {}) {
  const values = new Map(Object.entries(seed));
  return {
    get length() {
      return values.size;
    },
    key(index: number) {
      return [...values.keys()][index] ?? null;
    },
    getItem(key: string) {
      return values.get(key) ?? null;
    },
    setItem(key: string, value: string) {
      values.set(key, value);
    },
    values,
  };
}

describe('JoshBooks userdata backups', () => {
  it('exports approved storage keys and watch progress only', () => {
    const storage = fakeStorage({
      'joshbooks-view': '"grid"',
      'joshbooks-watch-progress:movie-1': '125',
      'unrelated-secret': 'do-not-export',
    });

    const backup = createUserdataBackup(storage, '2026-09-28T00:00:00.000Z');

    expect(backup).toMatchObject({
      _version: 1,
      _exported: '2026-09-28T00:00:00.000Z',
      'joshbooks-view': 'grid',
      _watch_progress: {
        'joshbooks-watch-progress:movie-1': 125,
      },
    });
    expect(backup).not.toHaveProperty('unrelated-secret');
  });

  it('restores only approved keys and valid watch progress keys', () => {
    const storage = fakeStorage();
    restoreUserdataBackup(
      storage,
      JSON.stringify({
        _version: 1,
        'joshbooks-view': 'list',
        'unrelated-secret': 'must-not-write',
        _watch_progress: {
          'joshbooks-watch-progress:movie-1': 42,
        },
      }),
    );

    expect(storage.getItem('joshbooks-view')).toBe('list');
    expect(storage.getItem('joshbooks-watch-progress:movie-1')).toBe('42');
    expect(storage.getItem('unrelated-secret')).toBeNull();
  });

  it('rejects malformed or unsupported backups without writing anything', () => {
    expect(() => parseUserdataBackup('[]')).toThrow('Backup root must be an object');
    expect(() => parseUserdataBackup('{"_version":2}')).toThrow(
      'Unsupported backup version',
    );
    expect(() =>
      parseUserdataBackup(
        JSON.stringify({
          _version: 1,
          _watch_progress: { 'arbitrary-key': 99 },
        }),
      ),
    ).toThrow('Invalid watch progress data');
  });
});
