---
number: 0017
title: Contracts pipeline (Protobuf, buf, code generation)
type: design
created: 2026-11-21
status: current
---

# Contracts pipeline

Supersedes the `contracts/proto/{events,api}/` split in 0012.

## Layout
`contracts/proto/vajra/<domain>/v1/*.proto`. buf's standard layout: the folder must match the proto package (`vajra.common.v1`), otherwise `buf lint` fails. Events and APIs are told apart by package name when they are added (for example `vajra.events.v1`, `vajra.core.api.v1`).

## First message: `vajra.common.v1.EventEnvelope`
`event_id` (UUID string), `event_type`, `occurred_at` (Timestamp), `producer`, `payload` (`google.protobuf.Any`). Every Kafka event will wrap its body in it, so the audit log (CLAUDE.md rule 6) gets one id and one timestamp format for everything.

## Code generation (never committed)
| Language | How | Import |
|---|---|---|
| Java | `contracts/java` Maven module, `io.github.ascopes:protobuf-maven-plugin` downloads protoc and generates during `mvn verify` | `com.vajra.contracts.common.v1.EventEnvelope` |
| Python | `contracts/python` package `vajra-contracts`, hatch build hook runs `grpcio-tools` when uv installs it | `from vajra.common.v1.event_envelope_pb2 import EventEnvelope` |

- Python top-level package is `vajra` (not `vajra_contracts.vajra`), because protoc-generated cross-imports are written as `from vajra.<pkg> import ...`.
- Runtime versions must stay on the same protobuf line: Java `protobuf-java 4.36.x` ↔ Python `protobuf 7.36.x`.
- The protoc binary fetched by the Maven plugin needs glibc, so Java images build on a Debian/Ubuntu Maven image (runtime image can stay Alpine).

## Checks
- `buf lint` (STANDARD rules) on every push.
- `buf breaking` (FILE rules) against `main` on every pull request: a change that breaks wire compatibility fails CI.
