# XDM Android OBS01/OBS02 — observability and problem reporting

Date: 2026-09-10

## Scope delivered

This first roadmap overlay turns XDM's existing redacted Debug Center recorder into a product-level observability foundation instead of adding a second logging stack.

- Standard logging remains bounded and app-private; high-volume `Trace` events are suppressed by default.
- **Verbose debug logging** is an explicit persisted user option in Diagnostics & support. It enables trace retention while preserving the same redaction and rolling-size limits.
- Debug areas now distinguish media resolver, embedded WebView, transfer backend, storage, persistence, and thumbnails so later thumbnail/WebView work can use the same event vocabulary.
- Logging is strictly best-effort. A diagnostic write failure cannot fail a transfer, capture, or UI operation.
- `ProblemIncident` adds a bounded, app-private incident ledger independent of Room/download persistence. Incidents are redacted before persistence, deduplicated, count repeated occurrences, support resolved/reopen state, and use a notification cooldown.
- Actionable runtime problems use the **XDM problems** notification channel and open Diagnostics & support for review.
- Problem-notification IDs occupy a range disjoint from `TransferSystemIdRegistry`, avoiding collisions with download notifications.
- Existing failed/recovery download notifications remain authoritative; terminal transfer incidents are recorded with `notifyUser = false` so users do not receive duplicate alerts.
- Central media-intake failures now create Media resolver incidents.
- The embedded media locator emits page-load/request/media-observation events and creates a durable incident if its renderer dies.
- Startup persistence, queue-recovery, and condition-monitor failures become durable actionable incidents instead of being only ephemeral startup state.
- Diagnostics & support gets a **Problems** page with occurrence counts, recommended action, operation/download correlation IDs, resolve/reopen, and copy-details controls.
- Debug ZIP exports include `problem-incidents.txt` alongside the existing redacted timeline and support report.

## Privacy and safety invariants

- No automatic upload is introduced.
- Raw cookies, Authorization values, tokens, signed-query secrets, and key-like values continue through `DebugRedactor` before durable diagnostics/export.
- The incident store is deliberately separate from Room so observability does not add a database migration or become a transfer-database dependency.
- Notification permission denial never prevents local problem recording.
- Repeated problems are grouped and notification-cooled instead of generating one alert per occurrence/segment.
- No new top-level app route, storage permission, browser-handoff hardening, or transfer admission behavior is introduced.

## Verification performed in the artifact workspace

- All `core-model` main Kotlin sources compile with local `kotlinc`.
- A direct smoke harness passed redaction, incident deduplication, notification cooldown, and standard-versus-verbose trace retention.
- The OBS01/OBS02 static validator passes.
- Full Gradle/Android validation is intentionally deferred. The available container does not have the Gradle 9.7.1 distribution cached and cannot access the network; this intermediate overlay must therefore be applied with `--no-validate`, consistent with the XDM overlay-series policy.

## Next overlay

`xdm_android_media_thumbnail_mime_presentation_v1.zip` will build the shared thumbnail/artwork pipeline and MIME-backed fallback presentation on this observability foundation. Thumbnail failures will report through `DebugArea.Thumbnail` rather than introducing separate diagnostics.
