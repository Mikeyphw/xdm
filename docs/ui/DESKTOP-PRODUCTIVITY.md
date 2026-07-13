# Desktop productivity workflows

The desktop client exposes frequently used transfer actions without requiring navigation through settings or detail pages.

## Download workspace

- The download list uses native multiple selection. Standard Ctrl/Command and Shift selection behavior is supplied by the platform list control.
- Right-click actions cover pause, resume or retry, file/folder opening, source URL copying, desktop-notification muting, and history removal.
- Removing history keeps the most recent 20 entries in a bounded in-memory undo stack. Undo restores the original download identifier and metadata without deleting the downloaded file.
- The list density, visible metadata fields, detail-pane width, and muted download identifiers are persisted in `desktop-productivity.json`.
- Advanced search accepts free text and filters such as `status:failed`, `site:github.com`, `size:>1GB`, and `tag:games`.

## Capture and command access

- Clipboard monitoring presents captured links in a review banner unless automatic addition is enabled.
- Dropped text is parsed for HTTP and HTTPS URLs.
- Dropped Metalink, XDM JSON list, text URL list, and Windows internet-shortcut files are routed to the appropriate import workflow.
- `Ctrl+K` opens a command palette for navigation, selected-transfer actions, diagnostics, bandwidth limits, undo, and the mini-window.

## Notifications and tray

- Desktop notifications are mirrored into a bounded 200-entry in-app notification center.
- Completion/failure notifications can be muted per download while remaining visible in the in-app center.
- The tray tooltip reports active transfer count, aggregate progress, and aggregate speed.
- Tray actions provide unlimited, 1 MiB/s, 5 MiB/s, and 10 MiB/s global bandwidth presets.
- The compact window shows up to eight active, queued, paused, or finalizing transfers with pause, resume, and cancel controls.

## Persistence and safety

Preference writes use atomic temporary-file replacement and are best-effort so a preference-path failure cannot interrupt transfer processing. Notification and undo collections are bounded to prevent unbounded process growth.
