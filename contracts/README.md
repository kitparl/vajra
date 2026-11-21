# contracts

Single source of truth for all message and API schemas (Protobuf). Details: `docs/knowledge/0017-contracts-pipeline.md`.

- `proto/vajra/<domain>/v1/` — proto files; folder must match the package (`vajra.common.v1`).
- `java/` — Maven module `contracts-java`; generates Java classes during `mvn verify` (from `services/`).
- `python/` — package `vajra-contracts`; generates Python modules when uv installs it (`uv sync` in `services/`).

Generated code is never committed and never edited by hand. Never hand-write these shapes inside a service.

```
cd contracts && buf lint          # style check (also in CI)
```
CI also runs `buf breaking` against `main` on pull requests.
