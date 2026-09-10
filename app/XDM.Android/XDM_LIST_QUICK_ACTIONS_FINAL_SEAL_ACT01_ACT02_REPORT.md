# XDM Android ACT01/ACT02 — List quick actions and final roadmap seal

Date: 2026-09-10
Overlay: `xdm_android_list_quick_actions_final_seal_v1.zip`
Depends on: `xdm_android_live_locator_title_naming_v1.zip`

## Scope

ACT01/ACT02 closes the 2026-09-10 product-polish series by making common per-item actions reachable directly from Downloads, captured Media, recently queued Media, and Library cards while retaining the existing canonical action planners, storage truth, recovery flows, and destructive-operation confirmations.

## Download card quick actions

`DownloadActionPlanner.quickActionsFor()` now owns a bounded two-action card subset. The first action is always the canonical planner primary action. The second is state-aware:

- active/connecting/finalizing/verifying/repairing: Cancel when available;
- queued/created: Cancel next to Start now;
- paused/waiting/failed: Replace source URL next to Resume/Retry;
- completed: Share file next to Open, falling back to Rename/Details when capabilities require it;
- recovery-required: recovery/location action next to the canonical recovery primary;
- cancelled: Redownload, then record removal as a fallback.

`DownloadRow` renders this planner-owned subset plus the existing overflow menu. Quick destructive actions are not special-cased: they route through `runDownloadAction()`, so `requiresConfirmation` continues to open the existing confirmation sheet.

## Media capture and recently queued actions

Captured-media cards keep their state-aware Download/Open/resolve action and now expose `Edit` and `Remove` directly. Remove opens a confirmation explaining that existing downloaded files are retained.

The Media screen's recently queued rows now expose their current Pause/Resume/Retry action together with direct Cancel for active/queued work and Manage for opening that exact download in Downloads. The Manage path selects the download detail before navigating, avoiding a generic Downloads landing page.

## Library actions

Library list and grid cards now expose Open/Resume/Retry as before, plus direct Share when a playback URI exists, Manage for the full details sheet, and Remove. Direct record removal uses one confirmation dialog and explicitly states that the downloaded file remains on-device. Permanent file deletion remains the separate existing `Delete saved file` flow.

## Roadmap promise audit

The final seal re-audited every requested promise from the series against production source, regression contracts, and canonical validators:

| Requested improvement | Delivery evidence | Status |
| --- | --- | --- |
| Proper normal/debug logging | OBS01/OBS02 structured event recorder, standard/verbose modes, operation correlation | Complete |
| Proper notification on problems | Durable problem ledger, dedupe/cooldown, actionable problem notifications, Debug Center routing | Complete |
| Thumbnails for media downloads | Shared `XdmMediaArtwork` on download rows with resolver/capture/local fallbacks | Complete |
| Thumbnail for captured media from WebView/other capture paths | THUMB pipeline plus WEB metadata propagation | Complete |
| Thumbnail on Media captured list | `MediaCaptureCard` uses shared artwork path | Complete |
| WebView improvement | Native Live Locator shell, navigation state, page summary, retry/error surfaces, SSL fail-closed, capture panel | Complete |
| Save captured media with page title by default | NAME01 title-first naming for page-derived WebView captures | Complete |
| Apply naming to extension-captured media | NAME01 extension handoff keeps page title and uses the same naming policy | Complete |
| Proper MIME-backed icons without thumbnails | MIME-first presentation resolver with adaptive-media and document/package families | Complete |
| Easier card access to open/edit/cancel/remove and related actions | ACT01/ACT02 planner quick actions plus direct Media/Library management | Complete |

No requested promise remains unimplemented in this roadmap.

## Validation ownership

This is the first overlay in the series that must **not** be applied with `--no-validate`. The canonical final release gate now runs all four roadmap validators:

- `tools/validate-observability-problem-reporting.py`
- `tools/validate-media-thumbnail-mime-presentation.py`
- `tools/validate-live-locator-title-naming.py`
- `tools/validate-list-quick-actions-final-seal.py`

The final Devtool run must explicitly execute the Android common matrix: final static gate, Kotlin compile, core/model/util/API tests, browser integration/storage/native/aria2/scheduler/media/persistence/app tests, lint, extension tests/validation, browser integration check, debug APK assembly, and Android-test APK assembly.

Room schema remains 21. No new route, external image dependency, transfer backend, or persistence schema is introduced by ACT01/ACT02.
