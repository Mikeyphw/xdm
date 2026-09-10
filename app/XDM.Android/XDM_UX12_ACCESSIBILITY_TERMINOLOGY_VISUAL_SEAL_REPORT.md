# XDM Android UX12 Accessibility, Terminology & Visual-System Seal

Date: 2026-09-09

## Scope

UX12 is the final implementation-only polish pass before UX13 performs the full end-to-end validation and release seal. This overlay intentionally defers Gradle, lint, instrumentation, and the retained full release-gate matrix; it is designed to be applied with `--no-validate`.

## Accessibility and large text

The shared design system now adapts copy density to font scale rather than keeping small fixed line limits. At accessibility font sizes, titles, supporting text, metadata, status badges, list rows, and technical text may expand instead of being prematurely ellipsized.

Page-header actions stack when horizontal space or font scale makes an inline layout unsafe. Metric strips collapse from four columns to two and then one as font scale increases. Bottom-navigation labels allow two lines at large font sizes, and at the largest scale the selected destination remains labeled while unselected destinations retain accessible semantics. The expanded sidebar widens for large text.

Disabled navigation content has stronger contrast while remaining visually disabled.

## Non-color semantics

Status badges now expose semantic state descriptions such as `Warning: Waiting` or `Positive status: Completed`. Notice rows likewise expose their severity through semantics. Status meaning therefore does not depend on color alone.

## Typography and hierarchy

Section labels use sentence case instead of forced uppercase. Empty-state title and body alignment is centered consistently. Raw technical information is rendered through a selectable monospace `XdmTechnicalText` primitive rather than ordinary product copy.

## Terminology

Current user-facing status vocabulary is normalized around:

- Ready
- Running
- Waiting
- Paused
- Needs action
- Failed
- Completed
- Disabled

Activity now uses `Needs action`, `Queue holds`, `Unresolved`, and `timeline` language rather than `Needs attention`, `Queue decisions`, or `flight recorder`. Media status copy uses `Captured`, `Ready`, `Refresh needed`, and `Unavailable`. Completed downloads consistently use `Completed`.

Diagnostics/support copy no longer presents the legacy `Debug Workbench` name to current users. Queue summary messages no longer describe downloads as `explainably held`.

## Error hierarchy

Download details now present failures in four layers:

1. **What happened** — concise user-facing cause.
2. **What XDM will do** — automatic recovery/preservation behavior.
3. **What you can do** — the safest next action.
4. **Technical details** — expandable raw diagnostic text.

Debug Center and Browser Integration technical details use selectable monospace text, while existing explicit copy actions are preserved.

## Compatibility contracts prepared for UX13

Retained contracts that inspect current UI source have been updated for the current vocabulary and large-text adaptive sidebar. Historical phase metadata and historical documentation remain intact instead of being rewritten to pretend older releases used the new terminology.

UX13 remains responsible for actually executing the complete Gradle, lint, unit-test, retained static-gate, and UI/UX release-seal matrix.

## Persistence

No Room schema change is required. Schema remains 21.
