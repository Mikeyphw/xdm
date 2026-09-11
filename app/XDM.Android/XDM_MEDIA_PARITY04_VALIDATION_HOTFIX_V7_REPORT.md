# XDM Media Parity04 Validation Hotfix v7 Report

## Context

Hotfix v6 was applied with validation enabled after Parity04 had already been applied without validation. The v6 validated apply progressed through the prior Kotlin compile blockers and app unit-test assertion blockers, then failed during `:app:lintDebug`.

Devtool rollback succeeded, so the repository should still be at the committed post-Parity04 preimage with no hotfix v1-v6 changes retained.

## Failure reviewed

Validated apply log:

- `20260911-182426-427220-xdm_media_parity04_validation_hotfix_v6.log`

Remaining blocker:

- `app/XDM.Android/app/src/main/res/values/strings.xml:56` unused `R.string.media_locator_candidates_expanded`
- `app/XDM.Android/app/src/main/res/values/strings.xml:57` unused `R.string.media_locator_candidates_collapsed`
- `app/XDM.Android/app/src/main/res/values/strings.xml:85` unused `R.string.media_locator_saved`
- task failed: `:app:lintDebug`

The earlier validation stages had already moved past the previous production/test compile blockers. The Gradle native-integration warning was environmental and not the failure cause.

## Fix

Hotfix v7 is cumulative and supersedes v1-v6. It carries all prior repairs and adds production usages for the three Parity04 strings instead of deleting them, because the strings are part of the browser-UX/static-contract evidence:

- `media_locator_candidates_expanded` is used as the chooser dialog title decorator when the detected-media chooser is open.
- `media_locator_candidates_collapsed` is used for the browser header while candidates are collapsed behind the floating Media button.
- `media_locator_saved` is used for the visible toast/status confirmation after a reviewed media candidate is persisted.

This preserves the roadmap semantics while satisfying Android Lint's resource reachability analysis.

## Scope carried forward from v1-v6

- Developer Center multiline-string Kotlin syntax repair.
- Room schema 24 test truth rebase.
- Native supported HLS expectation rebase.
- Browser-extension privileged-evidence predicate assertion rebase.
- `LogicalMediaGraph.kt` nullable URI compile repair.
- Test `Files.readString` portability repair.
- App source-contract root-resolution/current-authority rebases.
- DebugCenterV9 invalid `String.contains(Pair)` compile repair.
- Developer Center direct-v3/app-private request-context evidence copy.
- Completed-download notification Share action.

## Local seal performed in this environment

- Reviewed v6 validated-apply log and confirmed rollback success.
- Reconstructed the clean post-Parity04 preimage from the handoff repository plus Parity01, Parity02, Parity03, and Parity04 overlays.
- Applied the cumulative hotfix tree and added the v7 resource-usage fix.
- Verified all three lint-reported string resources now have production Kotlin references in `MediaLocatorActivity.kt`.
- Rebuilt `.devtool-artifact.json` inventory with source SHA-256 for the exact post-Parity04 preimage and final SHA-256 for the v7 payload.

Full Gradle/lint/device validation must run in the Termux Devtool environment.
