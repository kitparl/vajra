---
number: 0021
title: Task type decides the version bump, and every task ends with a commit message
type: decision
created: 2026-11-21
status: current
---

# Task type decides the version bump

## Decision
Pranshu names each task as a feature, enhancement or bug. That name decides the release bump:

| Task | Bump | Example from `1.0.2` |
|---|---|---|
| feature | major | `2.0.0` |
| enhancement | minor | `1.1.0` |
| bug | patch | `1.0.3` |

When a task is finished, Claude Code always suggests a commit message (Conventional Commits). It never runs `git commit` or `git push` itself.

## Note
This differs from strict SemVer, where major means a breaking change. Here major means "a new feature", so the major number will grow quickly. That is intended.

Builds on `0020-release-version-per-change.md`.
