# XDM Android UX13 — End-to-End UI/UX Release Seal

Date: 2026-09-09
Overlay: `xdm_android_ux13_end_to_end_ui_ux_release_seal_v1.zip`
Baseline: UX12 accessibility, terminology, and visual-system seal
Room schema: 21 (unchanged)

## Scope

UX13 closes the screenshot-driven product roadmap after UX01–UX12. It does not add another user-facing feature family. Its job is to make the accumulated redesign executable as one release contract and to reject cross-screen contradictions that can reappear when storage, queue, media, settings, diagnostics, or accessibility code evolves independently.

## Final product contract

### Storage truth

The queue scheduler consumes destination health through the shared `DestinationWriter.health()` path. Destination capacity is represented as known, unknown, or unavailable. A writable destination with unknown capacity is not mislabeled as unavailable merely because the provider cannot report free bytes.

### Download truth

The primary workspace remains `All / Downloading / Waiting / Finished`; Waiting includes queued, paused, and condition-held work. Queue-level holds are summarized once, and download details preserve the `What happened / What XDM will do / What you can do / Technical details` hierarchy.

### Media truth

Media uses one consumer lifecycle (`Captured / Ready / Downloading / Downloaded`, plus exceptional states). Downloaded captures prefer `Open` and keep `Download again` secondary. Normal cards do not expose private request evidence by default.

### Locator, Library, and Activity truth

Live Locator remains inside a native XDM shell and recreates safely after renderer loss. Library offers user-facing open/share/delete flows and a useful empty state. Activity groups repeated queue incidents and uses `Retry storage check` for storage-policy reevaluation rather than assuming the destination must be changed.

### Settings and developer boundary

Normal settings are organized by user intent. Diagnostics & support remains available without Developer mode. Developer mode unlocks exactly one Developer Center. The old visible Debug Workbench / Advanced Debug Workbench / Developer tools duplication must not return.

### Accessibility and language

Status meaning is available through semantics rather than color alone. Large text can reflow shared titles, metadata, actions, metrics, bottom navigation, and sidebar navigation. Current user-facing states use the normalized vocabulary established in UX12, while raw diagnostics remain selectable/copyable technical details.

## Validation ownership

UX13 is the final roadmap overlay and therefore owns validation that was intentionally deferred for UX04–UX12. The artifact requires the canonical static gate, module unit tests across the download/storage/media/browser stack, app unit tests, browser-extension validation, and Android lint. Devtool must roll the overlay back if any required task fails.

The retained Phase11 device/release matrix remains the source of truth for physical-device instrumentation, signed release publication, and previous-release upgrade/downgrade evidence. These environment-specific gates are not replaced by documentation or by UX13 source assertions.

## Completion criteria

UX13 is complete only when:

1. `PROJECT_MANIFEST.json` points to UX13 and `next_phase` is `complete`.
2. the UX13 static validator passes;
3. the canonical final static gate passes, including the retained 80-row Phase11 static matrix;
4. required Gradle unit/lint/browser-extension tasks pass in the target Termux environment;
5. Room remains schema 21;
6. no final validation failure is suppressed or converted into documentation-only evidence.
