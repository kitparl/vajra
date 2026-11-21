# research-service (folder: manthan)

**Stack:** FastAPI (Python 3.14), Alembic
**Owns DB schema:** `research` (own role, own migrations — never touch other schemas)

**Does:** feature store, backtesting, training, model registry, promotion gates, drift reports, AI research layer.

**Does not:** talk to exchanges or place orders.

Details: `docs/knowledge/0004-service-responsibilities.md`.

## Run and test
```
cd services && uv run pytest manthan/tests        # Testcontainers tests (needs Docker)
scripts/compose.sh up -d --build research-service          # from repo root   (laptop only: add --profile research)
curl localhost:8092/health
```
Config (env): `DB_URL` (`postgresql+psycopg://host:port/vajra`), `DB_USER`, `DB_PASSWORD`. Migrations: `alembic/versions` (schema `research`); they run as a separate step before the server (`alembic -c alembic.ini upgrade head`), never inside the app (0016).
