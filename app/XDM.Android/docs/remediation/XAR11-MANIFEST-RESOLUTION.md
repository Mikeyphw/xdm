# XAR11 — Media Manifest Resolution, Track Graph & Expiry Semantics

Overlay 11 of 17 closes the S11 media detection/resolution findings. It deliberately stops at resolver/candidate truth; executor byte semantics remain owned by XAR12.

## Resolver invariants

- DASH `SegmentTemplate` representations are converted into executable segment URLs/templates and initialization URLs instead of flattening execution to `BaseURL`.
- DASH `Period` boundaries are preserved as `manifestTimelineGroupId`, and BestVideo auto-selects a compatible audio representation from the same timeline group.
- DASH XML parser hardening is required. If a parser cannot apply the XXE/doctype controls, MPD parsing fails closed.
- HLS `CLOSED-CAPTIONS` are represented as in-band metadata with `requiresNetworkFetch=false`, not as a bogus subtitle network input pointing at the master playlist.
- HLS `TYPE=VIDEO` alternative rendition groups are parsed and exposed as video choices.
- Inline bounded manifest probes are authoritative only when the body is complete enough to prove format boundaries; truncated 768 KiB probes must refresh/stream before resolving variants.
- Response `Content-Length` from a page is applied only to the direct response/body signature resource, never to URLs merely extracted from HTML/JSON.
- Session/cookie requirement and queued expiry are derived from selected executable child URLs and selected inputs, not from every unselected variant in the capture.
- Resolver Ready is blocked when the capture manifest or any selected child URL has expired.

## Canonical coverage

Closed canonical IDs: S11-01, S11-02, S11-03, S11-04, S11-05, S11-06, S11-07, S11-08, S11-09, S11-10, S11-11, S11-12, S11-13, S11-14, DS6-S11-01, DS6-S11-02, DS6-S11-03, DS6-S11-04.

## Validation

Run `python3 tools/validate-xar11-manifest-resolution.py` for the focused source contract, or Gradle task `verifyXar11ManifestResolution` in a full build environment.
