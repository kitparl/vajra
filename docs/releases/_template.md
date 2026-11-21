---
version: X.Y.Z          # must match the file name <version>-<slug>.md
bump: major            # major | minor | patch
date: YYYY-MM-DD
services: [core-service]   # core-service, risk-service, research-service, signal-service, gateway-bff, ui, contracts, infra
---

# <short title of the change>

## Summary
One or two lines: what changed and why.

## Changes
- 

## DB migrations
None | list schema/table changes (service + migration file).

## Contract changes
None | list Protobuf / API / event changes.

## Breaking changes
None | what breaks and how to migrate.

## Verification
How this was tested (tests run, testnet/paper checks).

## Rollback notes
How to undo this safely.
