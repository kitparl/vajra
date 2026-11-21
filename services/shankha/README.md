# signal-service (shankha)

**Stack:** FastAPI (Python 3.14), Alembic
**Owns DB schema:** `signal` (own role, own migrations — never touch other schemas)

**Does:** run strategies and promoted model versions, publish `signal.generated` (incl. shadow signals).

**Does not:** place orders.

Details: `docs/knowledge/0004-service-responsibilities.md`.
