# XDM Android UX08 + UX09 — Settings information architecture and advanced-settings reorganization

Date: 2026-09-09

## Scope

This intermediate roadmap overlay implements UX08 and UX09 on top of the successfully applied UX06/UX07 state. It is intentionally prepared for `devtool ... apply overlay ... --no-validate`; full Gradle/lint/release validation remains deferred to the final overlay in the series.

## UX08 — Settings information architecture

The Settings overview no longer exposes one overloaded “Advanced download rules” bucket. Normal settings are grouped by user intent and rendered as grouped navigation rows instead of a wall of equally weighted cards.

New settings destinations:

- Storage & destinations
- Download behavior
- Network
- Media & capture
- Browser integration
- External tools
- Post-processing
- Appearance
- Backup & restore
- Privacy
- Diagnostics & support
- Developer Center (Developer mode gated)

The existing `SettingsPanel.AdvancedDownloads` enum value is intentionally retained for persisted-navigation compatibility, but its label and destination now mean `Download behavior` only.

Storage permission, direct paths, Android-managed locations, SAF folders, and Storage Doctor now live together under Storage & destinations. Appearance no longer consumes the main Settings overview. Notifications remain a direct Android system action.

## UX09 — Advanced-settings reorganization

The former advanced screen is split into focused surfaces:

### Download behavior

- default file-name conflict strategy
- Smart queue management entry
- destination rules
- duplicate URL rules

Destination rules no longer ask normal users to type a canonical URI. They use the XDM destination catalog and saved writable folders, show the friendly destination label, and expose the technical URI only while Developer mode is enabled.

### Network

- proxy enablement
- host/port
- username
- credential alias

### Media & capture

- Media inbox
- Live Locator
- Browser integration
- Post-processing entry
- privacy explanation

### External tools

- Termux bridge and probes
- optional privileged/root actions through the existing authorizer
- Termux aria2 cockpit and secret rotation

### Post-processing

The UI now presents the safe execution order explicitly:

1. final publication/finalization
2. durable post-processing automation admission
3. optional user-selected transformation

Safety-critical finalization remains fixed rather than pretending it can be arbitrarily reordered. The existing durable automation and transformation settings remain authoritative.

### Backup & restore

Portable settings import/export now has one dedicated home instead of being appended beneath unrelated engine/runtime settings.

## Compatibility and persistence

- no Room schema change; schema remains 21
- no new broad storage permission
- persisted `SettingsPanel.AdvancedDownloads` remains valid and opens Download behavior
- no settings are deleted; capabilities are moved to clearer homes
- no automatic transfer start, deletion, upload, or external command behavior is added

## Deferred validation

This intermediate artifact adds UX08/UX09 contract tests and updates the retained R5 Settings contract for the new information architecture, but does not execute Gradle/lint validation. The final roadmap overlay must run the complete validation set and reconcile historical overlay allowlists.
