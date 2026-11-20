# RUIZU Sync feature to-do list

Status: planned work, compared with the delivered 1.1.0 app. An unchecked item is not an implemented or verified feature. Keep these numbers stable when adding progress notes.

Purpose: reduce repeated file selection, prepare playable audio before USB connection, and make music, podcasts and audiobooks easier for Josh to manage. Reuse the existing SAF transfer manager, verified writer and Android share flow. This task records the roadmap only; it does not implement the features below. Completion of this documentation task means all eleven agreed features, their sources and acceptance checks are recorded and linked from the app README.

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

8. [ ] **Import and export podcast subscriptions.** Import an OPML subscription list, preview and select the feeds to add, avoid duplicate subscriptions, and export the current list for backup or another podcast app.
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

## Suggested implementation order

Start with **2 (playlists), 3 (podcast subscriptions/preparation), then 1 (MP3 conversion)**. This is a suggested order, not a claim that any work has started. Saved folder rules (5) are a prerequisite for automatic connection-triggered transfers (6); saved subscriptions (3) are a prerequisite for useful OPML import/export (8).

## Shared completion rules

- Keep existing player files, Music/Podcasts folders and library databases intact. Never format the player or introduce automatic deletion/overwrite.
- Reuse the current serial transfer manager, cancellation, journalled temporary outputs and SHA-256 verification. New transforms operate on new copies.
- Keep private Drive content authenticated. No public private-file endpoints, credential logging, root requirement or DRM bypass.
- Treat saved state, imported files, feeds, metadata and provider responses as untrusted; bound background work and storage use.
- Test according to the change, including errors, interruption, cancellation and safe recovery. Mark an item complete only with named test/build evidence and any required physical-device results.
- A phone may prepare downloads while the player is disconnected. USB transfer still requires a connected, mounted and authorised destination; Wi-Fi features elsewhere do not give this player Wi-Fi hardware.

See [README.md](README.md) for the delivered app, supported build path, installation, update limitations and outstanding physical acceptance checks.
