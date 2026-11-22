# RUIZU Drive comparison (read-only)

Independent **Python standard-library-only** tool to compare a private Google Drive music/podcast catalogue with a mounted player folder or an exported player inventory. No Android app changes, network access, access tokens, writes to source music, writes to the RUIZU player, or `.LIB` editing.

## Getting started

Download the private Drive catalogue bundle from the RUIZU Sync Catalogues folder, then:

```bash
# Quality-check the source catalogue, without a player connected
python3 compare.py audit --drive-manifest /path/to/RUIZU-Drive-Audio-Catalogue.zip --output-dir ./out/audit

# Connected player mounted as /media/USER/RUIZU (example path only)
python3 compare.py scan-player --player-root /media/USER/RUIZU --output-dir ./out/player
python3 compare.py compare --drive-manifest /path/to/RUIZU-Drive-Audio-Catalogue.zip --player-root /media/USER/RUIZU --output-dir ./out/comparison

# Or take an exported read-only inventory from DSH, without connecting the player:
python3 compare.py compare --drive-manifest /path/to/manifest.csv --player-manifest /path/to/player-inventory.json --output-dir ./out/comparison
# Only after verifying the supplied inventory is complete:
python3 compare.py compare --drive-manifest /path/to/manifest.csv --player-manifest /path/to/player-inventory.json --confirm-inventory-complete --output-dir ./out/comparison
```

**Safety:** Output must be outside the player mount for `--player-root`. Files and folders under the player are enumerated **read-only**; symlinks are skipped. The tool never deletes tracks, modifies `.LIB`, downloads audio, or accesses Google Drive directly. Do not publish your private manifest, folder names, or comparison results to a public GitHub repository.

## Input formats

Drive source: CSV with `drive_file_id`, `file_name`, `relative_path`, `size_bytes` or JSON `files` list with those fields; the private export ZIP includes the JSON. An optional `content_sha256` is accepted but is normally unavailable because the Drive metadata-only scan did not download or hash audio.

Player: mount directory **or** a `.json`/`.csv` containing file records with `relative_path` (or `path`, `name`, `file_name`) and `size_bytes` (or `size`). An optional `sha256` is accepted for already independently hashed files. The tool does **not** hash player media by default, to avoid slow full-device reads.

## Result statuses

| Status | Meaning |
| --- | --- |
| `VERIFIED_PRESENT` | Both inventories supply an identical full SHA-256 digest; requires independently computed hashes |
| `LIKELY_PRESENT` | Normalised filename and byte count match; audio content **not verified** |
| `POSSIBLE_MATCH` | Matching normalised name but different size, or contradictory supplied digests |
| `NOT_FOUND_IN_SCAN` | No name match in a complete scanned/supplied inventory; renamed files may still exist |
| `UNCONFIRMED` | No match, but player inventory was not proved complete |
| `SOURCE_EMPTY` | Source file is zero bytes and should not be treated as playable music |

The tool intentionally does **not** claim album completeness or perform deletions, sync or automatic transfer. Normalisation may conflate distinct Unicode names. Name+size is not a proof of identity. Missing from the scanned set is not proof of missing from the physical player, especially if the mount path or inventory is wrong.

Reports: `source-audit.json`, `source-duplicate-candidates.csv`, `player-inventory.json`, `comparison-summary.json`, `comparison-results.csv`. CSV formula-like untrusted filenames are quoted to avoid spreadsheet formula execution.

## Tests

```bash
cd tools/ruizu-drive-compare
python3 -m unittest discover -s . -p 'test_*.py' -v
```

Use with DSH's RUIZU Sync Android work **without changing** the Android package, transfer engine, signing or instrumentation suite.

## Private offline review screen

Open `offline_review.html` locally in Chrome or another modern browser (no server or login needed). The standalone screen imports the existing private Drive catalogue and a separately exported player CSV/JSON, then shows `VERIFIED_PRESENT`, `LIKELY_PRESENT`, `POSSIBLE_MATCH`, `NOT_FOUND_IN_SCAN`, `UNCONFIRMED` or `SOURCE_EMPTY` with reasons. It offers text search, category/status filtering and safe CSV export. It does **not** copy, delete, or control files on the RUIZU.

Because the source catalogue is personal, the repository contains **no preloaded Drive data**. Import the CSV or JSON from your private Google Drive `RUIZU Sync Catalogues` folder. The player inventory import accepts `relative_path` or `path` plus `size_bytes` or `size`; pasted screen text is not a complete size-bearing inventory. The app never contacts a server. Import is bounded to 12 MB per file / 40,000 rows. An unconfirmed scan never produces `NOT_FOUND_IN_SCAN`.

### Browser regression tests

```bash
node --test tools/ruizu-drive-compare/test_offline_review.mjs
```

Only synthetic audio records are used in tests, and no personal audio or Drive links belong in this public repository. The HTML's Content Security Policy blocks external network requests.


## RUIZU player sorting from Termux

`sort_player.py` inventories audio **and video**, but moves nothing by default. It classifies artist/soundtrack folders under the legacy `__1`, loose music, Outlander audiobooks and already-organised podcasts. Unknown folders remain `REVIEW`; video files remain `VIDEO_REVIEW` or `VIDEO_PRESENT` until the player model and playback format are confirmed. Existing playlists are checked so their referenced files are not moved. Files in Music, Podcasts and Audiobooks are left intact; .LIB indexes and non-media/system files are never changed. The CSV and log are written outside the player.

Termux (one line each):

```sh
pkg install -y python curl && curl -fL https://raw.githubusercontent.com/Parris-Tech-Services/JoshBooksOnline/main/tools/ruizu-drive-compare/sort_player.py -o "$HOME/ruizu-sort.py" && python "$HOME/ruizu-sort.py"
```

Read the CSV plan in the phone's Downloads. Only after reviewing each proposed `READY` move, the optional command is `python "$HOME/ruizu-sort.py" --apply`. It performs non-overwriting renames on the mounted player, and a hardware media-library rescan may then be necessary. Mount path is configurable with `--root`; default is the previously observed `/mnt/media_rw/0000-006F`. This path must be mounted and accessible to Termux.

## Movie compatibility: local converter and test videos

There is no single universal RUIZU video format. Some models require very small `.amv` files, while video-capable newer models accept `.mp4`. The script **does not** determine the device model and **does not** copy videos to the player.

`prepare_video.py` uses FFmpeg and FFprobe. Profiles: `amv-160x128` (default), `amv-128x160`, `amv-128x128`, `mp4-320x240`. For AMV, a test with FFmpeg was successfully generated using 15 fps, 22050 Hz mono ADPCM audio and a 1470-byte audio block size.

```sh
pkg install -y python curl ffmpeg && curl -fL https://raw.githubusercontent.com/Parris-Tech-Services/JoshBooksOnline/main/tools/ruizu-drive-compare/prepare_video.py -o "$HOME/ruizu-video.py" && python "$HOME/ruizu-video.py" --sample
```

That creates an eight-second synthetic AMV in the phone's Downloads (or in Termux home if the shared storage link is absent). Copy that clip to the player and test playback. If the model needs a different profile, supply `--profile amv-128x128` or `--profile mp4-320x240` and test again.

Only after a sample plays on the **physical** unit, a locally downloaded movie can be previewed via `python "$HOME/ruizu-video.py" --input "$HOME/storage/downloads/Movie.mp4" --profile amv-160x128` (20-second preview) or converted in full with `--full`. This script does not download from Drive; download the file from your own Drive to the phone first. No DRM bypass is provided. The script writes outside the player and refuses existing output names.
