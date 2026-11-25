# RUIZU episode pipeline (`ruizu-episodes`)

Engineering principles: v5.1 · Assurance tier: 3 (inherits the repository's tier) ·
Canonical repository: https://github.com/Parris-Tech-Services/JoshBooksOnline

A persistent, incremental pipeline that keeps RUIZU players topped up with TV episodes from a
shared Google Drive library. It replaces the hand-run scripts that previously lived only on the
phone (`discover.py`, `pick-variety.py`, `encode-episodes.sh`, `copy-set.sh`, `sync-watcher.sh`,
`verify-player.sh`), which re-walked the whole library every session and kept their state in
loose TSV files and logs.

Each episode moves through its own steps and every step is recorded as it completes:

```text
discovered → downloaded (resumable) → converted → verified → staged → copied → verified on the card
```

So a run can stop anywhere (phone locks, Termux is killed, cable pulled) and the next run carries
on from there. A failing episode backs off on its own (15 min, doubling, up to 24 h; gives up
after 5 attempts) and never blocks the others.

Standard-library Python 3.11+, `curl`, `ffmpeg`/`ffprobe`. Runs in Termux on the phone.

## Why it is safe to leave running

- **Never writes to an unidentified player.** Both players mount as `0000-006F`, so identity comes
  from the `.ruizu-sync-device.json` marker the RUIZU Sync app already uses (same format; see
  `android/ruizu-sync` `PlayerIdentity`). A player without a marker, or one that is not set up
  here, is reported and left alone.
- **Per-player show lists.** A player only receives shows explicitly allowed for it
  (`allow`, or `--all`). Shows that no player is allowed are never downloaded.
- **Stays out of the app's way.** There is no lock shared with the RUIZU Sync app, so if the app has
  an in-flight copy (`VIDEO/.ruizu-<uuid>.part` written in the last 10 minutes), no episode is
  copied that run. Preparation carries on. Older app leftovers are ignored and left for the app.
- **Nothing half-written in `VIDEO/`.** Copies are written as `<name>.ruizu-partial`, fsynced,
  read back with the page cache dropped, SHA-256 compared, then renamed into place. An
  interrupted copy is cleaned up on the next connection; a file it did not write is never
  touched, and a file you delete from the player by hand is not pushed back.
- **Only the proven AMV profile is staged:** AMV 160×128 at 15 fps, `adpcm_ima_amv` 1470-byte
  blocks, 22.05 kHz mono (TODO item 28). The encode is verified for streams, dimensions and
  frame coverage of the whole source before it is published.
- **Drive responses are untrusted.** A throttle or sign-in page is never mistaken for an empty
  folder or for media, and an empty listing never marks episodes as deleted.
- **Bounded runs.** Per-run download allowance, staging cap, phone free-space floor, Wi-Fi
  requirement, a cap on folder listings per run, and an optional deadline: no copy or
  conversion starts that is not expected to finish before it.

## Install / update (on the phone, in Termux)

```bash
pkg install python ffmpeg curl termux-api   # termux-api also needs the Termux:API app
cd ~/repos/.../JoshBooksOnline && git pull
bash tools/ruizu-episode-pipeline/install.sh
```

Installs the program to `~/.ruizu-player/app/` (the previous version is kept beside it as
`ruizu_episodes.previous` for rollback), a `ruizu-episodes` command, and two Termux:Widget
shortcuts: **RUIZU Episodes - Sync now** and **RUIZU Episodes - Status**. Re-running updates it.

## First-time setup

```bash
ruizu-episodes import-legacy        # adopt the old scripts' lists and staged AMVs (read-only, repeatable)
ruizu-episodes add-root <folder-id> # optional: the Drive folder whose sub-folders are shows,
                                    # so shows added to Drive later are found automatically
ruizu-episodes scan                 # optional: discover now rather than on the next run
# plug in one player, then:
ruizu-episodes claim "Kid's player"
ruizu-episodes allow "Kid's player" "How I Met Your Mother" Heroes   # names as in `ruizu-episodes shows`
ruizu-episodes per-show "Kid's player" 2                             # optional, default 1
```

Repeat `claim`/`allow` for the other player. Episodes already on a player are recognised by
name and count as had, so each show continues from where it is.

## Everyday use

- Plug a player in and tap **Sync now** (or `ruizu-episodes run --minutes 7`, or
  `--until 22:10`). It copies what is already prepared first, then discovers and prepares more,
  then copies again. You can unplug at any time.
- `ruizu-episodes schedule` runs it every 15 minutes through the Android job scheduler, only on
  unmetered networks and when the battery is not low. Each scheduled run is limited to 9 minutes
  because Android stops scheduled jobs after about ten; the work resumes on the next run.
  `ruizu-episodes unschedule` stops it.
- `ruizu-episodes status` shows what is ready to copy and what will be prepared next per player.

Each sync gives a player up to `per_show` new episodes of each allowed show, round-robin across
shows (smaller files first within a round). It also prepares the following episodes so they are
ready next time.

### Starting a run from the RUIZU Sync app

The Android app's **Sync episodes** button asks Termux to run exactly this command:

```bash
{ ruizu-episodes run --minutes 7 --quiet; echo "__ruizu-exit:$?"; } > '<player>/.ruizu-sync-run.log' 2>&1
```

That is the whole of the app's involvement: it starts a run and reads the log back from the player.
It never copies an episode and keeps no record of what was copied, so `pipeline.db` remains the only
record and the two cannot disagree. The app needs Termux's `com.termux.permission.RUN_COMMAND` and
`allow-external-apps=true`; see `android/ruizu-sync/README.md`.

## Commands

| Command | What it does |
| --- | --- |
| `status` | Library, prepared and failed counts, per-player plan, connected player |
| `run [--minutes N \| --until HH:MM] [--no-scan] [--no-fetch] [--no-copy] [--allow-metered] [--max-download-mb N] [--notify] [--quiet]` | One bounded run |
| `scan [--refresh] [--max-listings N]` | Discovery only |
| `import-legacy` | Read the old `episodes.tsv`, `himym-order.tsv`, `other-shows.tsv`, `.work/variety.tsv`, `tv-items.json` and staged `*.amv` |
| `claim NAME` | Write an identity marker to the connected player (never replaces an existing one) |
| `allow PLAYER SHOW... \| --all`, `deny PLAYER SHOW...`, `per-show PLAYER N` | Per-player policy |
| `add-root FOLDER_ID`, `shows` | Library roots, known shows |
| `retry LABEL... \| --all` | Try failed episodes again |
| `log [-n N]` | Recent events |
| `restore-backup` | Put back the database snapshot taken at the start of the last run |
| `export [--output FILE]` | Read-only JSON for migrating to the native app: players, allowlists, per-player copy states (verified, present, removed) and staged SHA-256 values, keyed by episode label. No Drive ids or paths, but it names players and shows: keep it private |
| `schedule [--every MIN]`, `unschedule` | Termux job scheduler |

## Where things live

| Path | Owner of | Notes |
| --- | --- | --- |
| `~/.ruizu-player/pipeline.db` | **All pipeline state** (SQLite, schema v1, `PRAGMA user_version`) | Folders and when each was listed, episodes and their step, per-player copy history, event log |
| `~/.ruizu-player/pipeline.db.bak` | Recovery point | Replaced at the start of every run; `restore-backup` checks integrity first |
| `~/.ruizu-player/pipeline.json` | User policy | Players (keyed by marker id), allowed shows, limits. Validated on load. Private: never commit it |
| `~/ruizu-episodes/*.amv` | Staged episodes | Same folder and names as the old scripts, so existing encodes are reused |
| `~/ruizu-episodes/.work/` | Transient downloads | `<id>.src.part` resumes across runs; sources are deleted once converted |
| `<player>/.ruizu-sync-device.json` | Player identity | Shared with the RUIZU Sync app |
| `<player>/VIDEO/<label> - RUIZU.amv` | Episodes on the player | |

The old TSV files are only read by `import-legacy`; after that the database is the one source of
truth. Limits live in `pipeline.json` under `limits` (defaults: 1500 MB download per run,
Wi-Fi required, 8000 MB staging cap, 2000 MB phone free-space floor, listings refreshed weekly,
40 listings per run).

## Recovery

- **Database damaged or a bad run:** `ruizu-episodes restore-backup`. Losing the database
  entirely is also recoverable: `import-legacy` plus the next connection (episodes on the player
  are recognised by name) rebuild nearly everything; only the record of episodes deleted from a
  player by hand is lost, so those could be copied again.
- **Bad program update:** swap `~/.ruizu-player/app/ruizu_episodes.previous` back into place.
- **Bad staged file:** it is re-verified before its first copy and rebuilt if it fails.

## Known limits / not yet verified

- Drive's embedded folder view has worked for show and season folders. Whether it lists every
  entry in very large folders has not been verified.
- Wi-Fi detection uses `termux-wifi-connectioninfo`. If it is unavailable, manual runs do not
  download unless `--allow-metered` is given. Scheduled runs rely on the scheduler's
  unmetered-network constraint.
- Phone hardware testing of this tool (actual RUIZU playback of its output, job-scheduler
  behaviour under Doze) has not been done yet. The encode profile and copy method are the ones
  already proven on both players by the earlier scripts.

## Development

```bash
cd tools/ruizu-episode-pipeline
python -m unittest discover -s tests -t . -v      # needs ffmpeg, ffprobe, curl
ruff check ruizu_episodes tests crap_gate.py && mypy
python -m coverage run --branch --source=ruizu_episodes -m unittest discover -s tests -t . \
  && python -m coverage xml -o coverage.xml && python crap_gate.py coverage.xml ruizu_episodes
```

The tests run the real `curl` and `ffmpeg` against a fake Drive served over local HTTP (with
byte-range resume, throttle pages and dropped connections) and a temporary player folder. They
use synthetic video only, with no personal data and no network. CI:
`.github/workflows/ruizu-episode-pipeline.yml`.
