import {
  applyAutoGroupSuggestions,
  buildAutoGroupSuggestions,
  extractBaseTitle,
  type ManualAudiobookGroup,
} from '@/lib/audiobookGroups';
import type { AudiobookEntry } from '@/types/books';

function audio(id: string, title: string, isFolder = false): AudiobookEntry {
  return {
    id,
    title,
    source: 'Audiobooks',
    isFolder,
  } as AudiobookEntry;
}

describe('audiobook grouping', () => {
  it('extracts stable base titles from numbered chapter names', () => {
    expect(extractBaseTitle('Lilith Chapter 1: The Library')).toBe('Lilith');
    expect(extractBaseTitle('Book Name Part 2')).toBe('Book Name');
    expect(extractBaseTitle('Simple Title')).toBe('Simple Title');
  });

  it('builds suggestions only for eligible repeated titles', () => {
    let id = 0;
    const suggestions = buildAutoGroupSuggestions(
      [
        audio('a1', 'Lilith Chapter 1: The Library'),
        audio('a2', 'Lilith Chapter 2: The Mirror'),
        audio('single', 'Standalone'),
        audio('folder', 'Lilith Chapter 3', true),
        audio('merged-member', 'Lilith Chapter 4'),
      ],
      [{ id: 'existing', title: 'Existing', memberIds: ['merged-member'] }],
      () => `group-${++id}`,
    );

    expect(suggestions).toHaveLength(1);
    expect(suggestions[0]).toMatchObject({
      id: 'group-1',
      name: 'Lilith',
      memberIds: ['a1', 'a2'],
      included: true,
    });
  });

  it('applies included suggestions and removes moved members from old groups', () => {
    const existing: ManualAudiobookGroup[] = [
      { id: 'old', title: 'Old group', memberIds: ['a1', 'keep'] },
    ];

    const next = applyAutoGroupSuggestions(existing, [
      {
        id: 'new',
        name: ' New title ',
        memberIds: ['a1', 'a2'],
        members: [audio('a1', 'A 1'), audio('a2', 'A 2')],
        included: true,
      },
      {
        id: 'ignored',
        name: 'Ignored',
        memberIds: ['keep'],
        members: [audio('keep', 'Keep')],
        included: false,
      },
    ]);

    expect(next).toEqual([
      { id: 'old', title: 'Old group', memberIds: ['keep'] },
      { id: 'new', title: 'New title', memberIds: ['a1', 'a2'] },
    ]);
  });

  it('ignores blank group names', () => {
    const next = applyAutoGroupSuggestions([], [
      {
        id: 'blank',
        name: '   ',
        memberIds: ['a1'],
        members: [audio('a1', 'A')],
        included: true,
      },
    ]);
    expect(next).toEqual([]);
  });
});
