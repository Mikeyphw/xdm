# Phase 7: Termux Command Runner and Optional Root Foundation

This phase adds an Android-to-Termux bridge for desktop-parity tooling without adding a raw shell surface to XDM.

## Scope

- Declare `com.termux.permission.RUN_COMMAND`.
- Make `com.termux` and `com.termux.api` visible through package queries.
- Launch Termux through `com.termux.app.RunCommandService`.
- Receive stdout, stderr, exit code, and Termux internal errors through `TermuxResultService`.
- Normalize Termux work directories with `TermuxPaths`.
- Expose a Diagnostics card and Settings card for Termux bridge status.
- Add an optional root-mode preference with root disabled by default.

## Safety rules

XDM must not expose arbitrary shell execution. Every Termux command is created from a typed command template:

- `ProbeAllTools`
- `ProbeTool`
- `Aria2Download`
- `YtDlpMetadata`
- `FfprobeInspect`
- `FfmpegConvert`

Root mode is a foundation for future privileged operations only. Root actions must stay typed and logged, and destructive actions must require explicit user confirmation. Chroot support is intentionally out of scope.

## User surface

Diagnostics shows current bridge readiness, last result, tool probe rows, and a copyable diagnostic summary. Settings exposes Termux status, tool probing, an Open Termux action, and root mode selection.

## Acceptance criteria

- XDM works without Termux installed.
- Termux detection is explicit and user-facing.
- The bridge uses `RUN_COMMAND`, `PendingIntent`, and `TermuxResultService`.
- No raw shell command text box is added.
- Root mode defaults to off and exposes only policy state in this phase.
- No new top-level route is added.
