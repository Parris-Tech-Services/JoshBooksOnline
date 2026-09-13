# Podcast Integration TODO

**Decision:** Add — high-value fit.  
**Status:** ✅ Core one-click podcast bank added 13 September 2026.
**Topic bank:** books, authors, literature, publishing, literary analysis, reading, book history.

## TODO
- [x] Curate 25 unique Spotify episodes on books/authors/literature in the shared JoshHub `books` bank.
- [x] Add a collapsed bottom dock: **📚 Listen to a different books podcast**.
- [x] One tap selects/loads another episode; persist recent selections and avoid immediate repeats.
- [x] Use Spotify embed/deep links without assuming autoplay.
- [x] Collapse the podcast dock whenever an audiobook, movie or other HTML audio/video is playing.
- [ ] Optionally tag/recommend episodes by author, genre or current book metadata — future enhancement.
- [x] Keep episode data separate from reader/player code and easy to refresh through JoshHub.
- [x] Shared dock provides mobile/a11y, reduced-motion, persistence and audio-conflict behaviour; repo-specific automated tests can be added later.

## Implementation
The root Next.js layout loads `podcast-dock-universal.js` with `data-bank="books"`. JoshBooks remains fully usable if Spotify is unavailable.
