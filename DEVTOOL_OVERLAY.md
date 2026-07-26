# XDM Android UIX R6 Overlay

UIX R6 is the final accessibility, responsiveness, performance, and release-validation seal for the Android redesign. It depends on the UIX R5 Activity, Settings, and Developer boundary overlay.

## Included

- Stable semantics tags for every primary screen and adaptive shell region.
- 48 dp minimum interaction targets and TalkBack state descriptions.
- Large-font-safe shared headers and metric layouts qualified at 200% font scale.
- Compact, Medium, Expanded, Add-modal, list-detail, list/grid, empty, and error layout contracts.
- Saveable navigation, filter, modal, quality, and organization state where transient UI must survive rotation.
- Lazy Developer planner ownership behind the persisted gate and active panel.
- Consumer-source scans blocking debug architecture phrases, raw machine values, unredacted URLs, cookies, authorization, and command templates.
- Updated product/topography contracts, project manifest, CI, final release gate, device smoke helper, and full Devtool build matrix.

## Validation policy

R6 requires the complete target-environment gate: debug and beta lint, module tests, app unit tests, Android-test assembly, debug and beta packaging, aria2 payload verification in CI, and zero warnings/errors. Manual qualification covers clean install, upgrade from R5, external browser/share handoff, TalkBack, 200% font scale, portrait/landscape, foldables/tablets, process restoration, failure states, recovery, and Developer-options shutdown.

## Apply

Use Devtool with `--validate`. The artifact owns its commit message; do not add a CLI `--commit` flag.
