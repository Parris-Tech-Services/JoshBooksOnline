# RUIZU Sync (Android) — 1.2.7

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
2. Tap **Use connected USB drive** (or **Choose player folder**). The app writes a small
   `.ruizu-sync-device.json` marker into that folder the first time and remembers the folder
   against the marker, so reconnecting the **same** player restores it automatically. Two players
   can report the same USB volume id (both units here report `0000-006F`), so when a marker does
   not match the remembered one the app selects nothing and tells you which unit is connected
   instead of copying to the wrong player.
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

## New in 1.2.0

- **Named playlists** — save the current queue as a playlist, reload it later, and export it to the
  player. Export copies the tracks and writes a player-compatible `.m3u` (relative paths) into the
  player folder.
- **Podcast subscriptions with advance downloads** — save feeds, then *Download new* fetches each
  feed (streamed, early-stop), downloads up to five unseen episodes to the phone using HTTP Range
  resume, and queues them for the player. Episodes are ready while the RUIZU is disconnected.
- **OPML import/export** — import subscriptions from AntennaPod or any podcast app, and export an
  OPML file (via the system file picker) for backup or for another app.
- **Automatic MP3 conversion** — optional. Non-MP3 sources are decoded with `MediaCodec`
  (including a raw-PCM fast path for WAV) and re-encoded with the pure-Java LAME port (jump3r)
  before copying; the original file is never modified. Failures fall back to copying the original.
- **Saved transfer plan (folder pair)** — remember source folder → player folder plus all options,
  run it with one tap, and optionally get asked to run it when the player connects. The plan also
  records which player it was saved for, and refuses to run against a different unit.
- **Per-player identity** — each player gets a marker file in its chosen folder, and the current
  player (name plus a short id) is shown above the transfer controls so you can check the
  destination before pressing Start.
- **Durable history** — transfer runs are appended to `transfer-history.jsonl` and survive
  restarts; the last three runs and a copyable log are shown in the app.
- **Resumable downloads** — interrupted HTTPS episode downloads continue from the bytes already on
  disk (`Range` + `.part` file) instead of starting over.

## AMV movie handoff (1.2.7)

The physical RUIZU played the converted full film *Pay It Forward (2000)* in AMV at 160×128, 15 fps with 22.05 kHz mono ADPCM. This release adds **Add converted AMV movie** and an Android share target for `.amv`. AMV files are routed to `VIDEO/` under the chosen **player root** via the existing journalled, readback-verified SAF writer; they are not included in audio playlists. Unconverted MP4/MKV films are **not accepted** by the app.

For a film stored in Google Drive, the companion **RUIZU Movie** Termux:Widget shortcut opens Android's Google Drive/Files document picker using Termux:API, converts the selected film to the hardware-tested AMV profile with FFmpeg, verifies output and shares it to RUIZU Sync. Setup/launcher: [tools/ruizu-drive-compare/README.md](../../tools/ruizu-drive-compare/README.md#ruizu-movie--android-home-screen-shortcut-amv-transfer-hand-off). Android still requires choosing the movie and confirming the USB transfer. Full live Drive-to-player acceptance on the Galaxy A55 is **not yet verified**.

## TV episodes from Termux (`ruizu-episodes`)

