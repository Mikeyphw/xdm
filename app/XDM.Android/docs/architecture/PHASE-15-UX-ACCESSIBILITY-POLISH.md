# Phase 15: UX and accessibility polish

Phase 15 is a schema-free UI hardening pass. It preserves the Phase 13/14 route topology and focuses on the app surfaces that changed most during automation, browser handoff, diagnostics, and release-safety work.

## Scope

- Keep the top-level routes unchanged: Downloads, Add, Queues, Scheduler, Media, Recovery, Diagnostics, and Settings.
- Add a compact Downloads overview so small screens show total, active, completed, and attention-needed work before the list.
- Preserve long filenames by truncating text before controls instead of pushing pause/resume/retry actions off screen.
- Give pause/resume, copy-summary, and migration actions stable minimum touch targets.
- Add semantic state descriptions for navigation, filters, switches, and progress indicators.
- Keep Diagnostics privacy-safe while making the copy action explicit for screen readers.

## Non-goals

- No Room schema bump. Database remains v14.
- No new top-level route. Accessibility and layout health are shown inside Settings.
- No placeholder controls. Every visible action invokes an existing ViewModel or clipboard operation.

## Acceptance checks

- `PROJECT_MANIFEST.json` includes implemented phase 15 and `ux_accessibility_polish.schema_version_unchanged = 13`.
- `app/build.gradle.kts` reports `0.15.0-alpha01`.
- UI source uses Compose semantics for action/state descriptions.
- The Phase 15 validator and architecture contract tests verify the route topology is unchanged.
