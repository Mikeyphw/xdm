# Phase 43A — Browser Extension FAB/Detector Parity Repair

Phase 43A repairs the repo-owned Firefox Android extension after the pre-release-channel removal baseline. It is intentionally scoped to the browser-extension bridge: no Add Download UX, download-list actions, completed-notification routing, media batch input, or app-side 1DM-style sniffing engine changes are included.

## Exit gate

A manual launcher must render before media detection is considered healthy:

```text
Normal HTTPS page
  -> open the XDM Firefox extension popup
  -> tap Show app test
  -> close the popup
  -> themed XDM FAB remains visible in the webpage
```

That top-frame FAB path is now required. Iframe detector injection remains best-effort because Firefox Android pages can block or omit extension access for individual child frames, but those child-frame failures must not poison the visible top-frame launcher.

## Repairs

- Added a dependency-free `bridge-selftest.js` that proves the active page can mount a temporary Shadow DOM host before the popup attempts the real launcher.
- Made popup bridge injection top-frame-first and iframe-best-effort, matching the only frame that can visibly host the FAB.
- Added bridge health diagnostics for self-test, bridge, handoff, FAB renderer, page host, page sniffer, and offer counts.
- Added `showManualWithDiagnostics()` so the popup receives a truthful success/failure report instead of a silent boolean.
- Added page-sniffer status reporting for fetch, XHR, media-play, and PerformanceObserver wrappers.
- Repaired high-confidence HLS/DASH network fallback so a playing encrypted/blob/blocked video that cannot produce a concrete launcher no longer suppresses the network-derived FAB.

## Validation

`tools/validate-phase-43a-browser-extension-parity.py` statically locks the Phase 43A scope, and `browser-extension/tests/test_phase43a_bridge.js` validates the manual FAB, blocked-playback fallback, and visible missing-dependency diagnostics.

Focused validation:

```bash
cd app/XDM.Android
python3 tools/validate-phase-43a-browser-extension-parity.py
cd browser-extension
node tests/test_detector.js \
  && node tests/test_handoff.js \
  && node tests/test_fab.js \
  && node tests/test_phase43a_bridge.js \
  && node tests/test_background.js \
  && node tests/test_release_gate.js
```

The Phase 42 full release matrix remains the release train gate. Phase 43A only requires the touched browser-extension parity lane when run with a focused Devtool task subset.
