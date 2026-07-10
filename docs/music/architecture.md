# Music Atlas architecture

## Module boundary

Music Atlas is an isolated top-level domain inside the existing BookShelf application. Its UI lives under `app/music` and `components/music`, its HTTP surface under `app/api/music`, its domain and server code under `lib/music`, and its types in `types/music.ts`.

The existing ebook and audiobook systems remain production-frozen. Music must not reuse their domain types, Drive scanner, caches, metadata modules, collection hooks, progress APIs, or player components. A small amount of duplicated infrastructure is intentional where sharing would couple Music to those systems.

Music may use only these existing facilities:

- `lib/auth.ts` and the authenticated NextAuth session
- standard React and Next.js infrastructure
- the root visual tokens and styles
- existing environment variables, Google OAuth access token, and Redis/KV configuration
- the domain-neutral Redis transport, wrapped by Music-owned keys, validation, and migrations

## Enforced dependency rule

Files under these Music scopes:

- `app/music/`
- `app/api/music/`
- `components/music/`
- `lib/music/`
- `types/music.ts`

must not import, dynamically import, require, or re-export any of:

- `types/books.ts`
- `lib/googleDrive.ts`
- `lib/libraryCache.ts`
- `lib/bookMetadata.ts`
- `lib/useCollections.ts`
- `components/AudioPlayer.tsx`
- `components/DockedAudioPlayer.tsx`
- `components/AudiobookCard.tsx`

The reverse boundary is also enforced: existing ebook and audiobook domain files must not import Music routes, components, logic, or types. Existing app surfaces may link to the URL `/music`; a URL link is navigation, not a domain dependency.

`__tests__/music/moduleBoundaries.test.ts` checks both directions. It resolves `@/` aliases and relative specifiers and covers static imports, dynamic imports, re-exports, and CommonJS `require` calls.

## Runtime boundaries

- Every `/api/music/*` route authenticates independently near its data access. `proxy.ts` is only an optimistic page guard.
- Drive access, tokens, persistence credentials, and stream authorization remain in server-only Music modules.
- The configured Music root is server configuration and cannot be replaced by a client-supplied folder ID.
- Streaming is authorized from a server-written Music index or a server-side root-membership check. Browser state never grants file access.
- Music state uses versioned `joshbooks:music:*` KV keys and exact `joshbooks-music-v1:*` localStorage keys.
- Music does not call `/api/userdata`, `/api/audio-progress`, or `/api/library/audio-progress`.
- A nested Music layout owns its player provider so queue and playback survive navigation inside Music without entering the BookShelf root layout.
- Music route and component error boundaries isolate failures from `/library`, `/reader`, `/audiobooks`, and `/listen`.

## Minimal integration surface

Only four existing files are expected to change:

1. `app/page.tsx` for one authenticated Music Atlas link.
2. `proxy.ts` for the `/music/:path*` matcher.
3. `.env.local.example` for `MUSIC_DRIVE_FOLDER_ID`.
4. `README.md` for a short setup and route note.

Any additional change outside the Music-owned paths requires explicit justification in the completion report.

## Next.js 16 conventions

- Dynamic route parameters are promises and must be awaited.
- Pages and layouts remain Server Components unless interactivity requires a narrow client boundary.
- Route handlers are uncached by default and are treated as public endpoints.
- `app/music/error.tsx` is a Client Component and uses the Next.js 16 `unstable_retry` callback.
- Proxy is not a substitute for API authorization.
