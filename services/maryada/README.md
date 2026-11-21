# risk-service (maryada)

**Stack:** Spring Boot (Java 25 LTS), Flyway
**Owns DB schema:** `risk` (own role, own migrations — never touch other schemas)

**Does:** approve/reject every order against hard limits; owns the kill-switch. Deterministic and fail-closed.

**Does not:** use ML/LLM in its decision path; let models change limits.

Details: `docs/knowledge/0004-service-responsibilities.md`.

## Run and test
```
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home   # laptop, see 0018
cd services && mvn -B verify -pl maryada -am     # unit + Testcontainers integration tests (needs Docker)
scripts/compose.sh up -d --build maryada          # from repo root
curl localhost:8082/actuator/health
```
Config (env): `DB_URL` (jdbc), `DB_USER`, `DB_PASSWORD` (startup fails naming any missing one), `PORT`. Migrations: `src/main/resources/db/migration` (Flyway, schema `risk`).
