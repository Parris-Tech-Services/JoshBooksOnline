# Podcast Integration TODO

**Decision:** Add — high-value fit.  
**Status:** ✅ Independent podcast player live in the app.
**Topic bank:** books, authors, literature, publishing, literary analysis, reading, book history.

## TODO
- [x] Curate 25 unique Spotify episodes on books/authors/literature.
- [x] Store the 25-episode catalogue directly inside JoshBooksOnline so playback does not depend on JoshHub or jsDelivr.
- [x] Add a collapsed bottom **🎧 Podcasts** launcher.
- [x] One tap opens an episode; **📚 Different podcast** avoids the current/recent selections and remembers state in `localStorage`.
- [x] Use Spotify embed/deep links without assuming autoplay.
- [x] Close the podcast panel whenever an audiobook, movie or other HTML audio/video starts playing.
- [ ] Optionally tag/recommend episodes by author, genre or current book metadata — future enhancement.
- [x] Keep the podcast implementation isolated from the main React/Next reader by using a local Web Component with Shadow DOM.
- [x] Keep JoshBooks fully usable if Spotify is unavailable.

## Implementation
`app/layout.tsx` loads only the local `/book-podcast-player.js` file. That file defines and mounts an isolated `<book-podcast-player>` Web Component, contains the 25-episode catalogue, persists local selection history, and renders Spotify embeds directly. It has no JoshHub, jsDelivr or shared podcast-runtime dependency.
