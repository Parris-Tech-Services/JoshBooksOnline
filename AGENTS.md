# JoshBooksOnline agent rules

Read `codingprinciples.md` before changing code.

Engineering principles: v5.1
Assurance tier: 3
Canonical repository: https://github.com/Parris-Tech-Services/JoshBooksOnline

This app contains authentication and personal library data, so Tier 3 rules apply.

## Change discipline
- Never push directly to `main`; use one coherent branch and PR.
- Understand the full data path before changing auth, imports, catalogue persistence, book files or user data.
- Add behavioural regression tests for changed important behaviour.
- Run lint, typecheck, tests, production build, and the CRAP4all gate before merge.
- Do not weaken tests, typing, linting or the CRAP threshold to make a change pass.
- Never commit secrets or real OAuth credentials. Treat imported files, URLs, browser storage and provider responses as untrusted input.
- Destructive/persistent-data changes require a recovery plan and representative restore/migration evidence.
- Report verification as named evidence, not confidence.
