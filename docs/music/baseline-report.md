# Music Atlas baseline report

## Source baseline

- Baseline commit: `6956a21015a36378829589c848e954adb3a27ce8`
- Baseline commit subject: `Expand podcast catalogue`
- Baseline branch: `main`
- Implementation branch: `feature/music-atlas-module`
- Remote comparison: local `main` matched freshly fetched `origin/main` (`0` ahead, `0` behind)
- Working tree before validation: clean
- Node: `v24.16.0`
- npm: `11.13.0`
- Next.js: `16.2.9`

The validation commands below ran before any Music Atlas source changes. The feature branch was created only after the baseline gate passed.

## Install

`npm install` completed successfully through `npm.cmd install`. The PowerShell `npm.ps1` launcher was blocked by the machine execution policy, so the Windows executable launcher was used for the same npm operation.

npm reported 7 dependency audit findings: 5 moderate and 2 high. No dependency versions were changed to address these pre-existing findings. npm 11 also normalized peer and optional-package metadata in `package-lock.json`; that install-only rewrite was discarded so the baseline remained identical to the clean source commit.

## Validation results

| Command | Result | Details |
| --- | --- | --- |
| `npm run lint` | Pass | ESLint completed with no warnings or errors. |
| `npm run typecheck` | Pass | `tsc --noEmit` completed with no errors. |
| `npm test` | Pass | 8 of 8 suites and 45 of 45 tests passed; 0 snapshots. |
| `npm run build` | Pass | Next.js 16.2.9 Turbopack production build compiled, type-checked, and generated all 25 pages successfully. |

## Existing warnings and failures

- No lint, typecheck, test, or production-build failures existed at the baseline commit.
- No lint or build warnings were emitted.
- npm's audit summary reported 7 dependency vulnerabilities (5 moderate and 2 high).
- The local PowerShell execution policy blocks the `npm.ps1` shim; use `npm.cmd` in this environment.
- There was no Playwright configuration or `e2e` directory at baseline. A broad Playwright invocation would also discover Jest tests, so Music end-to-end coverage needs a focused configuration.

These environment and dependency warnings are documented only. They are outside the Music Atlas implementation scope.
