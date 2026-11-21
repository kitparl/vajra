# Release notes

One file per change, and **every change gets its own version number**. Version numbers are assigned automatically by Claude Code (see `CLAUDE.md`), never typed by hand.

## Layout
```
docs/releases/
  README.md                          <- these rules
  VERSION                            <- current (latest) version
  _template.md                       <- copy for each new entry
  1.0.0-repo-folder-structure.md
  1.0.1-superpowers-workflow-rule.md
  1.1.0-<slug>.md
  ...
```
File name = `<version>-<short-slug>.md`. The `version:` in the frontmatter must match the file name.

## State file `VERSION`
```
current: 1.0.2
```
- `current`: the version of the latest release doc.

## How a version number is chosen
The bump comes from what the task is called:

| Task | Bump | Example |
|---|---|---|
| feature | major: `X+1.0.0` | `1.4.2` -> `2.0.0` |
| enhancement | minor: `X.Y+1.0` | `1.0.2` -> `1.1.0` |
| bug | patch: `X.Y.Z+1` | `1.0.2` -> `1.0.3` |

Read `current` from `VERSION` and apply the bump.

Then create `<new version>-<slug>.md` and set `current` in `VERSION` to the new version. The very first change of the project is `1.0.0`.

## Rules
- One change = one new file = one new version. Never merge files, never edit or delete an existing release doc after it was pushed.
- Each file has the frontmatter and sections from `_template.md`.
- A change that touches a DB schema or a Protobuf contract must say so under "DB migrations" / "Contract changes".
