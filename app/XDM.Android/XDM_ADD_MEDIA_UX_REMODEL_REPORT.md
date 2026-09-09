# XDM Android Add + Media UX Remodel

Base: `c214240e` (execution/media semantics repair v3 applied successfully).

## Goal

Turn the now-correct download execution model into a calmer, 1DM+-inspired user flow without weakening XDM's explicit-intake, privacy, or backend-safety contracts.

## Add Download

- The adaptive **New download** sheet remains the entry surface.
- The old `Review download -> Add to queue` second confirmation is removed.
- A normal valid direct download is queued with one explicit **Download** tap.
- External/browser handoffs still never auto-queue: opening the sheet is not consent to start the transfer.
- File/link identity and a human-readable destination are primary.
- Browser-session health, backend recommendation/escalation, checksum, conflict policy, and other expert controls are under **Advanced options**.
- Page/adaptive-playlist inputs that genuinely need media inspection show **Inspect media** instead of pretending they are direct files.
- Direct progressive media remains directly downloadable and does not get forced through media inspection.

## Media

- Direct progressive media cards expose **Download** as the primary action.
- A single direct MP4 no longer displays a fake `Primary` quality choice.
- Quality/audio/subtitle controls appear only when playlist/variant data provides meaningful choices.
- Resolver/execution attention is described as **Needs attention**, not the misleading `Could not read` wording.
- URL intake and Live locator remain easy to reach, while batch/diagnostic-style intake is collapsed under **More tools**.

## Compatibility and safety

- The internal `DownloadReviewPlanner` remains a pure classification/policy helper; removing the second UI confirmation does not introduce silent queueing.
- Duplicate-download confirmation remains separate because it protects against accidental duplicate transfers rather than duplicating the normal Add flow.
- Room remains schema v21.
- The execution/media semantics repair remains the functional baseline.

## Warning cleanup

`PostDl03ReleaseFollowupContractTest` now avoids both Java-platform nullability warnings by defaulting `user.dir` safely and requiring the repository parent explicitly.

## Validation

The canonical final gate owns `tools/validate-add-media-ux-remodel.py`. The seal asserts the one-action Add flow, collapsed diagnostics, direct-media card behavior, warning fixes, carry-forward release authority, and the absence of the superseded second-confirmation tokens from the active Add surface.

## Promise-delivery audit follow-up — 2026-09-08

The direct-media simplicity promise now applies to the **Options** sheet as well as the main card. `MediaTrackPickerSheet` receives the same `hasTrackChoices` decision and does not render a one-item `Primary` quality group for a normal progressive MP4. Adaptive quality/audio/subtitle controls remain available when choices are meaningful.

