# Bug Hunt Remediation Phase 9 r2: Accessibility and Adaptive Layout Gap Closure

Phase 9 r2 closes the audit gaps left by the first accessibility overlay.

## Production fixes

- Fold posture is now sourced from Jetpack WindowManager through `WindowInfoTracker` and mapped into `XdmWindowProfile`.
- Download two-pane eligibility is evaluated against the measured available pane width, not just the raw window class.
- Sheets and expanded dialogs request focus when opened and restore focus to the opener when dismissed.
- Shell, list, details, sheet/dialog, and player surfaces have explicit traversal ordering for keyboard, D-pad, and switch-access navigation.
- Status badges and notice rows pass through `XdmContrastPolicy` so weak foreground colors fall back to readable content colors.
- Phase 9 now names the required risky large-font surfaces and screenshot/semantics matrix in production contracts.

## Regression coverage

- Compose instrumentation covers phone, split-screen, 840dp threshold, tablet, compact-height landscape, large-font, and separating-hinge profiles.
- Compose instrumentation captures a screenshot image for each matrix profile and verifies shell semantics are present.
- Compose instrumentation verifies focus restoration for adaptive sheets.
- Large-font instrumentation covers notice, badge, progress, sheet, post-processing, and media-variant contract surfaces.
- JVM policy tests cover measured pane widths, separating hinges, traversal ordering, and contrast gates.
