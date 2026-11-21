# core-service (brahma)

**Stack:** Spring Boot (Java 25 LTS), Flyway
**Owns DB schema:** `core` (own role, own migrations — never touch other schemas)

**Does:** exchange connectors (Delta first), market data, instruments, OMS (state machine, idempotency, reconciliation), positions, paper broker, audit log.

**Does not:** decide trades, change risk limits, run ML.

Details: `docs/knowledge/0004-service-responsibilities.md`.
