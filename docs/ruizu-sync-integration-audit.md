# RUIZU Sync integration audit (20 November 2026)

## Starting evidence

PR #8, `feature/ruizu-sync-android`, head `077a871ceecb1558e971b00c8d55eb042f364aa5`, 13 commits, 10 changed files. Latest starting Android Actions run **44696376901** succeeded and produced artifact **14611195641**. Thus the latest PR was not blocked on D8; the Termux problem concerns the separate unpublished implementation. History shows SDK setup repairs in `64d8293` and `93a2e8e`.

## Findings and disposition

| Finding | Change |
| --- | --- |
| Same filename/size treated as duplicate despite possibly different bytes | SHA-256 source/output/candidate comparison, including numbered retry outputs |
| Truncated known-length source could be reported complete | Exact byte-count check; no publication after truncation |
| Output marked successful before close completed | Close before read-back verification and publication |
| Visible final filename held incomplete audio | Random journalled temporary document, rename after verification |
| Activity-owned executor lacked cancellation/background lifetime | Serial foreground service, cancellation, progress and wake lock |
| 2 GiB cap and int-sized risks | FAT32-specific 4 GiB minus one limit and 64-bit counters; real generated 2 GiB+ unit stream |
| Long names lost extensions; FAT reserved names unhandled | Extension-preserving UTF-8 bounds and FAT filename rules |
| Unbounded RSS bytes | 5 MiB/100 episodes, declaration processing disabled |
| Stored destination alone implied usable permission | Validate persisted read/write grant before copying |
| JoshBooks button only opened homepage | Authenticated prepare-files/share flow in Drive audiobook Details, no new public endpoints |
| Build had no repository wrapper/checksum/lint/emulator coverage | Gradle wrapper, pinned SDK build tools, lint, unit and DocumentsProvider fault tests |
| Target 35 layout could overlap system bars | Window inset handling on API 30+ |

## Verification record

See Actions test reports for the exact committed revision. Local web checks: lint exit 0 (existing public-player warnings and generated coverage warning); TypeScript exit 0; 154 tests across 24 suites; Next.js production build exit 0; TypeScript-aware CRAP4all headline 20, gate <30 passes. Local Android/emulator evidence is recorded in the final delivery report rather than inferred from compilation.

## Decisions and remaining limits

Reuse the existing Android app and authenticated Drive stream. Browser file sharing avoids introducing Android OAuth secrets or unauthenticated private media URLs. Browser memory is bounded; large selections use Drive SAF. This is a development APK with cache-backed debug signing, not a permanent production signing identity. USB providers do not guarantee atomic rename, so hardware tests are still required. Safe retry restarts instead of assuming seek/range support. Model-specific codec playback, real USB failure behaviour, Android 17/One UI and browser handoff remain physical acceptance items.

No DeepSeek branch/source was present in the initial fetch. During the session `feature/ruizu-sync-android` advanced to `6ceeb22`, introducing the published DeepSeek implementation (`69949ba`). Integration explicitly merged that commit history, retained the larger implementation, removed the old Activity/service prototypes, and routed its TransferManager through the verified SAF writer. Existing overwrite behaviour was disabled at both UI and runtime boundaries. Both legacy pure-logic suites and new tests are run; no results from the phone are inferred. No files from the phone were read or overwritten.
