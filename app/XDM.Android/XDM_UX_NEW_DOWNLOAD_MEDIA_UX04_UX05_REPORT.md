# XDM Android UX04 + UX05 Product UI Report

## UX04 — New Download + destination preflight

- Replaced the permanent destination-chip wall with one `Save to` row and an adaptive destination picker.
- Picker groups built-in destinations and the user’s validated writable SAF folders, with a dedicated `Choose another folder` action.
- Added destination preflight using the same `DestinationWriter.health()` truth source introduced by UX01. The review surface shows writable state and free capacity when known, explicitly handles providers that do not report capacity, and preserves the prior destination when a new selection is not writable.
- Added advisory remote-link preflight. Manual HTTP(S) links are probed after a short debounce for suggested filename, MIME type, content length, range/resume support, and redirects. Browser handoffs reuse already supplied metadata instead of reissuing credential-sensitive requests. Probe failure never blocks admission.
- Remote metadata can improve the inferred filename shown in the review card without overwriting the user’s explicit file-name field.
- Advanced engine selection remains collapsed. Fallback wording now describes behavior rather than the implementation term “Compatible fallback.”
- Conflict policy leads with Rename, Resume, and Overwrite; Skip and Compare are available under More options.

## UX05 — Media capture product UI

- Replaced mixed consumer states with the lifecycle `Captured → Ready → Downloading → Downloaded`, plus `Refresh needed`, `Unavailable`, and `Protected`.
- Media state is now derived from both capture readiness and the latest durable output generation.
- Completed outputs make `Open` the primary action and `Download again` the secondary action. Active/queued outputs display `Downloading` rather than offering another primary download.
- Browser capture headers now lead with page/site and media count. Observation evidence is available only under `Capture details`; cookies, authorization values, and exact temporary URLs remain hidden.
- Batch intake copy is reduced to one task-oriented sentence. Static/live/DRM limitations are moved under technical details.
- Empty Media now offers `Open Live locator` directly.
- Card secondary action is renamed from `Options` to `Details`.

## Safety / persistence

- No Room schema changes. Schema remains 21.
- No new top-level route.
- No credential-bearing values are persisted or rendered by preflight.
- HTTP preflight is advisory and time-bounded; transfer execution remains authoritative.
