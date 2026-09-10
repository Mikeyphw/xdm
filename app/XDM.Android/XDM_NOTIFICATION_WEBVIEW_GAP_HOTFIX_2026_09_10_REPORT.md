# XDM notification + WebView gap hotfix v5 — 2026-09-10

## Scope

This replacement hotfix closes the source-level gaps found after the ACT01/ACT02 final-seal audit and the follow-up source audit of hotfix v1. It does not replace the earlier observability, artwork/MIME, Live Locator/title-naming, or quick-action overlays; it hardens their runtime integration.

## Notification closure

- Android 14+ user-initiated transfer jobs render the exact download's live snapshot rather than the aggregate queue snapshot.
- WorkManager foreground execution refreshes from live transfer telemetry instead of a static placeholder.
- Notification updates are throttled to a 750 ms notification-only cadence; in-app transfer telemetry remains unthrottled.
- Aggregate cards expose only aggregate Pause all / Resume all actions; per-file Pause/Cancel appears only when the card identifies exactly one download.
- Pause/Resume/Cancel/Retry notification commands are journaled before execution.
- Terminal delivery is two phase: a generation/state idempotency row is reserved Pending, Android delivery happens, and only then is it marked dispatched. Pending rows survive restart and are reconciled after startup or notification-settings return.
- Completion, attention, routine state, active progress, and XDM Problems use distinct channels. Disabled channels do not falsely mark Pending terminal rows as delivered.
- Notification permission history, app-level enablement, and important channel enablement feed a persistent in-app warning/settings route.
- Problem notifications preserve the exact incident id and expose contextual Retry when a download is associated.
- Failure/problem text uses privacy-strength notification redaction; terminal notifications are explicitly grouped.
- Debug Center notification descriptions and action models reflect actual production controls.

## Live Locator and media closure

- WebView navigation/history state and scroll position survive recreation.
- Page favicons are displayed when available.
- Find-in-page, Share page, and Open in browser controls are exposed.
- Captured candidates in Live Locator use the shared thumbnail/artwork loader.
- Page poster/OpenGraph/Twitter/JSON-LD/link artwork provenance flows through WebView and extension capture paths.
- `MediaCaptureRecord.thumbnailProvenance` is persisted in Room schema 22 via `Migration21To22`.
- Page-title filenames are made collision-safe against already persisted captures, not only candidates from the current scan.

## Validation ownership

`tools/validate-notification-webview-gap-hotfix.py` is part of the canonical final static gate. Full Gradle validation remains required on the target Termux Android build host, including unit tests, lint, browser-extension tests, debug assembly, and Android-test assembly.


## Second-pass promise audit closure

A second source audit found six edge cases that the original hotfix validator did not prove. v5 carries those corrections and the earlier full-Gradle repairs, then closes the next real Gradle compile/warning findings without another Room migration:

- Persisted artwork now has an explicit quality merge policy. Resolver/generated/direct-image artwork cannot be silently downgraded by a later lower-quality page hint; equal or better incoming artwork can still refresh it.
- Completed video/adaptive downloads retain captured/resolver artwork when available. Local frame extraction is now a fallback, while completed image files still prefer their authoritative local image. The cache key is versioned so stale generated-frame thumbnails cannot mask the new ordering.
- Downloads maps capture artwork to every app-owned `MediaOutputRecord.downloadId`, including additional generations, not only the legacy/primary capture download id.
- Add Download now records whether a suggested filename came from server `Content-Disposition`, a redirect target, or the original link. Leaving File name blank submits that effective suggestion to admission; custom edits remain authoritative and the UI explains the source.
- Android 14+ UIDT active notifications use `JOB_END_NOTIFICATION_POLICY_REMOVE`; only a successfully installed terminal replacement uses `DETACH`. A blocked terminal channel therefore cannot leave a stale “Downloading” card behind after the job ends.
- Terminal notification replay uses `setOnlyAlertOnce`, Android delivery is best-effort with the dispatched marker written only after a successful call, and terminal action models are reconstructed from the same policy used by production notification buttons after process restart.

Full-Gradle follow-up closure carried from v3:

- Added the missing `media_locator_no_external_browser` string resource used by Live Locator external-browser failure handling.
- Removed the invalid explicit Compose `layout.weight` import so `ColumnScope.weight` resolves correctly.
- Removed the scheduler's obsolete pre-API-26 notification-channel branch because the module minSdk is 26.
- Migrated notification permission persistence to `androidx.core.content.edit` so scheduler lint remains warning-as-error clean.
- Added explicit page-title provenance to media candidates so quality/audio filename disambiguators apply only to genuine page/extension titles; URL-derived fallback names such as `session-token.mp3` remain unchanged.

Additional full-Gradle follow-up closure in v4:

- Phase-4 queue/notification contract now explicitly loads `TerminalNotificationActionPolicy.kt` before asserting shared recovery wording, fixing the `terminalPolicy` unresolved reference during `:app:compileDebugUnitTestKotlin`.
- Live Locator restored-state handling binds a non-null `restoredState` once after successful WebView state restoration, removing five unnecessary-safe-call Kotlin warnings without changing recreation behavior.
- The artifact allows at most one known non-project diagnostic from Gradle native Termux daemon environment synchronization; XDM source warnings remain expected at zero.

Room remains at schema 22. No storage schema changes are introduced by v4.

## Full-Gradle contract-suite repair in v5

The v4 target-host run proved production compilation and assembly progressed, then exposed eleven stale source-contract assertions plus one Kotlin test warning. v5 rebases those tests to the current runtime truth without weakening product requirements:

- Current-schema assertions now require Room 22 instead of treating historical Room 21 as the active schema.
- Add Download inference checks require both the server `Content-Disposition` explanation and the generic blank-name inference path.
- Terminal notification tests consume `TerminalNotificationActionPolicy` and the two-phase idempotency store instead of obsolete inline action/`putIfAbsent` source shapes.
- Live Locator naming tests require explicit page-title provenance and retain the URL-fallback rule.
- UX13/post-UX13/remediation release tests preserve their historical baseline authority while also requiring the current notification/WebView runtime-quality authority.
- `Ux01DestinationStorageTruthContractTest` uses a non-null `user.dir` binding, eliminating the target-host Java platform-type warning.
- Dormant unused locals in `ArchitectureContractTest` were removed so a clean test recompilation remains warning-free.

Room remains schema 22 and no production database change is introduced by v5.

## v5r1 lint correction

The v5 implementation reached the full Gradle gate but `:app:lintDebug` rejected the Live locator candidate detail row with `SetTextI18n`. v5r1 keeps the v5 behavior unchanged while moving the inferred media-type fallback and the four-field candidate detail row into Android string resources. The hotfix validator and contract test now explicitly seal those resource-backed paths and reject the original hard-coded fallback.
