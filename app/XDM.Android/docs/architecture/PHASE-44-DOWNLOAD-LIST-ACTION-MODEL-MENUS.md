# Phase 44 — Download List Action Model and Menus

Phase 44 gives download rows the same kind of state-aware action vocabulary expected from a full download manager while keeping the implementation safe and testable. The row still opens details on tap, but the trailing primary action and the full action sheet now come from a pure model planner instead of hardcoded Compose branches.

## Scope

This phase adds:

- `DownloadActionKind` for the supported action vocabulary.
- `DownloadActionIcon` so Compose can map model actions to the local icon set without putting Material icons in `core-model`.
- `DownloadAction` with `enabled`, `primary`, `destructive`, and `requiresConfirmation` metadata.
- `DownloadActionPlanner.actionsFor(download)` for row/action-sheet menus.
- `DownloadActionPlanner.primaryActionFor(download)` for the trailing row button.
- `DownloadActionPlanner.batchActionsFor(downloads)` as the shared selection-mode contract.
- A `More actions` row button and adaptive action sheet in the Downloads list.

## Row behavior contract

- Tap row: open details or toggle selection when selection mode is active.
- Long-press row: toggle selection.
- Primary trailing button: planner-selected primary action.
- Kebab button: planner-provided full action sheet.
- Action sheet: shows primary, copy/share, reorder, recovery, and destructive choices with explicit metadata.

## State matrix

| State | Primary action | Menu coverage |
|---|---|---|
| Downloading / Connecting / Finalizing / Verifying / Repairing | Pause | Details, Copy link, Share link, Cancel |
| Created / Queued | Start now | Move top/up/down/bottom, Details, Copy link, Cancel |
| Paused / Waiting for network / Waiting for power | Resume | Details, Refresh link, Redownload, Copy link, Delete record |
| Failed | Retry | Details, Refresh link, Copy link, Redownload, Delete record |
| Completed | Open file | Open details, Open location, Share file, Copy link, Copy file name, Copy path, Rename, Redownload, Delete record, Delete file + record |
| Recovery required | Review recovery | Details, Locate file, Restart, Remove record |
| Cancelled | Details | Copy link, Redownload, Delete record |

## Safety boundaries

Phase 44 does not implement raw file opening, file deletion, queue reordering, rename, or refreshed-link acquisition directly. Those actions are surfaced as planner-backed menu entries and route to details until their dedicated execution flows are implemented. Destructive actions are marked with `destructive = true` and `requiresConfirmation = true`, so later execution phases cannot accidentally wire them as silent one-tap operations.

## Validation

Phase 44 is guarded by:

- `DownloadActionPlannerTest` for pure state/action behavior.
- `DownloadListPhase44ActionMenusContractTest` for the Compose surface contract.
- `tools/validate-phase-44-download-list-actions.py` for static source and manifest checks.

The full historical app unit-test matrix may still include older unrelated contract failures. The focused validation set for Phase 44 is `:core-model:test` plus the Phase 44 app contract test.
