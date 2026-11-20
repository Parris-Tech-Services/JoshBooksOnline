# RUIZU Sync (Android) — 1.1.0

Native, no-root companion for transferring music and podcasts from a Samsung phone (or Google
Drive) to a RUIZU 64 GB MP3 player over USB. It is an independent Android app inside
JoshBooksOnline; it does not change the Next.js site.

The transfer engine is a merged implementation: SAF source/destination access, folder planning,
duplicate detection, progress reporting, retries, verification and the test suites. The podcast
RSS browsing, direct HTTPS audio links, share-intent handling and `TransferUtils` tests from the
first revision are preserved and run on top of that engine.

## What the app does

1. Connect the player with its USB cable and unlock the phone. Android mounts it as removable
   storage (USB mass storage / OTG drive) and it appears to the system file picker.
2. Tap **Use connected USB drive** (or **Choose player folder**). The folder is remembered per
   volume, so reconnecting the same player restores it automatically.
3. Add sources:
   - **Add files** — multi-select audio from phone storage or Google Drive (SAF; Google's
     DocumentsProvider supplies the files).
   - **Add folder** — recursive scan of a folder tree, including nested albums.
   - **Browse podcast feed** — stream an HTTPS RSS/Atom feed and pick episodes. Feeds are parsed
     incrementally and stop after 100 episodes, so multi-megabyte feeds (e.g. long-running shows)
     load quickly instead of failing a size cap.
   - **Link → Music / Link → Podcasts** — queue a direct HTTPS audio file link.
   - **Share** audio files or links into the app from another app.
4. Choose **Folders on the player**: flat root, Artist / Album (from tags), by source folder,
   Music / Podcasts, or keep the source folder tree.
5. Choose duplicate handling: skip by name + size, skip by SHA-256 content, overwrite, or keep
   both. Optionally only count files in the destination folder as duplicates.
6. Optional reliability features (1.1.0):
   - **Verify each copy** — the source is hashed while it is copied and the written file is read
     back and compared (SHA-256); mismatches are deleted and retried.
   - **Automatic retries** — transient failures are retried twice with backoff, and **Retry
     failed** reruns only the files that failed.
   - **Free-space checks** — the copy is refused with a clear message when the player lacks room.
   - **Resume after process death** — the queue is persisted every 10 files; on restart the app
     offers to restore the remaining files.
   - **M3U playlists** — one playlist per touched folder, relative paths, player-compatible.
   - **History** — last-run summary, a copyable log, and live retry counters.
7. **Start transfer**: overall and per-file progress, speed, ETA, live log, cancel, and a
   foreground-service notification with a Cancel action.

The app never deletes unrelated player files, never formats storage, and deletes a partially
written file when a copy fails or is cancelled.

## Supported formats

MP3, WAV, FLAC, WMA, APE, AAC, M4A/M4B, OGG/Opus, AIFF. Anything else is reported as skipped.
MP3 is the safest choice across RUIZU models; check the player manual for APE/OGG support.

## Limits and non-goals

- **No DRM bypass.** Files flagged `is_drm` by the local MediaStore are skipped; nothing is
  decrypted. Spotify and YouTube/YouTube Music streams are rejected with an explanation.
- **MTP-only players cannot be mounted by Android.** If the player only appears as an MTP/PTP
  device, the app now says so explicitly: no no-root app can write to MTP. Transfer from a
  computer, or switch the player to USB disk / mass-storage mode.
- **USB volume root may be unselectable** on some builds; pick the `Music` / `Podcasts`
  subfolder and the flat layout instead.
- Remote (feed/link) files stream at transfer time, capped at 2 GB; playlists (`.m3u`, `.m3u8`,
  `.pls`, `.xspf`, `.mpd`) are rejected.
- SHA-256 duplicate detection on a remote file reads the source twice when verification is off
  (once to hash, once to copy).
