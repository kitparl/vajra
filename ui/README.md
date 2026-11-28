# ui

Angular 22 trading terminal (Node/TypeScript are build tooling only). Talks **only** to `gateway-bff`.
See `docs/knowledge/0008-ui-design-direction.md`.

## Build and run

Pinned: Angular CLI 22.2.8 (`@angular/*` framework packages 22.2.7, the latest 22.2 patch of those), Node 26.13.1. Standalone, zoneless, strict, no SSR; tests run on Vitest.
Node is not needed on the host; run everything in the node image:

```sh
docker run --rm -v "$PWD/ui":/w -w /w node:26.13.1-alpine sh -c 'npm ci && npm test -- --watch=false && npm run build'
```

Production image (`ui/Dockerfile`): build in node, serve with `nginx:1.29.8-alpine` as a non-root user on 8080.
Nginx serves the SPA, proxies `/api/` and `/ws` to `gateway-bff:8080`, and sets the security headers
(`security-headers.conf`). It is the only service the stack publishes (`127.0.0.1:8088`); Tailscale serve fronts it.
`scripts/check-compose-ports.sh` guards that.
