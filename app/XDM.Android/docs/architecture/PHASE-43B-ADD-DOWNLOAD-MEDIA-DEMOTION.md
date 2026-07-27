# Phase 43B — Add Download Media Recommendation Demotion

Phase 43B keeps Add Download focused on the ordinary download-manager path after the browser-extension parity repair. Media analysis remains available, but the UI no longer treats every page-shaped URL as a media job.

## Policy

`DownloadReviewPlanner` now uses a pure `MediaInspectionPolicy` with three visibility weights:

```text
Hidden       normal Add Download flow; no media button
Optional     neutral Analyze/Inspect affordance
Recommended  explicit review-choice state before queueing
```

Rules:

```text
AdaptiveMedia                 -> Recommended
BrowserExtension DirectMedia  -> Recommended
BuiltInBrowserDownload media  -> Recommended
ExternalDownloadManager media -> Recommended
Manual DirectMedia            -> Optional
External DirectMedia          -> Optional
BuiltInBrowserPage unknown    -> Optional
ExternalView PageOrUnknown    -> Optional
ExternalShare PageOrUnknown   -> Optional
ManualEntry PageOrUnknown     -> Hidden
DirectFile                    -> Hidden
Torrent                       -> Hidden
```

## UX changes

- Normal manual unknown/page links stay in `Ready` and show `Add to queue`, not `Review choice`.
- Page-shaped external links use the neutral `Analyze page for media` label.
- Direct media keeps `Inspect media`, and only high-confidence media origins use `Inspect media (recommended)`.
- The helper text now explains that page analysis is for watch pages or playlists, not normal files.
- Browser-extension handoffs keep their dedicated `BrowserExtension` intake origin, so extension-captured direct media can still be promoted without making manual Add Download noisy.

## Non-goals

Phase 43B does not add download-list action menus, completed-notification open-file routing, media batch input, or the future shared app-side sniffing engine. It is a review-planner/UI wording correction only.

## Validation

Focused validation:

```bash
cd app/XDM.Android
python3 tools/validate-phase-43b-add-download-media-demotion.py
./gradlew :core-model:test --tests com.mikeyphw.xdm.android.model.DownloaderExperienceTest --tests com.mikeyphw.xdm.android.model.DownloadIntakePlannerTest
./gradlew :app:testDebugUnitTest --tests com.mikeyphw.xdm.android.BrowserExtensionPhase43BContractTest
```

The full app unit-test matrix may still contain unrelated historical contract failures. Phase 43B is guarded by the core planner tests and the targeted app contract above.
