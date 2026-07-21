# Phase 11: Post-processing Automation

Phase 11 adds a mobile-safe post-processing automation layer for XDM Android.

## Product rules

- Automation is enabled by default but only uses typed XDM actions.
- Rules can be previewed before they are executed.
- Termux-backed actions are generated from templates, never from a raw shell field.
- Root-backed actions remain optional and flow through the Phase 10 typed root guard.
- No chroot support is added.
- No new top-level route is added.
- The event log is copyable for diagnostics and support.

## Initial triggers

- Download completed
- Download failed
- Media captured
- Media download created

## Initial conditions

- File extension
- MIME type
- Source host
- Backend
- Minimum size

## Initial actions

- Move to folder
- Rename by pattern
- Verify SHA-256
- FFprobe inspect
- Fast-start MP4 remux
- Extract audio
- Clean partial markers
- Fix permissions with optional root mode

## Guardrails

Phase 11 does not expose arbitrary command entry. Actions are sealed Kotlin types and every Termux shell script is produced from `TermuxShellTemplates`. Root actions require the Phase 10 root mode and probe guardrails.
