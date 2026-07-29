# Debug Workbench D5, Transfer + notification debugger

D5 adds a read-only Transfer + notification debugger to `Settings → Debug Workbench`.

The panel explains the current transfer lifecycle, backend choice, notification path, completed-notification open-file path, and failure label using existing app state. It is diagnostic glass only: it does not pause, resume, cancel, retry, enqueue, launch viewers, probe files, open custom schemes, or upload reports.

## What it shows

- Active transfer count and selected transfer summary.
- Progress derived from `ActiveTransferSummary` and the current `Download` record.
- Backend and backend-selection reason with human labels.
- Notification path for active, paused, completed, failed, recovery, and waiting states.
- Completed notification open-file behavior through the non-exported trampoline and XDM details fallback.
- A small state-derived lifecycle timeline.
- A sanitized copy report with redacted source URLs and fingerprinted destination URI.

## Privacy and safety boundaries

Normal UI never renders raw URLs, raw enum names, raw JSON, headers, cookies, or authorization data. The copy report uses `DebugRedactor.redactUrl`, `DebugRedactor.redactText`, and destination URI fingerprints.

D5 does not add a top-level route, Room migration, background worker, foreground service, new notification action, or automatic upload path.

## Next

D6 can add a runtime self-test suite that checks manifest routes, FileProvider authority, custom scheme readiness, redaction smoke, notification intent wiring, and media sniffer smoke tests without running Gradle from the app.
