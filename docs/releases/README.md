# Release notes

One file per change, grouped in one folder per version. Version numbers are assigned automatically by Claude Code (see `CLAUDE.md`), never typed by hand.

## Layout
```
docs/releases/
  README.md          <- these rules
  VERSION            <- state: last released version + currently open version
  _template.md       <- copy for each new entry
  1.0.0/
    0001-<slug>.md
    0002-<slug>.md
  1.0.1/ ...
```

## State file `VERSION`
```
last_released: none
open: 1.0.0
```
- `last_released`: latest version that was actually cut/released (`none` before the first release).
- `open`: the version currently collecting changes (`none` if everything is released).

## How a version number is chosen
1. **Before the first release:** `open` is `1.0.0`. Every change becomes a new file inside `1.0.0/`, whatever bump level it is (the bump label is still recorded in the file). New files keep being added until `1.0.0` is cut.
2. **After a release is cut:** `last_released` = that version, `open` = `none`.
3. **Next change when `open` is `none`:** compute from `last_released` and the chosen bump:
   - major: `X+1.0.0`, minor: `X.Y+1.0`, patch: `X.Y.Z+1`
   - create the folder, set `open` to it.
4. **Next change when a version is already open:** add a new file to the open folder. If the new bump is higher than the open version's bump level (for example the open version is a patch and the new change is minor), recompute the version from `last_released`, rename the folder, and update `version:` in every file inside it.
5. **Cutting a release** (only when the user says so): set `status: released` in every file of the folder, set `last_released` to the version, set `open: none`.

## Rules
- One change = one new file. Never merge files, never edit or delete files of a released version.
- Files are numbered `0001`, `0002`, ... inside their version folder, with a short slug.
- Each file has the frontmatter and sections from `_template.md`.
- A change that touches a DB schema or a Protobuf contract must say so under "DB migrations" / "Contract changes".
