# infra

Local and deployment infrastructure.

- `compose/` — docker-compose for PostgreSQL 18, Kafka, Redis and the services. Pin images by exact tag or digest.
- `postgres/` — cluster bootstrap: one schema + one DB role per service (`core`, `risk`, `research`, `signal`, `gateway`). Tables are created by each service's own migrations, not here.

Production target is a small VPS (4 GB RAM, 2 vCPU), so keep memory limits explicit on every container.
Secrets come from env vars / a secret manager, never from files in this repo.
