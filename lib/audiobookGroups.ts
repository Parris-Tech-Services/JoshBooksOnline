import type { AudiobookEntry } from '@/types/books';

export type ManualAudiobookGroup = {
  id: string;
  title: string;
  memberIds: string[];
};

export type AutoGroupSuggestion = {
  id: string;
  name: string;
  memberIds: string[];
  members: AudiobookEntry[];
  included: boolean;
};

export function extractBaseTitle(title: string): string {
  const base = title
    .replace(/\s+(chapter|part|book|vol\.?|volume|episode|ep\.?)\s*\d[\s\S]*$/i, '')
    .replace(/\s+\d+[\s:–—\-][\s\S]*$/, '')
    .replace(/\s+\w*\d+\w*$/, '')
    .replace(/\s+[a-z]$/i, '')
    .trim();
  return base || title;
}

export function buildAutoGroupSuggestions(
  audiobooks: AudiobookEntry[],
  manualGroups: ManualAudiobookGroup[],
  createId: () => string,
): AutoGroupSuggestion[] {
  const alreadyMergedIds = new Set(manualGroups.flatMap((group) => group.memberIds));
  const grouped = new Map<string, AudiobookEntry[]>();

  for (const book of audiobooks) {
    if (book.isFolder || book.id.startsWith('group:') || alreadyMergedIds.has(book.id)) {
      continue;
    }

    const base = extractBaseTitle(book.title);
    if (base.length < 4) continue;

    const members = grouped.get(base) ?? [];
    members.push(book);
    grouped.set(base, members);
  }

  const suggestions: AutoGroupSuggestion[] = [];
  for (const [name, members] of grouped) {
    if (members.length < 2) continue;
    const sortedMembers = [...members].sort((a, b) =>
      a.title.localeCompare(b.title, undefined, { numeric: true }),
    );
    suggestions.push({
      id: createId(),
      name,
      memberIds: sortedMembers.map((member) => member.id),
      members: sortedMembers,
      included: true,
    });
  }

  return suggestions.sort((a, b) => b.members.length - a.members.length);
}

function removeMembersFromExistingGroups(
  groups: ManualAudiobookGroup[],
  memberIds: string[],
): ManualAudiobookGroup[] {
  const members = new Set(memberIds);
  return groups
    .map((group) => ({
      ...group,
      memberIds: group.memberIds.filter((id) => !members.has(id)),
    }))
    .filter((group) => group.memberIds.length > 0);
}

export function applyAutoGroupSuggestions(
  currentGroups: ManualAudiobookGroup[],
  suggestions: AutoGroupSuggestion[],
): ManualAudiobookGroup[] {
  let next = [...currentGroups];

  for (const suggestion of suggestions) {
    if (!suggestion.included) continue;
    const title = suggestion.name.trim();
    if (!title) continue;

    next = removeMembersFromExistingGroups(next, suggestion.memberIds);
    next.push({
      id: suggestion.id,
      title,
      memberIds: [...suggestion.memberIds],
    });
  }

  return next;
}
