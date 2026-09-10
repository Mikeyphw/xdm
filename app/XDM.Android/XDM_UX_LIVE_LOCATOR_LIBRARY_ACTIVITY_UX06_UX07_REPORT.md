# XDM Android UX06 + UX07 implementation report

## Scope

This intermediate roadmap overlay implements UX06 (Live Locator redesign) and UX07 (Library, Activity, and Recovery UX) on top of the applied UX04/UX05 product UI overlay. Full Gradle/static validation is intentionally deferred to the final overlay in the series; apply this overlay with `--no-validate`.

## UX06 — Live Locator native shell

- Replaces the hard-coded white utility shell with a native themed shell that follows Android/XDM background and text colors.
- Applies system-bar insets to the locator root.
- Adds a native title/close bar, URL field, Go, page Back/Next, Reload, Stop, and Scan media controls.
- Adds native WebView loading progress.
- Adds a dedicated detected-media count above results and hides the empty result list.
- Keeps the actual website inside WebView while XDM owns navigation, scan state, status, and candidate presentation.
- Preserves `onRenderProcessGone` handling and fixes a latent runtime bug: a destroyed renderer WebView is no longer reused. Reload/Go recreates the activity safely with the last URL after renderer loss.
- Keeps file/content access disabled, popup windows disabled, and mixed-content downgrade blocked.

## UX07 — Library, Activity, and Recovery

### Library

- Empty library now says **No media yet** and exposes **Find media**.
- Adds sort controls for Recent, Title, and Size while retaining media-type filters.
- Adds a Stored metric based on completed download artifact sizes.
- Renames the playable primary action from Play to **Open**.
- Adds **Share** in media details.
- Adds a destructive-confirmation flow for **Delete saved file** when the library item is backed by a completed XDM download.
- Keeps **Remove library record** separate and explicitly non-destructive.

### Activity and recovery

- Repeated incidents with the same category/severity/title/detail/action/source are grouped into one card.
- Grouped cards show affected-download or similar-event counts and the latest check time.
- Header metrics are simplified to **unresolved** and **events today**.
- The management entry and sheet are renamed **Queue & recovery**.
- Storage-pressure incidents now offer **Retry storage check** and re-run queue intelligence rather than sending users directly to change a valid destination.
- Underlying event history remains intact; grouping is a user-facing projection only.

## Deferred validation

No Gradle, lint, retained release-gate, or full unit-test validation is run for this intermediate overlay. New UX06/UX07 contract tests are included so the final roadmap overlay can validate these promises together with the rest of the series.

Room remains schema **21**.