- Android may kill the app under memory pressure; the foreground service keeps a transfer alive
  in normal use and the queue is persisted for resume, but there is no overnight download manager.

## Build

Two independent paths exist.

**CI (authoritative):** GitHub Actions runs `gradle :app:testDebugUnitTest :app:assembleDebug`
with Android SDK 35, then `tools/verify.sh` (signature, alignment, manifest, dex), then an
emulator smoke test (`.github/scripts/emulator-smoke.sh`) on an API 34 KVM-accelerated AVD: it
installs the APK, launches `MainActivity`, verifies the rendered labels and crash buffer, and
uploads a screenshot as the `ruizu-sync-emulator-evidence` artifact.

**Termux / offline toolchain:**

```sh
./tools/fetch-sdk.sh   # API 34 stubs + official R8/D8 from Google Maven
./tools/build.sh       # aapt2 + javac + d8 + zipalign + apksigner
./tools/verify.sh      # static checks against the built APK
./tools/test.sh        # JVM suites without Gradle
```

The Termux `d8` (R8 3.3.20) crashes on enum classes; `tools/fetch-sdk.sh` downloads the current
official R8 from Google Maven and the build uses it, so the project does not depend on the
bundled compiler.

## Tests

| Level | What runs |
| --- | --- |
| JVM core | 164 checks: formatting, formats/MIME mapping, name sanitising, folder planning incl. Music/Podcasts, duplicate matching incl. folder-scoped, index bookkeeping, playlist generation, stats/retry counters, hashing |
| JVM pipeline | 35 checks driving planner → sanitise → dedupe → collision → counters against an in-memory player |
| JVM feed | 15 JUnit tests incl. streaming chunk boundaries and early stop on 400-episode feeds |
| JVM utils | Original `TransferUtils` behaviour |
| APK static | v2/v3 signature, 4-byte alignment, manifest (`exported`, FGS type, permissions), single- or multi-dex payload |
| Emulator (API 34, KVM) | **passing in CI**: installs, `MainActivity` gains window focus, expected labels render, screenshot captured, crash buffer clean |

Run locally: `gradle -p android/ruizu-sync :app:testDebugUnitTest` or `./tools/test.sh`.

## What still requires a physical device

Emulator verification cannot exercise USB mass storage, Google Drive's provider, or the real
player. Confirm on the phone + player:

1. The player appears in Samsung My Files as USB storage and the app's USB banner detects it (or
   that it is MTP-only, which the app now reports explicitly).
2. Selecting the player root works; otherwise select `Music`/`Podcasts`.
3. A full album copy completes; notification and Cancel behave; the player indexes and plays the
   files, including nested folders and non-ASCII names.
4. Duplicate runs skip correctly; SHA-256 mode catches a renamed duplicate; the destination-folder
   scope behaves as expected; overwrite touches only the intended file.
5. Verification mode reports no mismatches on a healthy cable and catches failures on a bad one.
6. Cancelling mid-copy leaves no partial file; safe eject works only after completion.
7. >4 GB behaviour on FAT32 vs exFAT; DRM-flagged files are skipped.
8. Podcast feed (including a large one), direct link and share-intent imports land in the right
   folder; M3U playlists appear and play.

## Still to do (not in 1.1.0)

- Parallel copies for many small files (currently serial, deliberately, to keep progress and
  verification bookkeeping correct).
- Podcast subscriptions with a background scheduler and new-episode notifications (manual feed
  browsing only today).
- Migration from `dataSync` foreground service to Android 14+ user-initiated data transfer jobs.
- Full string localisation (UI strings are still inline in Java).
- Instrumented SAF test with a fake `DocumentsProvider` (biggest remaining coverage gap).
- Release signing via CI secrets and R8 minification for distribution.

## Package

- Application ID / namespace: `au.com.parristech.ruizusync`
- minSdk 26 (Android 8.0), targetSdk 35 (edge-to-edge insets handled)
- No AndroidX dependencies; framework widgets only (~110 KB APK)
