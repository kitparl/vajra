# CLAUDE.md — Rules for Claude Code in this repo

## Project
Self-learning, multi-exchange autonomous trading platform. Crypto first (Delta Exchange India, testnet first), Indian stocks and other exchanges later.
Owner: Pranshu. Read `docs/knowledge/0001-project-overview.md` and `0003-architecture-and-data-ownership.md` before any large change.

## Stack (details and versions: `docs/knowledge/0002-tech-stack-and-versions.md`)
- Spring Boot (Java 25 LTS): `core-service`, `risk-service`
- FastAPI (Python): `research-service`, `signal-service`
- Spring Boot (Java 25 LTS): `gateway-bff` (dashboard API, WebSocket gateway, notifier, scheduler)
- Angular: `ui` (frontend only; Node is used just for Angular build tooling)
- **Backend services use only Python and Java. No NestJS, no Node/TypeScript backend** (see `0011`).
- PostgreSQL 18, Kafka, Redis. Contracts (Protobuf) live in `contracts/` only.

## Hard architecture rules
1. **Data ownership:** every table is owned by exactly one service (own Postgres schema, own DB role, own migrations). Never read or write another service's tables. No cross-schema joins or foreign keys. Share data via API calls or Kafka events.
2. **Money/order logic only in `core-service` and `risk-service`.** `gateway-bff` and the FastAPI services never place or modify orders directly (gateway forwards user actions to core/risk APIs).
3. **Risk engine is deterministic.** Separate process, no ML/LLM in its decision path, can veto any order, owns the kill-switch. Learned models can never change risk limits.
4. **Live trading is OFF by default.** Default environment is testnet/paper. API keys and secrets are never committed; use env vars or a secret manager.
5. **Models reach live only through the gate:** registry version, then shadow, then paper, then small live capital. No hot-swapping unversioned models.
6. **Everything is auditable:** every signal, risk decision, order and fill is recorded.
7. **Strategy code must not have lookahead.** Backtest and live use the same strategy code path.

## Release documentation rule (docs/releases)
When a development task is finished (code done, tests pass), BEFORE ending your turn, ask the user (in Hinglish):

> Development complete ho gaya. Kya aap ise push karoge? Agar haan, to kya main is kaam ko release doc me add kar du (major / minor / patch)?

- Wait for the answer. If the user is not pushing, do not create a release doc.
- Never run `git push` yourself unless the user explicitly asks.
- If the user says yes without a level, propose one with a one-line reason and confirm:
  - **major** = breaking change (API/contract/DB incompatible, behavior change)
  - **minor** = new backward-compatible feature
  - **patch** = bug fix or small improvement
- Then follow `docs/releases/README.md` exactly: compute the version number automatically, create a NEW file (never edit released files), and update `docs/releases/VERSION`.

## Knowledge documentation rule (docs/knowledge)
`docs/knowledge/` is the project's long-term memory and is committed with the project.
- Whenever you make or learn something worth keeping (design decision, service responsibility change, important flow, strategy, new concept, a lesson learned, a bug root cause), add it as a **new numbered file** `NNNN-slug.md` using the **next free number**. Numbers are sequential and never reused or renumbered, so the oldest learning is always first.
- Update the index table in `docs/knowledge/README.md` in the same change.
- Edit an old file only to fix mistakes or mark it superseded (add a `Superseded by: NNNN` line); do not rewrite history.
- After adding one, mention it to the user in one line. Do not ask permission for knowledge docs.

## Hinglish topic notes (docs/knowledge/<topic>/)
Besides the numbered files, `docs/knowledge/` has **topic folders** (for example `trading-basics/`, `risk-management/`) with beginner-friendly notes in **Hinglish**, explained with small real-life examples.
- When you add a new concept or feature, also add or extend the note in the matching topic folder (create a new topic folder if none fits). Files in topic folders are named by topic (`NN-slug.md`), not by the global number.
- Style: very simple language, small example first, then project usage. Keep the folder's `README.md` and `docs/knowledge/README.md` topic table updated.

## Working style
- Reply to the user in Hinglish unless they switch language.
- Small, reviewable changes. Add tests for order state machine, risk checks and idempotency whenever touched.
- Do not add features beyond what was asked; note ideas in a knowledge doc instead.

