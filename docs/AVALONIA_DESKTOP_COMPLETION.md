# Avalonia desktop completion

This phase completes the primary desktop integration layer for the modern XDM application.

- The main window closes to the Avalonia tray icon and remains active for downloads, queues, the scheduler, and browser capture.
- The tray menu can restore the window or explicitly exit the process.
- Completion and failure notifications use the platform notification command when available (`notify-send`, AppleScript, or Windows toast PowerShell).
- The Downloads page uses an explicit virtualizing panel and includes a selected-download detail/event timeline.
- `XDM.NativeHost` implements browser native-messaging framing and forwards authenticated captures to the loopback service.
- The Browser Integration page installs or repairs user-level Firefox and Chromium-family host manifests.
- Publish scripts include the native host beside the main application.
- `eng/remove-legacy-ui.sh` removes obsolete WPF, GTK, WinForms, and MSIX source trees after the commit is reviewed.

The cleanup script is intentionally not executed by overlay extraction, so rollback remains safe.
