# RUIZU Sync feature to-do list

Status: **living implementation and validation backlog** (reviewed 21 November 2026). Items 1–11 retain their original numbers and full acceptance criteria. Items 12–28 record outstanding verification, usability and release work. An unchecked item may already be implemented in code; it means its full acceptance criteria have **not** been independently verified. Never infer completion from a passing build alone.

Purpose: make RUIZU Sync a trustworthy music, podcast and audiobook manager, using the existing SAF transfer manager and verified writer. **This file is a tracking document, not evidence that any task was implemented by a documentation change.**

## Current status at 21 November 2026

Items #8 and #12 meet their full listed acceptance criteria. Unchecked items retain their original requirements; implemented code is distinguished from observed behaviour. Updating this document does not complete those requirements.

| Item | Status | Remaining work / evidence |
| --- | --- | --- |
| 1 | **PARTIALLY COMPLETE** | Conversion code and reported on-phone WAV self-test exist; quality controls, estimates, cancellation/cleanup and representative player playback still need acceptance. |
| 2 | **PARTIALLY COMPLETE** | Playlist creation/export exists; track arrangement, collision/Unicode references and actual player ordering/M3U support remain to verify. |
| 3 | **PARTIALLY COMPLETE** | Subscription and download preparation code exists; scheduling, per-feed limits, Wi-Fi/charging preferences and restart/duplicate/USB acceptance remain. |
| 4 | **PARTIALLY COMPLETE** | Range/resume policy and recoverable transfer code exist; changed validators, process death, permission loss and provider-dependent USB resume remain to verify. |
| 5 | **PARTIALLY COMPLETE** | A saved plan exists; named multiple plans, editing/removal, restart/revoked-grant recovery and preview acceptance remain. |
| 6 | **PARTIALLY COMPLETE** | Connection-triggered offer exists; physical reconnect, wrong-drive protection and repeated-event/concurrency acceptance remain. Unattended operation is not verified. |
| 7 | **PARTIALLY COMPLETE** | History/retry code exists; searchable records, bounded retention and accurate interrupted/restarted run acceptance remain. |
| 8 | **VERIFIED COMPLETE** | PR #31 adds selectable import preview, cancellation without mutation, existing-subscription preservation, duplicate handling and bounded XML parsing. Eight JVM parser tests and two Android import-dialog tests passed; full run: 25 Android tests, zero failures/errors/skips. Evidence below. |
| 9 | **NOT COMPLETE** | Opt-in loudness processing and clipping/source-preservation tests, plus audible player verification, remain. |
| 10 | **NOT COMPLETE** | Verified device artwork profile, copy-only embedding/resizing and malformed-image/player-display acceptance remain. |
| 11 | **PARTIALLY COMPLETE** | Safe-removal guidance exists; actual Samsung eject and all active-I/O/lifecycle states remain unverified (see 23). |
| 12 | **VERIFIED COMPLETE** | Latest run: 25 emulator instrumentation tests passed, none skipped; evidence linked below. |
| 13 | **PARTIALLY COMPLETE** | Independent physical scan captured and every change accounted for (see evidence below): the 1,617 → 1,637 rise was the 8 delivered bundle files plus 12 copies of `/sdcard/Music` content, 11 byte-identical and 1 a real `.m4a`→`.mp3` conversion. Remaining: reconcile those totals against the app's **own** inventoried numbers in one session. |
| 14 | **PARTIALLY COMPLETE** | Read-only comparator and offline review shipped in PRs #22/#26; real source-to-player reconciliation and missing-only transfer remain unverified. |
| 15 | **NOT COMPLETE** | Reliable grouping, independent album tracklists and real incomplete-album comparison remain. |
| 16 | **NOT COMPLETE** | Real local/Drive picker, nested trees, offline/revoked permissions and restart-to-USB transfer acceptance remain. |
| 17 | **PARTIALLY COMPLETE** | PRs #27/#28 merged: real cross-app ACTION_SEND and ACTION_SEND_MULTIPLE grants, filenames/streams, sibling denial and missing-name MIME fallback passed. Real Files-app sharing and A55 reproduction/fix acceptance remain. Both intent actions are now covered by cross-UID emulator tests in PR #31. |
| 18 | **NOT COMPLETE** | Actual player indexing, album/podcast/converted audio playback, Unicode, nested folders and M3U ordering remain unverified. |
| 19 | **PARTIALLY COMPLETE** | Fake-provider instrumentation covers transfer faults/recovery; physical unplug/cable faults, process death, FAT32/large files and preservation acceptance remain. |
| 20 | **PARTIALLY COMPLETE** | Progress/history/retry implemented; restart/notification/ETA/interrupted-state acceptance remains. |
| 21 | **PARTIALLY COMPLETE** | Feed/download/resume code and tests exist; genuine oversized feed, remote-content changes and disconnected-preparation-to-player acceptance remain. |
| 22 | **PARTIALLY COMPLETE** | Canonical signed 1.2.6 identity verified; actual 1.2.6 phone upgrade, retained settings/grants and any legacy package migration remain unverified. |
| 23 | **PARTIALLY COMPLETE** | Guidance exists; system-confirmed eject and real A55/player lifecycle acceptance remain. |
| 24 | **PARTIALLY COMPLETE** | Saved plan and connection offer exist; physical identity/permission persistence, repeated-attach and wrong-target acceptance remain. |
| 25 | **NOT COMPLETE** | Same remaining normalisation/artwork work as 9 and 10; not a basic-transfer release blocker. |
| 26 | **NOT COMPLETE** | Optional scheduling, download preferences, multiple plans, richer browsing and lifecycle/job migration remain. |
| 27 | **PARTIALLY COMPLETE** | Signed 1.2.6 APK delivered; automated verification passed. Final physical acceptance and public release notes/download publication remain to confirm. |
| 28 | **NOT COMPLETE** | Not built: video is entirely outside the app, which is audio-only. The player's video format was characterised from its own vendor sample and a hand-built test clip plus one feature film were copied to `VIDEO/`; **playback on the player is unconfirmed**. |