## Project Rules

### 0. Role & Mindset
- Approach every task as a Senior Engineer / Architect would — think about maintainability, scalability, and edge cases before writing code, not after.
- Before implementing, briefly reason through: "What would a senior engineer reviewing this PR flag?" and address it upfront.
- Evaluate trade-offs (performance vs. readability, flexibility vs. simplicity) explicitly when a decision isn't obvious, instead of picking the first working approach.
- Think about how this code will be maintained, extended, and debugged by someone else in 6 months.
- Flag risky decisions (breaking changes, security implications, tight coupling) instead of silently proceeding.
- When multiple valid approaches exist, briefly state which was chosen and why — don't just pick silently.

### 1. Code Simplicity
- Do not over-engineer. Solve the problem in front of you, not the one you imagine you might have later.
- Avoid unnecessary abstraction (extra interfaces, factories, config layers) for logic used in only one place and unlikely to change.
- Prefer the simplest solution that is correct and readable over the "clever" one.
- No speculative generalization — don't add flexibility/config options for hypothetical future use cases (YAGNI).

### 2. Patterns & Architecture
- Follow the existing pattern already used in the codebase for similar use cases.
- If it's a genuinely new kind of problem with no existing precedent, pick the most widely accepted, idiomatic pattern for that language/framework — not an unconventional one.
- Don't introduce a new pattern, library, or architectural style when an existing one already solves the problem.
- Keep layering consistent (e.g. controller → service → repository) — don't skip layers or mix responsibilities.

### 3. DRY / Reuse
- Before writing new logic, search the codebase for an existing function, utility, service, or module that already does it (or close to it).
- If logic is used — or will plausibly be used — by more than one module/service/entity, extract it into a shared/common location (e.g. `common/`, `shared/`, `libs/`) instead of duplicating it.
- Don't copy-paste code with small tweaks — parameterize and reuse instead.
- Consolidate duplicate types/interfaces/DTOs rather than redefining the same shape in multiple places.
- Reuse existing constants/enums instead of hardcoding values.

### 4. Coding Principles
- Follow SOLID where it genuinely helps (especially single-responsibility) — don't apply patterns dogmatically at the cost of simplicity.
- Keep functions small and focused; one function should do one thing.
- Meaningful naming over comments explaining unclear names.
- Consistent error handling — don't mix silent failures, thrown exceptions, and returned error objects in the same codebase.
- Type everything explicitly (no implicit `any`, no loose typing) where the language supports it.

### 5. Before Writing Code
- Check for existing utilities, hooks, services, or constants before creating new ones.
- Check for existing types/interfaces before redefining.
- If unsure whether something already exists, search/grep first rather than assuming.
- Understand the existing data flow/module boundaries before adding new code.

### 6. Scope Discipline
- Don't refactor unrelated code while implementing a feature/fix — flag it separately instead.
- Don't add extra features, validations, or configs that weren't asked for "just in case."
- Keep changes minimal and scoped to the task.
- Don't rename/move existing files or symbols unless required for the task.

### 7. Testing & Validation
- Don't skip validation on inputs that cross a trust boundary (API inputs, webhook payloads, etc.).
- Add/update tests for new logic where the codebase already has a testing pattern in place.
- Don't leave dead code, commented-out blocks, or unused imports behind.

### 8. Communication
- If a requirement is ambiguous, ask rather than assume and build the wrong thing.
- If an existing pattern is clearly bad/outdated, flag it and suggest an alternative — don't silently deviate from it.

### 9. Do not commit and push
- Once development is done after test do not commit any code


### 10 Production Environment Awareness

IMPORTANT: Always consider the production VPS configuration when developing, modifying, or optimizing this project.

Before starting any development task, read the `secret_note` file in the project root.

The file contains the actual production server specifications.

### 1 Production VPS Specifications
Use `.secret_note` as the single source of truth.

## Important
These rules apply to ALL tasks, including:
- Frontend development
- Backend development
- Database design
- API development
- Authentication
- Package and dependency selection
- Docker and deployment configuration
- Performance optimization
- Testing and debugging
- Infrastructure architecture

Always keep the production environment in mind, even when working locally.