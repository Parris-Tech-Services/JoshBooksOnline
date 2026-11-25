# Episode pipeline: integration and device acceptance contract

This is a coordination and release checklist, not an additional transfer program.
The desired release is the existing Android package `au.com.parristech.ruizusync`.
Keep its release certificate, user data and persisted document grants.

## Ownership while multiple implementations are under development

- Phone-side agent: owns the existing live transfer session, USB device observations,
  real playback and on-device evidence. Do not start a competing writer.
- Python pipeline agent: owns its existing resumable downloads, legacy reconciliation,
  approved-show rules, and regression tests. Preserve the phone's existing database.
- Android/release agent: owns the native APK build, SAF writer, scheduling, regression
  gates and signed upgrade acceptance. The native worker is opt-in; do not activate it
  on a live player until the old writer and its scheduler are stopped.
- Final release: one active episode scheduler, one authoritative episode ledger and
  one writer to VIDEO/. No silent fallback to a second writer. No external process
  dependency in the desired native release.

## Shared invariants

1. Read the existing `.ruizu-sync-device.json` marker. The identity field is `id`.
   A storage-volume UUID is not a physical player ID. Reject missing, invalid or
   mismatching marker before each write/publish/recovery operation.
2. Approvals are per player. Unapproved sources must never be downloaded or copied.
   For a restricted player, approve individual show folders rather than a shared
   parent containing other shows. New show discovery must not implicitly approve it.
3. Copy verified staged files first, before network discovery. Then prepare one
   episode independently; interrupted work resumes from the last durable checkpoint.
4. Never overwrite unrelated media or delete an unowned partial file. Write to a
   uniquely owned temporary document, verify contents, publish, then re-read and
   compare SHA-256. A filename or size match alone is not proof of transfer.
5. No sensitive catalogue, credentials, IDs, household names or private file paths
   belong in this public repository or CI artifacts.
6. No completed status from compilation alone; establish executable runtime evidence.

## Safe migration boundary

Do not copy a live SQLite file. First stop its writer and scheduler, create an
immutable consistent export/backup, and identify the exact schema/version. Preserve
the original ledger and staged media until the native import is accepted. Use stable
source identifiers plus source version, marker ID, verified byte count, and content
hash for reconciliation. Import only explicitly approved sources. Validate each
existing staged AMV or player file independently; treat missing evidence as pending,
not verified. Never import private paths or IDs into this repository. The actual
legacy schema and migration have not yet been reviewed; no importer is claimed here.

## Release gates

- JVM unit tests and Android instrumentation pass on the exact release head.
- Actual bundled ARM64 and x86_64 FFmpeg/FFprobe binaries; Android instrumentation
  executes the x86_64 binary and converts/decodes synthetic video; arm64 is verified
  on hardware. Confirm 160x128, 15 fps, ADPCM IMA AMV mono, 22050 Hz.
- Signed stable APK verifies against the existing release certificate, with a higher
  versionCode and unchanged canonical package ID. Do not install a debug-signed APK
  over the current stable installation.
- Verify upgrade preserves music, podcasts, saved destinations and SAF permissions.
- On the actual phone: choose one known approved source; verify durable grant after
  process restart, catalogue caching, download checkpoint, staged conversion,
  offline preparation, and no download of an unapproved show.
- With player A attached: staged copy precedes any Drive request; verify SHA-256 and
  playback on hardware. Disconnect during a copy and verify safe retry with no
  duplicate or unrelated deletion. Attach player B: reject A's pending work.
- Verify per-player status, low-storage behaviour, Wi-Fi policy, pause/retry, app
  restart, and eventual background scheduling without other writers running.
- Record exact APK hash, Git head, tests, and hardware outcomes (pass/fail/not run).
  A build success is not physical-device acceptance.

## Current known limitations

Folder discovery depends on a durable SAF tree grant; if the Drive provider does not
offer one, use individual file grants until direct authenticated Drive integration
is available. Native incomplete source downloads currently restart the incomplete
stage; do not claim byte-range resume. Full legacy import and show-policy controls
beyond approved source folders remain to be integrated after inspecting the other
pipeline's actual schema and code. Cross-app mutual exclusion cannot be guaranteed
by an in-process lock; the ownership handover above is mandatory.
