# XDM Android Promise Delivery Audit — 2026-09-08

## Baseline

Audited the uploaded current repository after the execution/media semantics repair and Add/Media UX remodel. The audit traced intake, request-shape classification, encrypted handoff, redownload/link refresh, scheduler execution, backend migration, Live Locator recreation, media state, and the direct-media UI.

## Confirmed delivered

- Browser replay headers are orthogonal to transfer shape and URL expiry.
- Direct files and progressive media use direct HTTP semantics; HLS/DASH remain adaptive.
- Transfer shape survives encrypted persistence, scheduler reconstruction, and backend migration.
- Direct media is ready without playlist variants and execution failures do not overwrite resolver state.
- Add Download uses one explicit action with advanced diagnostics collapsed.
- Direct media cards use Download as the primary action.
- Room remains schema 21.

## Gaps found and closed

1. **Legacy media boolean fallback** — `DownloadRequest.isMediaRequest=true` could still default the shape to `AdaptivePlaylist`. The bit is now source-compatibility-only and cannot promote a direct resource.
2. **Opaque redownload/link refresh** — a known `AdaptivePlaylist` could be downgraded to `DirectFile` when its refreshed signed URL had no extension. Refresh now preserves specialized semantics when the new URL has no stronger shape evidence.
3. **Live Locator recreation/privacy** — saved-state serialized exact source/page/variant URLs and restore reclassified opaque manifests from URL/MIME. Exact executable URLs plus headers now stay only in the bounded process-local context cache; Bundle state carries a cache key and semantic/display metadata, and saved kind is used to restore opaque HLS/DASH.
4. **Direct-media Options sheet** — the main card hid fake quality controls but Options could still show a single `Primary` row. The track picker now uses the same meaningful-choice gate.
5. **Legacy media boolean still changed native wire defaults** — the compatibility-only `isMediaRequest` bit still fabricated `Sec-Fetch-Mode` / `Sec-Fetch-Site` and selected a media-wide `Accept` header. The native backend now preserves only explicitly captured `Sec-Fetch-*` values and derives default media `Accept` behavior from `transferShape`/MIME instead of the legacy bit.
6. **AndroidX WebKit 1.17.0 cookie feature lint false positive** — `WebViewFeature.COOKIE_INTERCEPT` is a public runtime feature, but AndroidX 1.17.0 omits it from the `WebViewSupportFeature` `@StringDef`, causing `WrongConstant` during `lintDebug`. The Live Locator keeps the required runtime `isFeatureSupported` guard and applies a narrowly scoped `@SuppressLint("WrongConstant")` only to that gate. The overlay now requires `:app:lintDebug`.
7. **Native recovery resume race** — the backend publishes `RecoveryRequired` from inside its transfer coroutine just before that coroutine finishes its `finally` cleanup. An immediate UI/test `resume()` could therefore observe the recovery state while `job.isActive` was still true and incorrectly return as a no-op. Recovery resume now joins only that already-terminal, still-unwinding job before launching recovery. Final-save recovery continues to retry destination publication from preserved staging bytes without any network redownload.
8. **AndroidX WebKit 1.17.0 Kotlin renderer lint false positive** — the Live Locator already overrides `onRenderProcessGone`, removes the dead WebView from its parent, destroys it, and returns `true`, but the 1.17.0 lint detector falsely reports Kotlin anonymous `WebViewClient` implementations. The source keeps the real recovery callback and suppresses only `MissingOnRenderProcessGone` on the `onCreate` construction path until the upstream Kotlin detector fix is consumed.
9. **Hardcoded Live Locator saving status** — `status.text = "Saving captured media…"` triggered `SetTextI18n`. The status now comes from `R.string.media_locator_saving`; no lint suppression is used for this genuine localization issue.

## Validation

The canonical final gate owns `tools/validate-promise-delivery-audit.py`. Unit/source contracts cover legacy-boolean shape and native-wire isolation, opaque-shape clone/refresh continuity, Live Locator saved-state privacy/semantic restoration, direct-media Options gating, the scoped AndroidX WebKit cookie-feature and Kotlin renderer-detector workarounds, resource-backed Live Locator saving status, and immediate recovery-resume synchronization. The existing final-save recovery test remains timing-strict and proves publication retry succeeds after the test server is stopped, so a network redownload cannot hide the recovery path. `:app:lintDebug` is a required overlay validation task.
