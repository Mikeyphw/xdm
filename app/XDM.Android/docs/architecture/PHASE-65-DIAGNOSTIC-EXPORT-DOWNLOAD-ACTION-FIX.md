# Phase 65: Diagnostic Export / Download Action Fix

Phase65 is a final-adjacent field repair over the Phase64 RC seal. It addresses two real-device issues:

1. Support diagnostics were copy-only. The app now offers explicit Android share-sheet export for the redacted support report and runtime self-test suite.
2. Download list actions could appear available but fail to remove or cancel a record from the three-dot menu. The list action planner now exposes Cancel and Delete record / Remove from list consistently, and the ViewModel performs a safe explicit removal path.

## Diagnostic export

Runtime self-test exports include the check IDs that actually ran. The exported text includes a `Ran check IDs:` line and per-check entries such as `[media-sniffer]`, `[redaction]`, and `[support-report]`. Export uses Android's share sheet and remains user-initiated. No automatic upload is added.

## Download action contract

- Active and queued downloads expose Cancel and Remove from list in the list action menu.
- Remove from list is confirmation-gated and cancels the transfer first when the item is not completed.
- Record removal cleans owned backend task, recovery, and finalization records before deleting the download row.
- Record removal does not delete completed files. Delete file + record remains the only action that attempts saved-file deletion.

## Boundaries

No Room migration, top-level route, all-files permission, automatic transfer start, automatic deletion, automatic upload, persisted session values, or Debug Workbench reopening is introduced.
