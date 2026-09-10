# XDM Android UX02 + UX03 Navigation and Downloads Product UI

Date: 2026-09-09
Overlay: `xdm_android_ux02_ux03_navigation_downloads_product_ui_v1.zip`
Depends on: `xdm_android_ux01_ux09_product_foundation_v3r2.zip` / commit `7d2b8bce`

## Scope

This overlay implements the next two product-roadmap items as one atomic change: UX02 navigation/visual hierarchy and UX03 Downloads/queue product UI. It does not begin UX04 New Download destination preflight. Room remains schema 21.

## UX02 — navigation and visual hierarchy

- The compact shell is the single owner of primary route titles. Media and Library use compact intro copy rather than duplicating their title below the app bar; their full page headers remain on expanded layouts where the shell intentionally delegates those headers to the page.
- Activity and Settings no longer repeat their route title inside content.
- The compact top-bar New download `+` is contextual to Downloads. Activity, Media, Library, and Settings no longer expose an ambiguous global add icon. The expanded sidebar keeps its explicit labeled New download action.
- Settings secondary pages use a conventional leading back arrow instead of a right-aligned textual Back action.
- Disabled grouped-list rows retain readable contrast while remaining visibly disabled.
- Existing compact/medium bottom navigation and expanded navigation-sidebar behavior remain intact.

## UX03 — Downloads and queue product UI

- Primary filters are now `All`, `Downloading`, `Waiting`, and `Finished`.
- `Waiting` intentionally groups Created, Queued, Paused, WaitingForNetwork, and WaitingForPower states.
- Overview metrics separately expose downloading, waiting, queued, aggregate moving speed, and remaining time when it can be calculated. Paused work therefore no longer disappears from the overview.
- Queue-level holds are grouped into one user-facing issue banner instead of exposing raw `QueueIntelligenceSummary.message` implementation text. Storage, network, power, schedule, retry, and manual-review states receive concise product language and a re-evaluation action.
- Download rows use concise state text; raw queue-policy details no longer dominate the primary card. A legacy `Destination storage unavailable` queue message is surfaced as `Waiting — Storage check failed`.
- Destination metadata is rendered on its own line instead of sharing the action/status text flow.
- Paused and waiting transfers retain their known progress/byte information; a nonzero last speed is labeled as historical rather than current throughput.
- Start/resume actions now use a download/resume glyph rather than the media Play triangle.

## Validation

Focused source validators passed:

- `validate-ux02-navigation-visual-hierarchy.py`
- `validate-ux03-downloads-queue-product-ui.py`
- `validate-uix-r3-downloads-add-workspace.py`
- `validate-bug-hunt-phase8-download-actions-ui-truthfulness.py`

The retained final static release gate passed outside the long Phase11 matrix, and the Phase11 static matrix then passed separately with all **80/80 roadmap rows**.

The target Termux/Android environment must still execute the affected Kotlin/JUnit and lint tasks because Gradle 9.7.1 is not available in the artifact-generation sandbox.

## Explicit non-goals

- No database migration or Room schema bump.
- No new storage permission.
- No automatic transfer start or file deletion behavior.
- No UX04 New Download/destination-picker/preflight redesign in this overlay.
