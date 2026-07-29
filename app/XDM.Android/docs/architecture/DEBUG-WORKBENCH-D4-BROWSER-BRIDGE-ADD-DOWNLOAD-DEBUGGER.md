# Debug Workbench D4: Browser Bridge + Add Download Debugger

D4 adds two Debug Workbench panels under Settings > Debug Workbench:

- Browser bridge debugger
- Add Download debugger

## Browser bridge debugger

The browser bridge debugger explains the current scheme registration, export-folder health, detector version, contract version, compatibility status, and recent redacted handoff results. It provides a copy-only debugger report with example capture and Add Download test URIs.

The panel never opens a custom scheme itself. It does not run probes, enqueue downloads, upload reports, or expose raw cookies, Authorization headers, or signed query values.

## Add Download debugger

The Add Download debugger explains the currently active external Add Download draft, if one exists. It mirrors `DownloadReviewPlanner` to show origin, kind, review gate, inspection choice, destination state, page-context availability, and file-name handling.

The debugger is review-only. It cannot create a transfer, write to the queue, or bypass the normal two-step Add Download confirmation.

## Privacy and UI contract

D4 uses human labels for origins and kinds. Normal UI does not render enum names, raw URLs, raw headers, cookies, authorization values, command payloads, or internal machine fields. Copy reports pass through the existing browser bridge redactor and `DebugRedactor.redactUrl` for draft URLs.

## Next phase

D5 should add transfer-runtime and notification-open-file debugger panels.
