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