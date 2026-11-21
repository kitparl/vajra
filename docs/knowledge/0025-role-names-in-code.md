---
number: 0025
title: Role names in code, Sanskrit only in folder names
type: decision
created: 2026-11-21
status: current
---

# Role names in code, Sanskrit only in folder names

## Decision
The Sanskrit names are used **only** as folder names under `services/`. Code, config, build files, compose services, hostnames, logs and the app name all use the role name, so a reader can tell what a service does from any stack trace, log line or container name. This supersedes the naming part of 0013. The folder names in 0013 are unchanged.

| Folder | Role name: Maven artifactId, uv name, `spring.application.name`, compose service, hostname | Java package / Python import | Main class / FastAPI title |
|---|---|---|---|
| `services/brahma/` | `core-service` | `com.vajra.coreservice` | `CoreServiceApplication` |
| `services/maryada/` | `risk-service` | `com.vajra.riskservice` | `RiskServiceApplication` |
| `services/dvara/` | `gateway-bff` | `com.vajra.gatewaybff` | `GatewayBffApplication` |
| `services/manthan/` | `research-service` | `research_service` | `ResearchService` |
| `services/shankha/` | `signal-service` | `signal_service` | `SignalService` |

Classes named after a service also use the role name, for example `MaryadaRiskGate` became `RiskServiceGate`.

## Conventions
- **Java:** package names all lowercase with no separators; classes PascalCase; methods camelCase.
- **Python:** package and module names snake_case; classes PascalCase; functions snake_case.
- **Maven, uv, compose, Spring app name and hostnames:** kebab-case.

## Where the folder name is still correct
These all refer to folder paths, not to the service name:
- `<module>` entries in `services/pom.xml`
- uv workspace `members` and `testpaths` in `services/pyproject.toml`
- `source = { editable = "<folder>" }` entries in `uv.lock`
- `COPY services/<folder>` lines in the Dockerfiles
- `build.args.SERVICE` in compose

`python.Dockerfile` takes three arguments: `SERVICE` (the folder), `PACKAGE` (the uv name) and `MODULE` (the import name). `java.Dockerfile` copies `target/*.jar`, so it does not depend on the artifactId.

## Not renamed
- DB schemas and roles (`core`, `core_svc`, …) were already functional names.
- Flyway migration files keep the old names in their comments. Changing a migration file, even a comment, changes its checksum and breaks Flyway validation on existing databases.
- Numbered knowledge docs 0001–0024 and the release docs are history.

## Deploy note (one time)
Because the compose service names changed, the old containers (`vajra-brahma-1`, …) become orphans. Remove them with `scripts/compose.sh up -d --build --remove-orphans` (never `down -v`). This removes containers only; the `pgdata` and `kafkadata` volumes stay. Tested on the laptop dev stack: all services became healthy against the existing data, with no Flyway re-run.
