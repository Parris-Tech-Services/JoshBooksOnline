# Podcast Integration TODO

**Decision:** Add — high-value fit.
**Status:** Existing independent podcast player implementation retained. Reviewed 21 November 2026; no new live Spotify playback test was performed during this documentation update.
**Topic bank:** books, authors, literature, publishing, literary analysis, reading, book history.

## TODO

The checked entries record the existing implementation status. The only remaining feature on this list is metadata-based episode recommendations. Device-sync and offline podcast work are tracked in [RUIZU Sync TODO](android/ruizu-sync/TODO.md), especially items 3, 8, 21 and 26; this web player does not establish offline USB playback.

- [x] Curate 25 unique Spotify episodes on books/authors/literature.
- [x] Store the 25-episode catalogue directly inside JoshBooksOnline so playback does not depend on JoshHub or jsDelivr.
- [x] Add a collapsed bottom **🎧 Podcasts** launcher.
- [x] One tap opens an episode; **📚 Different podcast** avoids the current/recent selections and remembers state in `localStorage`.
- [x] Use Spotify embed/deep links without assuming autoplay.
- [x] Close the podcast panel whenever an audiobook, movie or other HTML audio/video starts playing.
- [ ] **NOT COMPLETE: optional metadata recommendations.** Tag/recommend episodes by author, genre or current book metadata. Done when recommendations use available metadata, handle missing metadata, avoid current/recent repeats and have behavioural tests. Keep the existing player usable without this enhancement.
- [x] Keep the podcast implementation isolated from the main React/Next reader by using a local Web Component with Shadow DOM.
- [x] Keep JoshBooks fully usable if Spotify is unavailable.

## Implementation
`app/layout.tsx` loads only the local `/book-podcast-player.js` file. That file defines and mounts an isolated `<book-podcast-player>` Web Component, contains the 25-episode catalogue, persists local selection history, and renders Spotify embeds directly. It has no JoshHub, jsDelivr or shared podcast-runtime dependency.

## Verification record

- The 21 November repository check passed 24 Jest suites / 154 tests, type checking, lint (zero errors; existing warnings), catalogue validation and production build on retry. These are repository-wide checks, not proof of live Spotify availability or playback.
- Existing podcast tests validate catalogue count, unique IDs, required metadata and URL syntax; they do not exercise the separate 25-episode Web Component catalogue. Live embed/deep-link behaviour and service availability were not retested here.
- No new feature was implemented by this status update.
