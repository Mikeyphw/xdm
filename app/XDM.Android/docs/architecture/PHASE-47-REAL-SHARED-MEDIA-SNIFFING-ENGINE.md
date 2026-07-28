# Phase 47 correction: real shared app-side media sniffing engine

This overlay corrects the Phase 47 audit gap by landing the app-side shared media sniffing engine that the Phase 48 seal previously claimed.

## Delivered behavior

- `MediaSniffingEngine`, `MediaSniffingInput`, `MediaSniffingCandidate`, `MediaSniffingPlan`, and `MediaPageProbe` are real media-module sources.
- `MediaPageProbe` is Bounded: 10 second connect and read timeouts, 768 KiB body-prefix cap, HTTP(S) only, No arbitrary JavaScript execution, and No DRM bypass.
- HLS detection uses extension/MIME/classifier evidence and body signatures such as `#EXTM3U`.
- DASH detection uses extension/MIME/classifier evidence and body signatures such as `<MPD` and DASH namespaces.
- URL extraction covers plain text, JSON/script text, HTML attributes, CSS `url(...)`, escaped slash URLs, Unicode-escaped URLs, and relative URL resolution against the page/final URL.
- Fragment/noise filtering removes obvious HLS/DASH segment fragments, ad URLs, trackers, pixels, and analytics noise.
- Ranking promotes HLS/DASH manifests above previews/fragments while preserving signed media query strings on candidates.
- Diagnostics are redacted through `PrivacyDiagnosticsRedactor`.

## Shared routing

The following app paths now use the shared engine instead of parallel detector logic:

- Media batch intake through `MediaBatchIntakePlanner`.
- Add Download / external media review through `ExternalMediaReviewPlanner`.
- Android shared text through `MainViewModel.captureSharedText()`.
- Browser-extension automation media capture through `executeCaptureMediaCommand()`.

## Batch UX correction

The Media screen keeps the promised review-first actions:

- Inspect all
- Add selected
- Clear invalid
- Copy rejected lines

Add selected is now selection-based. Reviewable direct media links are shown with checkboxes and only checked links are sent to the shared app-side sniffer.

## Phase 48 correction

Phase 48 remains the release gate, but this overlay records `phase48_corrected_after_audit` because the original Phase 48 seal claimed shared app-side sniffing before these source files existed.