**Sync episodes** in the app does one thing: it asks [Termux](https://termux.dev) to run one bounded
[`ruizu-episodes`](../../tools/ruizu-episode-pipeline/README.md) run, then shows what that run did.
The pipeline stays the only thing that writes episodes to `VIDEO/` and its `pipeline.db` stays the
only record of what each player has, so the app cannot disagree with it and there is no second
copy path or second history to keep in step. The app reads that run's log back from the player over
the same SAF tree it already uses, which is why no extra storage permission is needed.

Requirements, all of which the app reports honestly when they are missing:

- Termux installed, with `allow-external-apps=true` in `~/.termux/termux.properties`, and the
  pipeline installed by `tools/ruizu-episode-pipeline/install.sh`.
- A player folder chosen in the app **that already has an identity marker**. The pipeline never
  writes to an unidentified player, so the button refuses rather than guessing which unit is plugged
  in.

The run writes `.ruizu-sync-run.log` in the player root. Its last line carries the exit status, and
**Show last run** displays it; a log without that line is reported as still running, never as a
finished run.

Not verified on hardware: the Termux `RUN_COMMAND` hand-off and live playback of pipeline-copied
episodes on either RUIZU unit. The encode profile and the copy method are the ones already proven by
the earlier scripts.

## Supported formats

MP3, WAV, FLAC, WMA, APE, AAC, M4A/M4B, OGG/Opus, AIFF, and **already converted AMV** video. Unconverted MP4/MKV and other formats are reported as unsupported.
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
- **Conversion is software MP3 encoding** (LAME in Java): it is CPU-bound and slower than native
  codecs, and it only produces MP3. It runs on the phone, so it works while the player is
  disconnected. Lossy sources are re-encoded (slight quality loss); the original is kept on the
  phone and never modified on the player.
- **Automatic downloads** are capped at five new episodes per *Download new* press and require the
  app to be running (no background scheduler yet).
- **USB writes cannot be resumed** mid-file: the Storage Access Framework offers no reliable
  append/offset contract. Only HTTPS downloads resume; interrupted copies restart that one file.
- One saved plan is supported (one source folder → one player folder).
- A player whose folder cannot hold the marker (read-only or refusing provider) is reported as
  **unidentified**. The app then keeps the old volume-only behaviour and does not claim to know
  which unit is attached.
- The wrong-player guard has JVM and fake-provider instrumentation coverage, but it has **not**
  been exercised with both physical RUIZU units attached; that remains a manual check.

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
| JVM core | 199 checks: formatting, formats/MIME mapping, name sanitising, folder planning incl. Music/Podcasts, duplicate matching incl. folder-scoped, index bookkeeping, playlist generation, stats/retry counters, hashing |
| JVM pipeline | 35 checks driving planner → sanitise → dedupe → collision → counters against an in-memory player |
| JVM feed + encoder | 16 JUnit tests incl. streaming chunk boundaries and early stop on 400-episode feeds |
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

## Still to do (not in 1.2.0)

- Parallel copies for many small files (serial on purpose, to keep progress/verification correct).
- Background scheduler + new-episode notifications for subscriptions (today: manual *Download new*).
- Migration from `dataSync` foreground service to Android 14+ user-initiated data transfer jobs.
- Volume normalisation and cover-art embedding/resizing for converted copies.
- Multiple saved plans and per-feed download rules.
- Full string localisation (UI strings are still inline in Java).
- Instrumented SAF test with a fake `DocumentsProvider` (biggest remaining coverage gap).
- Release signing via CI secrets and R8 minification for distribution.

## Package

- Application ID / namespace: `au.com.parristech.ruizusync`
- minSdk 26 (Android 8.0), targetSdk 35 (edge-to-edge insets handled)
- No AndroidX dependencies; framework widgets only (~110 KB APK)

## 1.2.6: subscription import and sharing

- OPML import now previews new feeds before saving. Untick feeds you do not want, then choose **Import selected**. **Cancel** leaves saved subscriptions unchanged. Existing subscriptions are not replaced.
- XML entities and Unicode titles/URLs round-trip correctly. Imports are limited to 8 MiB of input and 1,000 unique feeds; malformed XML, external declarations, invalid feed URL schemes and embedded passwords are rejected before saving.
- Both single-file and multiple-file shares have emulator regression coverage across separate Android UIDs.
- Files with a recorded problem are excluded from ready counts. Start remains disabled when no selected file can be copied, including after progress-state renders.

VersionCode 10 retains the existing canonical package and release certificate. The signed APK, exact hashes and test evidence are recorded in [the current backlog evidence](TODO.md#latest-implementation-evidence-126). Physical phone upgrade, Drive-to-USB transfer, eject and player playback remain separate acceptance checks.
