# infra

Local and deployment infrastructure. Details: `docs/knowledge/0016-database-roles-and-migrations.md`, `docs/knowledge/0018-phase1-build-decisions-and-measurements.md`.

- `compose/compose.yaml` — base stack (Postgres 18, Kafka, Redis, services) with memory limits; publishes no ports; reused on the VPS later.
- `compose/compose.dev.yaml` — laptop override: publishes ports (Postgres 15432, Redis 16379, services 8080-8092).
- `compose/.env.example` — every variable, with placeholders. Image tags live **only** here. Copy to `.env` (git-ignored).
- `postgres/init/01-roles-and-schemas.sh` — one schema + one login role per service; idempotent. Tables come from each service's migrations, not here.
- `postgres/test/role-isolation.sh` — proves no role can touch another schema or `public` (CI).

```
cp infra/compose/.env.example infra/compose/.env   # then change every change-me
scripts/compose.sh up -d --build                   # VPS set (manthan excluded)
scripts/compose.sh --profile research up -d manthan
scripts/check-memory.sh                            # all healthy and under 90% of limits
infra/postgres/test/role-isolation.sh
scripts/compose.sh down                            # add -v to wipe data
```

The VPS is small and shared with LifeOS: every container keeps an explicit `mem_limit`.
Secrets come from env vars / a secret manager, never from files in this repo.
