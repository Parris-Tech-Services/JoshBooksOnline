# Podcast Integration TODO

**Decision:** Add — high-value fit.
**Status:** Existing independent podcast player implementation retained. Reviewed 21 November 2026; no new live Spotify playback test was performed during this documentation update.
**Topic bank:** books, authors, literature, publishing, literary analysis, reading, book history.

## TODO

The checked entries record the existing implementation status. Topic-based recommendations are implemented and tested; live deployment verification remains before checking that entry complete. Device-sync and offline podcast work are tracked in [RUIZU Sync TODO](android/ruizu-sync/TODO.md), especially items 3, 8, 21 and 26; this web player does not establish offline USB playback.

- [x] Curate 25 unique Spotify episodes on books/authors/literature.
- [x] Store the 25-episode catalogue directly inside JoshBooksOnline so playback does not depend on JoshHub or jsDelivr.
- [x] Add a collapsed bottom **🎧 Podcasts** launcher.
- [x] One tap opens an episode; **📚 Different podcast** avoids the current/recent selections and remembers state in `localStorage`.
- [x] Use Spotify embed/deep links without assuming autoplay.
- [x] Close the podcast panel whenever an audiobook, movie or other HTML audio/video starts playing.
- [ ] **PARTIALLY COMPLETE: topic-based recommendations implemented.** The existing catalogue tags now populate a next-episode topic selector, including genre and author-interview categories. Suggestions prefer fresh matches, avoid current/recent episodes, and explain when no fresh topic match remains. Preference changes preserve the current media element, persist locally and tolerate blocked storage. Four Web Component tests pass. Live deployment verification is still pending; automatic current-book/author inference is not implemented or claimed.
- [x] Keep the podcast implementation isolated from the main React/Next reader by using a local Web Component with Shadow DOM.
- [x] Keep JoshBooks fully usable if Spotify is unavailable.

## Implementation
`app/layout.tsx` loads only the local `/book-podcast-player.js` file. That file defines and mounts an isolated `<book-podcast-player>` Web Component, contains the 25-episode catalogue, persists local selection history, and renders Spotify embeds directly. It has no JoshHub, jsDelivr or shared podcast-runtime dependency.

## Verification record

- The 21 November repository check passed 24 Jest suites / 154 tests, type checking, lint (zero errors; existing warnings), catalogue validation and production build on retry. These are repository-wide checks, not proof of live Spotify availability or playback.
- Existing podcast tests validate catalogue count, unique IDs, required metadata and URL syntax; they do not exercise the separate 25-episode Web Component catalogue. Live embed/deep-link behaviour and service availability were not retested here.
- No new feature was implemented by this status update.

### Topic recommendation acceptance (current work)

`__tests__/podcastPlayer.test.ts` exercises the actual Web Component in jsdom: topic matching, current/recent exclusion, honest fallback, preference persistence, invalid/blocked storage and unchanged current media element. Full suite: 25 suites / 158 tests. These tests do not prove publisher or Spotify playback availability.
