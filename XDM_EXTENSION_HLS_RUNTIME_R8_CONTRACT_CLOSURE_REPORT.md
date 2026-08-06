# XDM Android Extension HLS Runtime r8 Contract Closure

## Failure addressed

Devtool stopped at `:persistence:testDebugUnitTest` because `BugHuntPhase6DatabaseIntegrityContractTest.mediaVariantsAreReplacedTransactionally` still asserted that `MainViewModel` called the retired two-step `repository.replaceMediaVariants` API. The r6 runtime intentionally replaced that flow with `saveMediaCapturesWithVariants`, which stores capture rows and variants inside one Room transaction.

The two app-module assertion diagnostics shown beside the persistence failure came from earlier test reports. The Gradle excerpt shows fail-fast stopped the graph at persistence before `:app:testDebugUnitTest` ran. r8 still hardens both app contracts so their project-root discovery and assertion scopes are deterministic when they run again.

## Changes

- Updates the Phase 6 persistence contract to require:
  - transactional DAO replacement and reconciliation;
  - the `saveMediaCapturesWithVariants` repository helper;
  - `database.withTransaction` coverage;
  - atomic batch wiring in `MainViewModel`;
  - absence of a second `replaceMediaVariants` call in the batch flow.
- Makes the Phase 5 browser-handoff contract locate the Android project root rather than assuming a Gradle worker directory.
- Scopes HLS resolver assertions to `resolveCapturedPlaylistIfPossible` and page-probe assertions to `capturePageUrl`.
- Scopes Phase 46 batch assertions to `captureMediaBatchInput` instead of searching the entire view model.
- Adds assertion messages so future failures identify the violated contract directly.
- Extends the embedded validator to mirror all three JUnit predicates and execute all six browser-extension JavaScript suites.

## Baseline

Built against the rolled-back source snapshot `xdm-20260806-123048.tar.gz`. The overlay includes the complete r7/r6 runtime payload plus this contract closure, so it can be applied directly to that baseline.
