# Phase61 — Final Gate Validator Harmony

Phase61 is a targeted final-gate repair. It updates an older UIX R3 static validator so it checks the current Phase44 planner-backed download row instead of requiring the retired row-local `primaryRowAction` implementation.

## Why this exists

Phase44 intentionally moved download-row action selection into `DownloadActionPlanner`. Its validator still forbids reviving `private fun Download.primaryRowAction` and `private data class DownloadRowAction` in `DownloadRow.kt`.

The older UIX R3 validator was stale and still required the plain `primaryRowAction` string. That made the full static final gate fail even though the row was using the newer shared planner correctly.

## Contract

- UIX R3 requires `DownloadActionPlanner.primaryActionFor(download)` and `DownloadAction.iconVector()`.
- Phase44 continues to reject row-local action planning.
- Final release gates run the Phase61 validator.
- Phase60 and earlier post-field-fix validators tolerate Phase61 as a later current overlay.

## Boundaries

- No Room schema change; schema remains 14.
- No top-level route.
- No all-files permission.
- No automatic transfer start.
- No automatic deletion or orphan adoption.
- No automatic upload.
- No release criteria change.
- No Debug Workbench reopening.
