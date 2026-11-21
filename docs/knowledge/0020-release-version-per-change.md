---
number: 0020
title: Every release doc gets its own version
type: decision
created: 2026-11-21
status: current
---

# Every release doc gets its own version

## Decision
Each change in `docs/releases/` gets its own semantic version, computed from the previous one and the change's bump level:
- patch: `1.0.2` -> `1.0.3`
- minor: `1.0.2` -> `1.1.0`
- major: `1.0.2` -> `2.0.0`

Files are flat in `docs/releases/` and named `<version>-<slug>.md` (for example `1.0.2-numbered-knowledge-topic-folders.md`). `docs/releases/VERSION` holds only `current: <latest version>`.

## Why
The earlier rule kept every change inside one open version folder (`1.0.0/0001-...`) until a release was cut, so the version shown in a doc did not move when a change was pushed. Pranshu wants the version to move with every pushed change and be visible in the file name.

## What changed from the old rule
- No `open` / `last_released` state and no "cut a release" step; a release doc is written only when the work is being pushed.
- The `status: unreleased | released` frontmatter field was removed.
- Existing docs were renumbered: `1.0.0` repo folder structure (base version), `1.0.1` superpowers workflow rule, `1.0.2` numbered knowledge topic folders.
