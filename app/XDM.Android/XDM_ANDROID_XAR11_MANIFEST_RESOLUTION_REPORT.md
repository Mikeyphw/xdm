# XDM Android XAR11 Manifest Resolution Report — 2026-09-13

## Overlay

**Overlay 11 of 17 — XAR11: Media Manifest Resolution, Track Graph & Expiry Semantics**

## Scope

Primary owner for **S11 — Media Detection / Resolution**. This overlay closes all **18/18 canonical S11 findings** while preserving executor/runtime byte handling for XAR12.

## Implemented fixes

- DASH `SegmentTemplate` resolution now builds executable media segment URLs/templates and initialization URLs. Representations no longer execute the adaptation/root `BaseURL` when segmented media is present.
- DASH `Period` boundaries are carried as `manifestTimelineGroupId`; BestVideo auto-selects a compatible audio representation from the selected video period instead of flattening all periods into one simultaneous set.
- DASH parser hardening is fail-closed. Required doctype/entity parser features are applied explicitly; unavailable hardening controls throw a configuration failure instead of being swallowed.
- HLS `CLOSED-CAPTIONS` are non-network, in-band metadata. They no longer point back to the master playlist as a fake subtitle input.
- HLS `TYPE=VIDEO` alternative rendition groups are parsed as video variants and labeled as alternative-video renditions.
- Inline bounded manifest probes require completeness before becoming authoritative. Truncated 768 KiB probes no longer create durable variant truth.
- Page response `Content-Length` remains bound only to direct/body-signature observations and is not rebound to extracted HTML/JSON URLs.
- Selected child URLs drive credential/session fallback decisions, so an unselected signed/credential-bearing variant no longer contaminates a safe selected path.
- Queued media specs and resolver Ready state include selected child expiry, not only the primary/master/capture expiry.
- Focused behavioral tests cover DASH template execution, period/audio selection, HLS closed captions, HLS alternative video, selected-only credential/expiry semantics, Ready expiry blocking, and incomplete manifest probe rejection.

## Canonical closure

Closed **18/18** findings: `S11-01`, `S11-02`, `S11-03`, `S11-04`, `S11-05`, `S11-06`, `S11-07`, `S11-08`, `S11-09`, `S11-10`, `S11-11`, `S11-12`, `S11-13`, `S11-14`, `DS6-S11-01`, `DS6-S11-02`, `DS6-S11-03`, `DS6-S11-04`.

## Validation performed in this container

- `tools/validate-xar11-manifest-resolution.py`: passed, 27 checks, 18/18 S11 findings covered.
- Retained focused validators XAR01-XAR10: passed.
- `python3 -m py_compile tools/validate-xar11-manifest-resolution.py`: passed.
- `bash -n tools/run-final-release-gate.sh`: passed.
- `python3 -m json.tool PROJECT_MANIFEST.json`: passed.
- Kotlin syntax smoke check with local `kotlinc`: no lexical/syntax/string-template failures were reported before dependency-resolution errors expected from compiling source files outside the Gradle classpath.

## Container limitation

A full Gradle execution was not completed in this container because the project Gradle 9.7.1 environment is not available offline here. This remains an intermediate overlay intended for `--no-validate` application; final Gradle/device validation remains for XAR16/XAR17.

## Apply mode

Intermediate overlay. Apply with `--no-validate`.
