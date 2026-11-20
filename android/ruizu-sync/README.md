# RUIZU Sync (Android)

Native, no-root companion for transferring music and podcasts from a Samsung phone (or Google
Drive) to a RUIZU 64 GB MP3 player over USB. It is an independent Android app inside
JoshBooksOnline; it does not change the Next.js site.

This app replaces the original single-screen scaffold on this branch. The transfer engine,
duplicate detection, folder planning, progress reporting and test suites are new; the podcast RSS
browsing, direct HTTPS audio links, share-intent handling and `TransferUtils` tests from the first
revision are preserved and now run on top of that engine.

## How it works

1. Connect the player with its USB cable and unlock the phone. Android mounts the player as
   removable storage (USB mass storage / OTG drive) and it appears to the system file picker.
2. In **RUIZU Sync**, tap **Use connected USB drive** (or **Choose player folder** for Drive/manual
   navigation) and select the player folder. The app keeps this permission across restarts.
3. Add sources:
   - **Add files** — multi-select MP3s from phone storage or Google Drive through the Android
     picker (SAF). Google Drive files come through its DocumentsProvider; some are labelled
     `application/octet-stream`, so the app checks the extension instead.
   - **Add folder** — pick a folder tree; every supported audio file underneath is queued,
     including nested albums.
   - **Browse podcast feed** — paste an HTTPS RSS/Atom feed, select episodes, and they are queued
     as `Podcasts`.
   - **Link → Music / Link → Podcasts** — queue a direct HTTPS audio file link.
   - **Share** an audio file (or a link) into the app from another app.
4. Choose **Folders on the player**: flat root, Artist / Album (from tags), by source folder,
   Music / Podcasts, or keep the source folder tree.
5. Choose duplicate handling: skip by name + size (fast), skip by SHA-256 content, overwrite, or
   keep both with an auto-renumbered name.
6. **Start transfer**. Progress shows overall percentage, current file, byte counts, speed, ETA
   and a log; a notification mirrors it with a Cancel action. Cancel is also available in-app.

The app never deletes unrelated player files, never formats storage, and removes a partially
written file when a copy fails or is cancelled.

## Supported formats

MP3, WAV, FLAC, WMA, APE, AAC, M4A/M4B, OGG/Opus, AIFF. Anything else is reported as skipped
instead of being copied where the player would ignore it. MP3 is the safest choice across RUIZU
models; check your specific player's manual for APE/OGG support.

## Limits and non-goals

- **No DRM bypass.** Files flagged `is_drm` by the local MediaStore are skipped, and this app never
  decrypts anything. Spotify and YouTube/YouTube Music streams and offline downloads are not
  transferable audio files and are rejected with an explanation.
- **MTP-only players cannot be mounted by Android.** Many cheap players (and some RUIZU units) only
  expose MTP or PTP, which the Storage Access Framework cannot mount as a file tree, so no
  no-root Android app can write to them. If the player shows up in My Files as "USB storage", the
  SAF path works; if it only appears as "MTP device", the transfer must be done from a computer.
- **USB volume root may be unselectable** on some Android builds; select the `Music` or `Podcasts`
  subfolder instead and use the flat layout, or pick both folders separately.
- Podcast feeds and direct links must be HTTPS and return audio; playlists (`.m3u`, `.m3u8`,
  `.pls`, `.xspf`, `.mpd`) are ignored.
- Remote (feed/link) files are streamed at transfer time and capped at 2 GB. SHA-256 duplicate
  detection on a remote file reads the source twice, once to hash and once to copy.
- Android may kill the process if the system is short of memory; the foreground service keeps the
  transfer alive in normal use, but it is not a download manager for overnight queues.

## Build

Two independent build paths are supported.

**CI (authoritative):** GitHub Actions runs `gradle :app:testDebugUnitTest` and
`:app:assembleDebug` with the official Android SDK 35, then runs `tools/verify.sh` and an emulator
smoke test (install, launch, screenshot, crash-buffer check). The APK is uploaded as the
`ruizu-sync-debug-apk` artifact.

**Termux / offline toolchain (used during development):**

```sh
./tools/fetch-sdk.sh   # API 34 stubs + official R8/D8 from Google Maven
./tools/build.sh       # aapt2 + javac + d8 + zipalign + apksigner
./tools/verify.sh      # signature, alignment, manifest, dex checks
./tools/test.sh        # JVM suites without Gradle
```

The Termux `d8` (R8 3.3.20) crashes on enum classes, so `tools/fetch-sdk.sh` downloads the current
official R8 from Google Maven and the build script uses it. This is why the project no longer
depends on the bundled compiler.

## Tests

| Level | What runs | Where |
| --- | --- | --- |
| JVM unit tests | 139 core-logic checks (formatting, formats, name sanitising, folder planning, duplicate matching, index bookkeeping, hashing, stats) | `app/src/test/.../core/CoreTests.java` |
| JVM pipeline tests | 35 checks driving the full transfer decision pipeline (planner → sanitise → dedupe → collision → counters) against an in-memory player | `app/src/test/.../core/PipelineTests.java` |
| JVM feed tests | RSS 2.0 / Atom enclosure parsing, HTTPS-only filtering, playlist/streaming rejection, CDATA/entities, episode cap | `app/src/test/.../net/FeedScannerTest.java` |
| JVM utility tests | Original `TransferUtils` behaviour (audio detection, safe names, numbering, streaming-link detection) | `app/src/test/.../TransferUtilsTest.java` |
| APK static verification | v2/v3 signature, 4-byte alignment, manifest (`exported`, FGS type, permissions), dex payload | `tools/verify.sh` |
| Emulator smoke test | APK installs, activity launches, expected UI text renders, no crash in the crash buffer, screenshot captured | CI job `emulator` |

Run everything locally with `gradle -p android/ruizu-sync :app:testDebugUnitTest` or
`./tools/test.sh`.

## What still requires a physical device

Emulator verification cannot exercise USB mass storage, the Google Drive DocumentsProvider, or a
real RUIZU player. Before treating USB transfers as verified, confirm on the phone + player:

1. The player appears in **Samsung My Files** as USB storage (not only as an MTP device) after
   connecting, and the RUIZU Sync USB banner detects it.
2. Selecting the player root works; if not, select `Music`/`Podcasts` subfolders and note which
   layout you used.
3. A full album folder copy completes with no failures, and the progress bar, notification and
   Cancel action behave.
4. The player indexes and plays the copied files, including nested Artist/Album folders and
   non-ASCII filenames.
5. Duplicate runs skip correctly; SHA-256 mode catches a renamed duplicate; overwrite replaces the
   intended file only.
6. Cancelling mid-copy leaves no partial file on the player, and safe eject works only after the
   transfer completes.
7. A file larger than 4 GB cannot exist on FAT32: confirm large WAV/FLAC behaviour and, if the
   player uses exFAT, verify that too.
8. DRM-protected local audio is skipped rather than copied (the log should say so).
9. Podcast RSS browsing and a direct HTTPS link transfer to `Podcasts`, and a file shared from
   another app lands in the right folder.
10. If the player only offers MTP, record that finding — the SAF workflow cannot work on it.

## Package

- Application ID / namespace: `au.com.parristech.ruizusync`
- minSdk 26 (Android 8.0), targetSdk 34 (Samsung Galaxy A55 ships Android 14/15)
- No AndroidX dependencies; framework widgets only, so the APK stays small (~110 KB)