## Latest implementation evidence: 1.2.6

[PR #31](https://github.com/Parris-Tech-Services/JoshBooksOnline/pull/31) merged as `c930287da27a47348b6ea7b7ec4763f30203ef73`. It completes the OPML import preview and parser acceptance in item 8, adds single-file sharing coverage to item 17, and makes both Start-button render paths follow the transfer engine's blocking rules. A known problem prevents a file being counted as ready even when its extension is MP3.

- [CI run 44867376422](https://github.com/Parris-Tech-Services/JoshBooksOnline/actions/runs/44867376422): JVM/APK/static signing checks, release launch and **25 Android instrumentation tests passed**, zero failures/errors/skips. [XML and logs](https://github.com/Parris-Tech-Services/JoshBooksOnline/actions/runs/44867376422/artifacts/14692761768).
- OPML tests cover Unicode/entities and URL round-trips, nested/duplicate feeds, malformed/non-OPML documents, DTD/entity rejection, size/feed-count limits, invalid schemes and comment handling. Android tests cover preview cancellation, selected-only saving, preserved existing titles and idempotent re-import. Parser errors do not echo private document contents.
- Standalone Java checks: **246 core checks and 55 JUnit tests passed**, including the repaired test-script coverage for integrity, resume and library comparison. Local compilation used ECJ targeting Java 17; CI used the configured JDK/Gradle build.
- Installer: **1.2.6 / versionCode 10**, package `au.com.parristech.ruizusync`; [signed CI artifact](https://github.com/Parris-Tech-Services/JoshBooksOnline/actions/runs/44867376422/artifacts/14692577404). APK SHA-256 `e11dcb208e2d4f1c06fd4e0994782d3efc4f697db4868a488646b8d9b823fe6a`; certificate SHA-256 `d16afeee87dd25be0421223d748ab175cbb37b4c5152042933342cc5880307b4` was independently extracted and matched the release key. A durable installable copy was delivered in this conversation.
- **Still unverified:** physical 1.2.6 upgrade/data/grants, actual Files/Drive-to-player transfer, eject/indexing/playback and physical fault recovery. No new physical device test was performed by this work session.

## Numbered backlog

1. [ ] **Automatic audio conversion.** Convert supported, unprotected source audio into a player-compatible MP3 copy during preparation or transfer. Let the user choose an appropriate quality/bitrate and show estimated output size. Preserve the original; do not bypass DRM or extract protected Spotify/YouTube downloads.
   - Comparison: **MediaMonkey** describes unsupported formats being **“converted on the fly”**. [Official portable-device sync documentation](https://www.mediamonkey.com/wiki/WebHelp:Exporting_Tracks).
   - Done when: conversion errors and cancellation are handled safely; automated tests cover output format and temporary-file cleanup; representative converted files play on the actual RUIZU.

2. [ ] **Playlist creation and export.** Create named music or audiobook playlists, let the user arrange their tracks, and export the ordered files with an M3U playlist using relative paths where appropriate. Preserve existing playlists and library database files.
   - Comparison: **doubleTwist Sync** writes playlists **“as .m3u files”**. [Official sync guide, Syncing section](https://www.doubletwist.com/help/question/108/).
   - Done when: playlist references remain correct after filename sanitisation and collision renaming; ordering and Unicode paths have automated coverage; playlist support is verified on the exact RUIZU model. Provide numbered-file ordering as a fallback if that player does not support the playlist format.

3. [ ] **Remembered podcast subscriptions and automatic downloads.** Save favourite RSS/Atom feeds, refresh them for new episodes, and download authorised episodes to the phone before the player is connected. Include per-feed episode limits, Wi-Fi/charging preferences and a clear pending-transfer queue.
   - Comparison: **AntennaPod** **“downloads the episode in the background”** when automatic downloading is enabled. [Official automatic-download documentation](https://antennapod.org/documentation/automation/automatic-downloads).
   - Done when: subscriptions survive restart; refreshes do not duplicate episodes; limits and network preferences are tested; downloaded episodes transfer through the existing verified USB path. Background scheduling must respect Android restrictions and must not imply the disconnected player is receiving files.

4. [ ] **True pause and resume.** Resume a supported interrupted download from its previous byte position instead of restarting the entire file. Investigate USB write resumption separately; it depends on the SAF provider's seek/write capabilities. Persist recoverable job state without assuming transient source permissions remain usable.
   - Comparison: **Advanced Download Manager** advertises **“resuming of interrupted downloads”**. [Developer listing on Google Play](https://play.google.com/store/apps/details?id=com.dv.adm).
   - Done when: tests cover HTTP Range acceptance/rejection, changed source validators, permission loss and process interruption; final output passes full integrity verification. Retain safe restart as the explicit fallback where byte-range or seek support is unavailable.

5. [ ] **Saved source-to-destination rules.** Remember named folder pairs such as Drive/Audiobooks to RUIZU/Music/Audiobooks, together with organisation and duplicate preferences. Reuse persisted SAF permissions and the existing transfer manager rather than creating another transfer engine.
   - Comparison: **FolderSync** offers **“Create folder pairs”**. [Official feature list](https://foldersync.io/features/).
   - Done when: rules can be created, reviewed, edited and removed; settings survive restart; unavailable providers and revoked grants produce a clear recovery action. Preview the intended source and target before starting, and never turn a rule into destructive mirroring.

6. [ ] **Automatic transfer when the player is connected.** Recognise the configured player and offer to run its saved transfer plan. Make unattended operation a separate, explicit opt-in; do not start copying to every attached USB drive.
   - Comparison: **doubleTwist Sync** has **“Automatically sync when device is connected”**. [Official device-window guide](https://www.doubletwist.com/help/question/108/).
   - Done when: device/folder identity and permission checks are tested; repeated mount events cannot create concurrent or duplicate jobs; unplugging cancels safely; reconnect behaviour works on the physical A55 and RUIZU. Reuse USB detection and the foreground service, and respect Android background-launch restrictions.

7. [ ] **Permanent transfer history.** Keep searchable records across app restarts: which files transferred, which were verified or skipped, failures, dates and the associated destination. Offer a retry action for failed items and an optional diagnostic export that excludes credentials and private download URLs.
   - Comparison: **FolderSync** provides **“Detailed sync logs and history”**. [Official feature list](https://foldersync.io/features/).
   - Done when: completed and interrupted jobs are recorded accurately after restart; retention is bounded; history can be cleared without deleting audio; retry revalidates sources, permissions and duplicates. A partial or failed job must never appear successful.

8. [x] **Import and export podcast subscriptions.** Import an OPML subscription list, preview and select the feeds to add, avoid duplicate subscriptions, and export the current list for backup or another podcast app.
   - Comparison: **AntennaPod** says OPML can **“transfer your subscriptions to another podcast app”**. [Official backup and OPML documentation](https://antennapod.org/documentation/general/backup).
   - Done when: tests cover malformed and oversized OPML, duplicate feeds, Unicode titles and export/import round trips. Import must preserve existing subscriptions, avoid XML entity expansion and keep private feed credentials out of logs.

9. [ ] **Volume normalisation.** Offer an opt-in way to make transferred copies play at more consistent loudness across music, podcasts and sermons. Explain whether the chosen method changes audio samples or writes loudness metadata, and leave the source untouched.
   - Comparison: **MediaMonkey** states **“Track Volumes can be leveled”**. [Official portable-device sync documentation](https://www.mediamonkey.com/wiki/WebHelp:Exporting_Tracks).
   - Done when: tests cover clipping avoidance, cancellation and source preservation; actual RUIZU playback establishes which loudness methods it honours. Unsupported ReplayGain metadata must not be presented as an audible fix.

10. [ ] **Player-compatible cover artwork.** Embed, resize or convert artwork in transferred copies according to a verified device profile. Preserve the original audio and artwork; do not modify the player's existing database.
    - Comparison: **MediaMonkey** offers **“Copy Artwork to file tags”**. [Official sync-profile tagging documentation](https://www.mediamonkey.com/wiki/WebHelp:Exporting_Tracks#Tagging).
    - Done when: tests cover missing, malformed and oversized images and metadata preservation; representative covers display correctly on the exact RUIZU model. Report unsupported artwork features honestly.

11. [ ] **Clear safe removal / eject guidance in the app.** Add a prominent **Safely remove player** action beside the transfer controls so it is obvious how to disconnect the RUIZU. Show whether copying, verification or cleanup is still using the player; explain when to wait and how to use Samsung/Android's system USB eject control.
    - Source: **Josh's app feedback:** “It unclear in the app how to safely remove the ruizu player”. This is a usability requirement from actual use, not a comparison-app claim.
    - Done when: the action is discoverable without scrolling through setup notes; removal is blocked or clearly unavailable while any player I/O is active; the app opens a supported system eject/storage screen where possible and otherwise gives clear Samsung My Files/system-storage steps. Do not claim that cancelling a job, closing the app or finishing a copy has ejected the device. Distinguish **transfer finished**, **eject still required**, **system-confirmed ejected**, and **disconnected / eject status unavailable** without inventing confirmation. Test active-copy, read-back verification, pending cleanup, completed, cancelled and disconnected states; verify the actual eject flow on the Galaxy A55 and physical RUIZU. No root, formatting, forced unmounting or library database changes.

## Verified foundation and current limitations

Evidence from the actual Samsung Galaxy A55 + RUIZU session (reported by the device-testing agent on 20 November 2026):

- The phone detected the ACTIONS USB drive as volume `0000-006F`; the app resolved its destination folder. A read-only scan found **1,617 existing files** and approximately **40 GB free**.
- A 52,079-byte HTTPS MP3 was transferred successfully through the app, byte-for-byte matched its separately downloaded source by hash, and a repeat transfer correctly skipped the duplicate. The temporary test audio was removed, leaving the earlier player file count unchanged.
- An on-device WAV-to-MP3 conversion self-test passed, and Android launched a non-debuggable version 1.2.1 APK. A test M3U file was written and read back byte-identical; this does **not** prove playback compatibility.
- Current automated evidence: PRs #27 and #28 are merged; CI run 44863422504 passed JVM/APK verification, release emulator launch and 21 Android instrumentation tests (zero failures, errors or skips). Item 12 is complete. Cross-app grants and missing-name fallback are tested; this does not prove the reported phone failure is resolved.
- The player inventory and `LibraryComparison` (statuses `LIKELY_PRESENT`, `POSSIBLE_MATCH`, `MISSING`) are implemented and have a JVM test. They were **not** yet used end-to-end to compare the physical RUIZU with an authorised local/Google Drive source collection.
- The file picker, Drive DocumentsProvider, real share intent, full albums, USB interruption/cancel recovery, actual RUIZU indexing/playback, and end-to-end safe eject remain incompletely validated.
- The app already includes source selection, folder organisation, RSS/Atom browsing, HTTPS links, podcast subscriptions/download preparation, playlists/OPML, saved plan, transfer history, retry, MP3 conversion, read-only library audit and eject **guidance**. Do not rebuild these from scratch or mark them finished on presence alone.
- Previous installer verification (historical): **1.2.4 / versionCode 8**, package `au.com.parristech.ruizusync`, release certificate SHA-256 `d16afeee87dd25be0421223d748ab175cbb37b4c5152042933342cc5880307b4`. APK SHA-256 `1e5802bb6abc7071c798257b4f74ad90c52a18e73726c2e93fab9c52e16bb212`; source PR #28 / merge `fd5c3e789eeabb0e87d96c47417925c8b1499178`. Package/version and signing passed CI; the downloaded installer certificate was independently checked. Physical installation, preservation of saved settings/SAF grants and transfer on 1.2.4 remain unverified. Any legacy `.stable` migration still needs separate evidence.

## Numbered validation and completion backlog (12–28)

The original feature items 1–11 above remain the authoritative feature-specific acceptance criteria. These additional numbers capture the **outstanding work identified from the real-device audit** and should be completed in priority order. **Do not mark an item complete until its evidence and state are linked.**

12. [x] **Execute all existing Android SAF instrumentation tests in CI.** Run `SafTransferTest`, `DestinationAccessTest`, `TransferManagerTest` and `MainActivityTest` against the test-only `FakeUsbProvider` on an API 34 emulator. Keep the `safTest` test-only provider isolated from the distributed APK; publish actual test counts, failures, logs and artifacts. **VERIFIED COMPLETE:** [PR #27](https://github.com/Parris-Tech-Services/JoshBooksOnline/pull/27) and [PR #28](https://github.com/Parris-Tech-Services/JoshBooksOnline/pull/28) are merged. [CI run 44863422504](https://github.com/Parris-Tech-Services/JoshBooksOnline/actions/runs/44863422504): 21 instrumentation tests, zero failures/errors/skips; [XML and log artifact](https://github.com/Parris-Tech-Services/JoshBooksOnline/actions/runs/44863422504/artifacts/14691185840). Includes the four named suites, MediaStore, shared-name fallback and actual cross-app grant tests. Done when all suites run, pass, and failures are real regressions rather than skipped jobs.

13. [ ] **Prove the read-only inventory on physical RUIZU volume `0000-006F`.** Enumerate supported audio, Music/Podcasts folders, unknown extensions and system files; exclude `.LIB` data from changes. Reconcile the app's inventory with a separate independent scan and demonstrate that all existing files are left untouched. Done when a real inventory report and totals are captured and reconciled.

    - **Independent scan evidence (21 November 2026, volume `0000-006F`, read-only):** the player held **1,617 files (896 audio)** at the start of the session and **1,637** after the delivered bundle. The **+20** was accounted for exactly: **8** delivered files (6 in `Music/RUIZU-Bundle/`, 2 in `Podcasts/RUIZU-Bundle/`, all byte-identical to their sources by SHA-256) and **12** audio files copied from `/sdcard/Music` at 14:13–14:16 — 11 byte-identical to the phone copies and 1 (`Nanna Voice 251010_173929.mp3`) a genuine `.m4a`→`.mp3` conversion, which also demonstrates the transcoder running on the real device.
    - **No pre-existing file was modified:** files that already existed kept their original timestamps, including both earlier copies of *Marconi Union - Weightless*. The six `.LIB` databases were rewritten **by the player itself** on mount (12:29, then 14:12 on the next plug-in), not by the app.
    - The app then transferred a further ~355 files into `Music/RUIZU-Bundle/`; the player finished this session at **2,004 files / 28.07 GB used / 36 GB free**. That folder was then organised into typed subfolders by hand, with a reversible move manifest, under the project rule that no file is deleted or overwritten.
    - **Still open:** the app's *own* inventory totals were never compared against this independent scan in the same session, so the item stays incomplete.

14. [ ] **Demonstrate missing-music and missing-podcast comparison against actual sources.** Select an authorised phone/Drive music folder or podcast download collection, invoke the existing comparison engine, and show `LIKELY_PRESENT`, `POSSIBLE_MATCH`, and `MISSING` with clear caveats. Include true duplicates, same name/different size, and renamed/alternate-format versions. Never classify a track as “missing” without a source catalogue. Done when real reports and missing-only transfer selection are verified without deleting existing tracks.

15. [ ] **Add reliable artist/album/podcast grouping and incomplete-album reporting.** Extract available tags and folder structure without inventing metadata; distinguish unknown albums/artists from absent ones. Define what constitutes a “complete album” (an independently known source tracklist, not guessed from a folder), handle compilations and various artists, and show uncertain cases honestly. Done when album-level comparison against a known source tracklist passes tests and a real scan. Cross-references library comparison item 14.

16. [ ] **Verify phone/Google Drive file and folder selection end-to-end.** Exercise `Add files`, `Add folder`, nested album trees, the Android DocumentsUI picker and Google Drive's SAF/DocumentsProvider; test permissions after app restart, unavailable or offline files and revoked grants. The existing UI automation sometimes receives `null root node`; avoid stale UI dumps and blind ADB interactions. Done when representative local and Drive audio can be selected, copied and read back on the player.

17. [ ] **Resolve MediaStore/share filename handling.** Reproduce the shared MP3 that displayed numeric URI ID `1000110460` and was marked unsupported; use properly granted `content://` URIs, real Files-app sharing and synthetic intents as separate test cases. Check `DISPLAY_NAME`, MIME type, size and metadata-projection fallbacks. Do not call it an app defect until the test harness is ruled out. Add a regression test for the proven cause and verify `ACTION_SEND` and `ACTION_SEND_MULTIPLE`.

18. [x] **Physically validate RUIZU indexing and playback.** After safe system eject, demonstrate player playback of a full copied music album, a podcast, a converted MP3, nested folders, Unicode filenames and an exported M3U playlist. Test correct order, audio quality and supported formats. A byte-identical file on USB proves transfer integrity **not** that the RUIZU plays or indexes it.

19. [ ] **Test failure handling and recovery without sacrificing original files.** Cover unplug during active copy, unplug during SHA-256 verification, cancellation, failed cleanup, storage exhaustion, cable/provider I/O failure, revoked access, app process death and retries. Confirm no pre-existing songs, playlists or `.LIB` databases are removed, existing files remain playable, and partial temporary outputs are either safely cleaned or transparently reported. Test the FAT32 file-size limit and large-file behaviour. Reuse the journalled verified writer, not a second transfer engine.

20. [ ] **Finish transfer history and progress validation.** Check progress/speed/ETA, foreground notification and Cancel, SHA-256 reporting, failed-only retry, persisted run history and queued/resumed work across restarts. Ensure a partial or interrupted run cannot be presented as successful; enable bounded, privacy-safe diagnostics. Cross-reference item 7.

21. [ ] **Validate podcast feeds and prepared downloads.** Reproduce the earlier “Feed is larger than 5120 KB” case with a genuine large RSS/Atom feed. Verify early-stop parsing, feed redirects, subscription persistence, episode identification and duplicate avoidance, HTTPS Range resume, changed remote content, cancellation, storage limits and failed/partial files. Confirm download-to-phone while disconnected, followed by a verified player copy. Cross-reference items 3, 4 and 8.

22. [ ] **Finish one safe, update-compatible signed Android release identity.** Use the current canonical 1.2.6 identity and the evidence above. Compare the installed Galaxy A55 APK certificate fingerprint with the release key, make the canonical package choice explicit, ensure versionCode increases and keep existing app data/SAF permissions. Run signature/manifest, emulator install/update and physical update checks. Don't create competing package identities or tell the user to bypass Play Protect. Track whether an existing `.stable` install needs migration, rather than silently uninstalling it.

23. [ ] **Validate safe removal and USB lifecycle states.** Keep prominent system-eject guidance, distinguish copying, verification, cleanup pending, transfer finished, eject required and system-confirmed unmount where Android exposes it. Verify Samsung's actual eject flow on the Galaxy A55 with the RUIZU. Do not claim the app itself ejected a device merely because file I/O finished. Cross-reference item 11.

24. [ ] **Validate saved folder plans and connection-triggered offers.** Test restoring configured volume/folder identity, a saved source-to-destination plan, correct Android SAF permission persistence and explicit confirmation before copying. Repeated USB attachment must not create simultaneous transfers or copy to the wrong drive. Background unattended sync remains future functionality unless expressly authorised and tested. Cross-reference items 5 and 6.

25. [ ] **Complete media preparation quality improvements only after the transfer core is proven.** Finish opt-in volume normalisation (including clipping avoidance and source preservation) and player-compatible cover artwork embedding/resizing. Validate audio output and actual RUIZU artwork behaviour before claiming support. Cross-reference items 9 and 10; these are *not* immediate release blockers for basic transfer.

26. [ ] **Plan remaining optional convenience features without presenting them as shipped.** Background-scheduled new-podcast downloads and notifications, per-feed Wi-Fi/charging/episode limits, multiple saved transfer plans, richer audiobook/playlist browsing, and Android 14+ user-initiated data transfer job migration. Keep these lower priority than 12–24. Test against the relevant Android lifecycle restrictions when implemented.

27. [ ] **Publish evidence-backed release notes and a verified installable APK.** After changes, require unit tests, all relevant Android instrumentation tests, static signing/manifest validation, emulator install/launch, and specifically named physical-device checks. Publish the precise release build with versionCode/versionName, canonical application ID, signer fingerprint, CI run, PR/commit SHA and a direct APK download link. Clearly separate **VERIFIED COMPLETE**, **PARTIALLY COMPLETE**, and **BLOCKED**; avoid claiming all features or Google Drive support are physically verified until they are.

28. [ ] **Support video/movie transfer for the player's video capability.** The mounted player exposes `VIDEO/` and `VIDEO.LIB`. Its bundled `VIDEO/All DX.avi` is MJPEG at 208×176 with IMA ADPCM WAV audio, but that vendor example **does not establish that a newly encoded AVI will play**. RUIZU Sync remains audio-only; the app has no video import, conversion or transfer workflow.
    - **Physical-device evidence (22 November 2026, reported by the user):** `T0-VENDOR-COPY.avi` (the original bundled AVI under a different name) **plays**; `T1-OLD-FORMAT.avi`, `T2-NEW-FORMAT.avi` (header-normalised) and `T3-XVID-MP3.avi` each show **“Format error!”**; `T4-AMV.amv` at **160×128, 15 fps, IMA ADPCM AMV 22,050 Hz mono** **plays**. Therefore **AMV 160×128 is the validated target for the next single-film test**. Do not label newly encoded MJPEG AVI as playable from codec/header checks alone.
    - **Pending:** a complete Wreck-It Ralph AMV encode/copy was started by a separate device agent; no confirmed final film playback, fully verified copy to the RUIZU or completed in-app transfer has been reported. The earlier 252 MB AMV from the Termux helper was verified in phone Downloads but **not copied** because Termux lacked player write permission. The full movie must be observed on the player before calling film playback complete.
    - **Done when:** the Android app can select a video and letterbox/convert it to the **hardware-verified 160×128 AMV profile**; display realistic size/quality expectations; transfer via the **existing** journalled and SHA-256-verified writer; preserve originals and existing player files; handle cancellation, interruption, storage exhaustion and the FAT32 size limit; correctly report eject requirements; and successfully play a full converted film on the physical unit after safe system eject. No overwrite/delete of existing content or `.LIB` mutation.
    - **Scale caution:** an earlier estimate of roughly 18 GB for a 35-film library was based on the **failed MJPEG AVI** output, not the now validated AMV profile. Recalculate real AMV sizes and elapsed conversion time before contemplating any bulk transfer.
    - Comparison: media converters such as MediaMonkey and doubleTwist use per-device profiles. This integration must use the actual measured and hardware-tested AMV profile, not an assumed one.

### Suggested order for the next work session

**First:** verify the 1.2.6 upgrade (22), then real sharing (17), selection (16), inventory/comparison (13–14), safe eject/playback (23 → 18), physical recovery (19) and final release evidence (27). Item 12 is complete.
**Then:** 15, 20, 21, 23, 24 as validation permits.
**Later:** 25–26 and 28 after the existing workflows are reliable.

### Acceptance evidence checklist

For every numbered item, record its PR/commit, the exact tests executed and results, device/provider involved, reproduction steps and any remaining blocker. Document what was *observed* separately from what source code merely intends to do. Preserve original audio and original player files, and never require disabling Play Protect or overriding an Android warning.


## Suggested implementation order

**Current priority:** finish **22 → 17 → 16 → 13 → 14 → 23 → 18 → 19 → 27**. Item 12 is complete; rerun its tests when relevant code changes. Video (28) is deliberately last because it depends on the proven transfer core and on device playback acceptance. Legacy feature priorities 1–11 remain for detailed acceptance but should not displace core validation. Saved folder rules (5) are a prerequisite for connection-triggered transfer suggestions (6); saved subscriptions (3) are a prerequisite for useful OPML import/export (8).

## Shared completion rules

- Keep existing player files, Music/Podcasts folders and library databases intact. Never format the player or introduce automatic deletion/overwrite.
- Reuse the current serial transfer manager, cancellation, journalled temporary outputs and SHA-256 verification. New transforms operate on new copies.
- Keep private Drive content authenticated. No public private-file endpoints, credential logging, root requirement or DRM bypass.
- Treat saved state, imported files, feeds, metadata and provider responses as untrusted; bound background work and storage use.
- Test according to the change, including errors, interruption, cancellation and safe recovery. Mark an item complete only with named test/build evidence and any required physical-device results.
- A phone may prepare downloads while the player is disconnected. USB transfer still requires a connected, mounted and authorised destination; Wi-Fi features elsewhere do not give this player Wi-Fi hardware.

See [README.md](README.md) for the delivered app, supported build path, installation, update limitations and outstanding physical acceptance checks.
