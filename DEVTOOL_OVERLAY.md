# XDM startup recovery coordinator and workbench overlay

## Purpose

Implements the first two parts of the crash-recovery plan on top of commit `b80102c`:

1. A startup recovery coordinator that scans persisted records and durable transfer artifacts.
2. A dedicated recovery workbench shown after an unclean shutdown.

## Included

- Classifies known transfers as ready to resume, requiring validation, requiring repair, missing partial data, changed remote file, or recovered interrupted finalization.
- Discovers orphaned `.xdm.part`, `.xdm.resume.json`, and `.xdm.finalizing` artifacts in configured destination roots.
- Keeps interrupted active transfers paused; the coordinator never starts downloads.
- Adds a bounded remote identity validation action using length, ETag, Last-Modified, and range behavior.
- Adds a dedicated Recovery navigation page with the requested metadata and actions.
- Opens the Recovery page automatically after an unclean shutdown, including when the scan finds no damaged state.
- Adds coordinator regression tests and extends the app layout test for the new page.

## Validation

Devtool should restore, build, and test `app/XDM/XDM.Modern.sln` with zero warnings and zero errors.

- Updates the shell architecture contract to include the dedicated `RecoveryView`.
