# contracts

Single source of truth for all message and API schemas (Protobuf). See `docs/knowledge/0003`.

- `proto/events/` — Kafka event schemas (`md.candle`, `signal.generated`, `order.updated`, ...)
- `proto/api/` — gRPC/REST API schemas between services (e.g. core -> risk order approval)

Code is generated from here for Java and Python, plus a TypeScript client for `ui` only. Never hand-write these shapes inside a service.
