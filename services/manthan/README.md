# research-service (manthan)

**Stack:** FastAPI (Python 3.14), Alembic
**Owns DB schema:** `research` (own role, own migrations — never touch other schemas)

**Does:** feature store, backtesting, training, model registry, promotion gates, drift reports, AI research layer.

**Does not:** talk to exchanges or place orders.

Details: `docs/knowledge/0004-service-responsibilities.md`.
