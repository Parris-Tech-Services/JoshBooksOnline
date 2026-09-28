import { useEffect, type RefObject } from 'react';

export type LibraryTab = 'ebooks' | 'audiobooks' | 'movies' | 'podcasts';

export type LibraryShortcut =
  | { type: 'focus-search' }
  | { type: 'clear-search' }
  | { type: 'switch-tab'; tab: Exclude<LibraryTab, 'podcasts'> }
  | null;

export function resolveLibraryShortcut(
  event: Pick<KeyboardEvent, 'key' | 'ctrlKey' | 'metaKey' | 'altKey'>,
  targetTag: string | undefined,
  searchFocused: boolean,
): LibraryShortcut {
  const inInput =
    targetTag === 'INPUT' ||
    targetTag === 'TEXTAREA' ||
    targetTag === 'SELECT';

  if (event.key === '/') {
    return inInput ? null : { type: 'focus-search' };
  }

  if (event.key === 'Escape' && searchFocused) {
    return { type: 'clear-search' };
  }

  if (inInput || event.ctrlKey || event.metaKey || event.altKey) return null;

  if (event.key === '1') return { type: 'switch-tab', tab: 'ebooks' };
  if (event.key === '2') return { type: 'switch-tab', tab: 'audiobooks' };
  if (event.key === '3') return { type: 'switch-tab', tab: 'movies' };
  return null;
}

export function useLibraryKeyboardShortcuts(
  searchInputRef: RefObject<HTMLInputElement | null>,
  setSearch: (value: string) => void,
  setTab: (tab: LibraryTab) => void,
): void {
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      const targetTag = (event.target as HTMLElement | null)?.tagName;
      const command = resolveLibraryShortcut(
        event,
        targetTag,
        document.activeElement === searchInputRef.current,
      );
      if (!command) return;

      if (command.type === 'focus-search') {
        event.preventDefault();
        searchInputRef.current?.focus();
        return;
      }

      if (command.type === 'clear-search') {
        setSearch('');
        searchInputRef.current?.blur();
        return;
      }

      setTab(command.tab);
    };

    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [searchInputRef, setSearch, setTab]);
}
