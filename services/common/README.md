# common (vajra-common, Java)

Startup plumbing shared by brahma, maryada and dvara. **No business logic here.**

- `RequiredEnvironment` — fails startup naming any missing `DB_URL` / `DB_USER` / `DB_PASSWORD` (auto-configured before any bean).

Why it exists: `docs/knowledge/0016-database-roles-and-migrations.md`, `docs/knowledge/0018-phase1-build-decisions-and-measurements.md`.
