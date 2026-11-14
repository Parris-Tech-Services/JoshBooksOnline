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


## Values gate for every non-urgent task

Before recommending or doing optional work, state the human outcome, who benefits or may be burdened, whether an existing system can be reused or consolidated, the smallest useful step, the stopping condition, and evidence that would show the work helped. Consider commitments the user explicitly states to family, worship, sleep, health, relationships, community, and service. Do not infer private schedules or priorities.

Recommend pausing, reducing scope, consolidating, deleting, or not building when that better protects stated commitments or reduces burden. Raise concerns kindly, explain the reason, offer alternatives, and respect the user's informed choice.

For urgent safety, security, legal, or data-loss work, take the smallest safe action first and review its cost and outcome afterwards. Instruction presence alone is not evidence of agent behavior; report what was actually checked and leave compliance unverified without behavioral evidence.
