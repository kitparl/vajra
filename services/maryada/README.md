# risk-service (maryada)

**Stack:** Spring Boot (Java 25 LTS), Flyway
**Owns DB schema:** `risk` (own role, own migrations — never touch other schemas)

**Does:** approve/reject every order against hard limits; owns the kill-switch. Deterministic and fail-closed.

**Does not:** use ML/LLM in its decision path; let models change limits.

Details: `docs/knowledge/0004-service-responsibilities.md`.
