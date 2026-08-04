# Bug Hunt Remediation Phase 5: Browser Handoff And Media Sniffing

Phase 5 closes the browser handoff, page sniffing, media classification, and backend-selection gaps found during the Android bug hunt.

## Delivered

- Media screen Paste Page URL now runs `MainViewModel.capturePageUrl(...)` and `MediaPageProbe.probePage(...)` instead of redirecting to Add Download.
- Inspect/Check Again uses the page probe and no longer fabricates a synthetic `Primary`/`Selected` variant when no network-backed manifest or resolver result exists.
- Page probe reads in a bounded loop until byte limit, EOF, cancellation through stream close, or timeout.
- Page probe preserves supplied session headers after CR/LF validation instead of stripping Cookie/Authorization before the fetch.
- Browser handoff sessions separate stable media identity from exact signed URL and revision.
- Browser handoff stores proposed headers and final-sent headers honestly; final sent headers win when available, unavailable headers are represented explicitly.
- Iframe context is preserved through `BrowserFrameContext.effectiveReferer`.
- Android acknowledgment is modeled with `acknowledgedByAndroid`.
- Session eviction records mark unresolved captures as Session Lost.
- Page-observation events require a live nonce proof.
- DRM/protection classification depends on browser encryption events, HLS key metadata, DASH `ContentProtection`, or resolver evidence, not broad URL/title substrings.
- Backend requirements derive from explicit `MediaTransferShape` values: `DirectFile`, `DirectMedia`, `AdaptivePlaylist`, `SiteResolver`, `LiveRecording`, and `ProtectedDiagnostic`.
- Fallback decisions are review-first after bytes are written and route session/media resolution failures to refresh/review rather than blind migration.

## Deferred to later phases

- Full UI copy for session-lost captures will be refined in Phase 8.
- End-to-end Android acknowledgment transport depends on the available Firefox Android handoff channel; this overlay keeps custom-scheme links credential-thin and models acknowledgment/session revision without embedding raw Cookie or Authorization values in URLs.

## r2 repair

Phase 5 r1 was rejected during self-audit because the payload had invalid Kotlin raw CR/LF character literals, a potential duplicate selection declaration, missing coordinator construction, and partial Android-side consumption of browser session metadata. r2 fixes those blockers and wires stable media ID, session revision, frame URL, final headers, page-observation proof, and app-private session persistence into the runtime intake path.
