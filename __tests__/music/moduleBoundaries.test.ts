import fs from 'node:fs';
import path from 'node:path';

const root = process.cwd();

const musicRoots = [
  'app/music',
  'app/api/music',
  'components/music',
  'lib/music',
  'types/music.ts',
];

const protectedBookModules = [
  'types/books.ts',
  'lib/googleDrive.ts',
  'lib/libraryCache.ts',
  'lib/bookMetadata.ts',
  'lib/useCollections.ts',
  'components/AudioPlayer.tsx',
  'components/DockedAudioPlayer.tsx',
  'components/AudiobookCard.tsx',
];

const existingDomainRoots = [
  'app/library',
  'app/audiobooks',
  'app/listen',
  'app/reader',
  'app/media',
  'app/watch',
  'app/api/library',
  'app/api/audio-progress',
  'app/api/userdata',
  ...protectedBookModules,
];

function sourceFiles(entries: string[]): string[] {
  const files: string[] = [];

  function visit(entry: string) {
    if (!fs.existsSync(entry)) return;
    const stat = fs.statSync(entry);
    if (stat.isDirectory()) {
      for (const child of fs.readdirSync(entry)) visit(path.join(entry, child));
      return;
    }
    if (/\.(?:ts|tsx|js|jsx|mjs|cjs)$/.test(entry)) files.push(path.resolve(entry));
  }

  for (const entry of entries) visit(path.resolve(root, entry));
  return files;
}

function importSpecifiers(file: string): string[] {
  const source = fs.readFileSync(file, 'utf8');
  const expressions = [
    /\b(?:import|export)\s+(?:[\s\S]*?\s+from\s+)?['"]([^'"]+)['"]/g,
    /\brequire\(\s*['"]([^'"]+)['"]\s*\)/g,
    /\bimport\(\s*['"]([^'"]+)['"]\s*\)/g,
  ];

  return expressions.flatMap((expression) =>
    Array.from(source.matchAll(expression), (match) => match[1])
  );
}

function resolveSpecifier(file: string, specifier: string): string | null {
  if (specifier.startsWith('@/')) return path.resolve(root, specifier.slice(2));
  if (specifier.startsWith('.')) return path.resolve(path.dirname(file), specifier);
  return null;
}

function withoutSourceExtension(file: string): string {
  return file.replace(/\.(?:ts|tsx|js|jsx|mjs|cjs)$/, '').replace(/[\\/]index$/, '');
}

function targetsPath(target: string, candidate: string): boolean {
  const normalizedTarget = withoutSourceExtension(path.resolve(root, target));
  const normalizedCandidate = withoutSourceExtension(candidate);
  return (
    normalizedCandidate === normalizedTarget ||
    normalizedCandidate.startsWith(`${normalizedTarget}${path.sep}`)
  );
}

function violations(sources: string[], forbiddenTargets: string[]): string[] {
  const found: string[] = [];
  for (const file of sourceFiles(sources)) {
    for (const specifier of importSpecifiers(file)) {
      const resolved = resolveSpecifier(file, specifier);
      if (!resolved) continue;
      if (forbiddenTargets.some((target) => targetsPath(target, resolved))) {
        found.push(`${path.relative(root, file)} -> ${specifier}`);
      }
    }
  }
  return found;
}

describe('Music Atlas module boundaries', () => {
  it('does not import protected BookShelf domain modules from Music', () => {
    expect(violations(musicRoots, protectedBookModules)).toEqual([]);
  });

  it('does not import Music domain modules from ebook or audiobook code', () => {
    expect(violations(existingDomainRoots, musicRoots)).toEqual([]);
  });
});
