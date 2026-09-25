# XGO-53 implementation report — media selection policy

## Scope

XGO-53 adds the canonical media selection policy for the unified Go engine. It intentionally remains inside the `xgo_media` target and does not start the HLS/DASH execution wave.

## Delivered behavior

- Deterministic media selection over resolution, bitrate, codec, container, language, subtitle policy, and estimated size constraints.
- `MediaSelectionResult` separates `available_choices`, `default_video`, and explicit selected video/audio/subtitle choices.
- Selection rationale is machine-readable (`SelectionDecision` codes plus subject IDs) and does not embed UI ranking strings in the domain.
- Optional subtitle preferences do not force fallback, while required subtitles fall back deterministically when a preferred language is unavailable and fail when no subtitle track exists.
- Audio language fallback is deterministic when preferred languages are unavailable.
- Equivalent resolution cases honor codec preference without changing graph identity.

## Validation

- `engine/media` unit coverage exercises deterministic selection, unavailable preferred language, equivalent-resolution codec preference, separate audio/video selection, subtitle optional/required behavior, and estimated-size rejection.
- `xgo-media-audit --mode media_selection` is wired as `media_selection_matrix` in `xgo_media#validate` after media graph convergence.
- `XGO-CAP-MEDIA-003` is promoted to `IMPLEMENTED` and its fixture is detailed.

## Non-goals

- No HLS/DASH fragment ledger, playlist parser, or post-processing implementation is included here.
- No UI ranking text or presentation-specific sorting is embedded in the domain layer.
