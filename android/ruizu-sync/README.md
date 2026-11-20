# RUIZU Sync 1.1 (Android)

Native Java companion for Samsung/Android using Android's Storage Access Framework (SAF). Canonical source: [JoshBooksOnline](https://github.com/Parris-Tech-Services/JoshBooksOnline). No root, broad storage permission, formatting or library database edits.

## Install

1. Open the passing **RUIZU Sync Android APK** [Actions run](https://github.com/Parris-Tech-Services/JoshBooksOnline/actions/workflows/ruizu-sync-apk.yml).
2. Download **ruizu-sync-debug-apk** from its Artifacts section (GitHub sign-in required), unzip and open `app-debug.apk` on the Samsung. Allow installation from that download app when Android prompts.
3. Connect the player using a USB data cable/OTG adapter. Confirm it appears in Samsung My Files.
4. Tap **Choose player folder**. Select the existing player folder (or an existing Music/Podcasts subfolder if Android disallows the volume root). Folder access persists through SAF.
5. Add audio files or a source folder from the phone/Google Drive. Choose **Music / Podcasts folders** organisation when using a common parent, or **Flat** when already inside the intended folder. Tap **Start transfer**. Existing folders are reused.
6. Wait for the transfer result, then use Android's USB eject action. The notification can remain after completion to show the result.

MP3 is recommended. WAV/FLAC/AAC/M4A/M4B/OGG/WMA may be copied but playback depends on the exact player model and codec. No conversion is performed. Protected Spotify/YouTube downloads cannot be exported; official app/site buttons and authorised RSS/direct audio are the alternatives.

## JoshBooks handoff

Open an authenticated Drive audiobook's **Details** page in JoshBooks. **Export to RUIZU** lets you select one or multiple tracks, **Prepare selected audio**, then **Send to RUIZU Sync** in the Android file share sheet. Preparation and sharing are separate taps because browsers require fresh user activation to open a share sheet. Filenames carry the book title and zero-padded chapter numbers in the catalogue's existing order.

This reuses `/api/stream/[fileId]` and the existing browser Google sign-in. Private audio is downloaded through the authenticated same-origin stream, then handed over as files; no tokens, signed download links or public Drive endpoints are introduced. Share support depends on the browser. Selections are bounded to 200 tracks/200 MiB of actual response bytes to control browser memory. Larger books: select their originals through the app's Google Drive SAF picker. Android source-folder scans and transfer batches are limited to 5000 files. Drive must be installed/signed in and expose the chosen audio through Android's picker.

## Transfer guarantees and limits

- DeepSeek transfer manager with foreground `dataSync` service keeps transfers independent of screen rotation, with a bounded wake lock and cancellation in app/notification. Cancelling waits for outstanding I/O (network timeout up to 30 seconds) before cleaning up.
- Streams use 64-bit counters up to FAT32's 4 GiB minus one byte single-file limit, without loading entire files into memory.
- Write only a random app-owned `.ruizu-*.part` document; close output, verify source length where known, read back SHA-256, then rename to publish.
- Existing files are never opened for write. Identical content with the requested or numbered conflict name is skipped; different content gets a numbered name. A same-name/same-size file is not assumed identical.
- Filename sanitisation removes path/control characters, handles FAT reserved names, limits UTF-8 bytes and preserves audio extensions.
- Disconnect, full storage, permission loss, failed close, read-back corruption or unsupported rename fails the operation. Failed temporary-file cleanup remains journalled and is retried before the next transfer to that folder. Recovery checks both the document URI and its exact original temporary name, so a successfully renamed file is preserved after a process interruption.
- Retry is **restart from source**, not byte-range resume: reconnect, choose the folder again if needed, then reselect/re-share the files. Already verified identical outputs are skipped. The service is not restarted after process death because transient sources cannot be assumed available.
- Android/USB providers do not universally expose reliable available capacity. Actual write/close failures are authoritative. Integrity read-back doubles USB I/O and cannot prove long-term flash durability.
- Direct downloads require HTTPS, bounded redirects, timeouts and audio/octet-stream content; podcast feeds are bounded to 5 MiB and 100 episodes. The app does not bypass DRM or extract streaming audio.

## Build and test

JDK **17**, Android SDK platform **35**, build tools **35.0.0**, Gradle **8.9**, Android Gradle Plugin **8.7.3**. The checked-in Gradle wrapper includes the distribution SHA-256. Standard Google/Maven repositories supply dependencies; no Termux D8 executable is used.

```sh
android/ruizu-sync/gradlew --no-daemon -p android/ruizu-sync \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
# With an Android emulator/device visible to adb:
android/ruizu-sync/gradlew -p android/ruizu-sync :app:connectedDebugAndroidTest
```

CI uploads APK SHA-256, signing-certificate information, unit/lint reports, emulator reports and a UI screenshot. Emulator tests use Android 15/API 35, 1080×2340 at density 450 (A55-like viewport, not Samsung firmware). Test-only fake DocumentsProvider is packaged solely in the test APK; it is not shipped in the user APK. No physical RUIZU compatibility is implied by these tests.

## Physical acceptance still required

- Galaxy A55's actual Android/One UI version: install, launch, readable layout, folder picker and persisted access after app/phone restart.
- Real RUIZU USB enumeration, write/rename/read-back support, safe eject, reconnect and playback.
- Multi-track playback order in the player's own database; MP3 tags, non-ASCII names and model-specific codecs.
- Real unplug mid-copy, actual full storage and retry, preserving all existing tracks/database files.
- Background/screen-off transfer, notification cancellation, rotating/reopening UI during a long transfer.
- Google Drive cloud retrieval, Android shares from other apps, browser multi-file handoff and representative RSS/HTTPS audio.

Use a small authorised MP3 first. The application never formats or clears the player.

## Updating

Increment `versionCode` for each update. Build and install with the **same signing key** to update in place and retain folder grants. CI caches its development debug keystore when available; **cache eviction or separate PR cache scopes can change that key**, so this is a test distribution, not a guaranteed stable release-signing channel. Compare `signing-certificate.txt` between builds. If Android rejects an update because certificates differ, uninstall/reinstall and choose the destination folder again; audio already on the player remains untouched. A durable production signing identity must be configured separately before promising seamless long-term updates. Never commit a production signing key.

## DeepSeek integration boundary

Development is on `integration/ruizu-sync-release`, based on PR #8. During this session DeepSeek published `69949ba` and follow-up CI repairs through `6ceeb22`; those sources were merged and its UI, folder planner, source scanning, RSS/link import, foreground service, USB detection and pure-logic suites retained. `TransferManager` calls `SafTransfer`/`VerifiedCopy` for the final write/verify/publish operation. The earlier Activity and service prototypes were removed to keep one runtime path.

Overwrite was removed from the UI and rejected by the runtime to meet the requirement to preserve existing player files. Available modes are exact-content skip and keep both. Source metadata and reported size are not proof of identical audio. The phone's Termux workspace was never accessed.
