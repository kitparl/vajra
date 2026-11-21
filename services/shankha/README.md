# signal-service (folder: shankha)

**Stack:** FastAPI (Python 3.14), Alembic
**Owns DB schema:** `signal` (own role, own migrations — never touch other schemas)

**Does:** run strategies and promoted model versions, publish `signal.generated` (incl. shadow signals).

**Does not:** place orders.

Details: `docs/knowledge/0004-service-responsibilities.md`.

## Run and test
```
cd services && uv run pytest shankha/tests        # Testcontainers tests (needs Docker)
scripts/compose.sh up -d --build signal-service          # from repo root
curl localhost:8091/health
```
Config (env): `DB_URL` (`postgresql+psycopg://host:port/vajra`), `DB_USER`, `DB_PASSWORD`. Migrations: `alembic/versions` (schema `signal`); they run as a separate step before the server (`alembic -c alembic.ini upgrade head`), never inside the app (0016).
