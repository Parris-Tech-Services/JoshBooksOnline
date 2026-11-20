# RUIZU Sync (Android)

Native, no-root USB transfer companion for Samsung/Android. This is an independent Android app inside JoshBooksOnline; it does not change the Next.js site.

## Features
- RUIZU 64GB MP3-player transfer using Android's Storage Access Framework (no broad-storage permission or Termux required).
- One-time permission to existing RUIZU **Music** and **Podcasts** folders; Android persists access.
- Multi-select audio from the Android picker, including locally stored MP3s and Google Drive files. Some Drive providers return generic MIME types, so file extension is checked.
- Download freely available or otherwise authorised direct HTTPS MP3/audio links into Music or Podcasts.
- Browse HTTPS podcast RSS feeds, select episodes and transfer them to Podcasts.
- Receive audio through Android **Share**.
- Open JoshBooks, Drive, Spotify, YouTube Music, and YouTube links in their official web or installed app contexts.
- Skip files with identical names and sizes; auto-number filenames on conflicts; never format USB storage, overwrite or delete existing RUIZU files.
- Remove incomplete output files after a failed copy.

**Limits:** Spotify and YouTube Music/YouTube streaming/offline downloads are not transferable MP3s. JoshBooks provides library links but does not yet expose a dedicated authenticated Android export endpoint. Use the Google Drive picker for original JoshBooks audio files. Format compatibility depends on the model of RUIZU; MP3 is the safest choice. Android may refuse selecting the USB volume root; choose Music or Podcasts subfolders instead. Disconnect/eject only after the transfer reports completion.

## Build

Requires JDK 17, Android SDK 35, and Gradle 8.9:

```sh
gradle -p android/ruizu-sync :app:testDebugUnitTest :app:assembleDebug
```

Download the APK from the **RUIZU Sync Android APK** GitHub Actions workflow artifact (`ruizu-sync-debug-apk`) and install it on your phone.

## First use
1. Connect the RUIZU over USB and confirm Samsung My Files shows **USB storage 1**.
2. Open RUIZU Sync, tap **Choose RUIZU Music folder**, navigate to USB storage 1 > Music, and select **Use this folder**.
3. Repeat for **Podcasts**.
4. Tap **Add music from phone / Google Drive**, select one or multiple MP3s, and let the transfer finish.
5. For podcast episodes, paste a public HTTPS RSS feed and select the episodes to copy.

This initial release is not verified on a physical RUIZU; GitHub Actions builds and unit-tests the APK. Device testing remains necessary before treating USB transfers as fully verified.
