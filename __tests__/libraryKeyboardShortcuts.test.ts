import { resolveLibraryShortcut } from '@/lib/useLibraryKeyboardShortcuts';

function event(key: string, overrides = {}) {
  return {
    key,
    ctrlKey: false,
    metaKey: false,
    altKey: false,
    ...overrides,
  };
}

describe('library keyboard shortcuts', () => {
  it('focuses search with slash outside form controls', () => {
    expect(resolveLibraryShortcut(event('/'), 'DIV', false)).toEqual({
      type: 'focus-search',
    });
    expect(resolveLibraryShortcut(event('/'), 'INPUT', false)).toBeNull();
  });

  it('clears search only when search is focused', () => {
    expect(resolveLibraryShortcut(event('Escape'), 'INPUT', true)).toEqual({
      type: 'clear-search',
    });
    expect(resolveLibraryShortcut(event('Escape'), 'DIV', false)).toBeNull();
  });

  it('switches the three numbered library tabs without modifiers', () => {
    expect(resolveLibraryShortcut(event('1'), 'DIV', false)).toEqual({
      type: 'switch-tab',
      tab: 'ebooks',
    });
    expect(resolveLibraryShortcut(event('2'), 'DIV', false)).toEqual({
      type: 'switch-tab',
      tab: 'audiobooks',
    });
    expect(resolveLibraryShortcut(event('3'), 'DIV', false)).toEqual({
      type: 'switch-tab',
      tab: 'movies',
    });
    expect(
      resolveLibraryShortcut(event('1', { ctrlKey: true }), 'DIV', false),
    ).toBeNull();
  });
});
