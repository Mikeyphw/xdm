# XDM Desktop REM11 — Media Discovery Catalog Identity

Date: 2026-09-13
Target: `xdm_modern`
Overlay: `xdm_desktop_rem11_media_discovery_catalog_identity_v1.tar.gz`
Status: intermediate remediation overlay; apply with `--no-validate` and reserve full gate validation for the final REM seal.

## Objective

REM11 closes the media-catalog defects identified in S08 by making media discovery truthful before segmented execution. It hardens HLS/DASH identity, redirect-base resolution, yt-dlp fallback/routing, format classification, quality ceilings, authenticated inbox deduplication, and destination filename inference.

## Findings covered

- S08-01: HLS/DASH relative child URLs now resolve against the final redirected manifest URI.
- S08-02 and S08-08: DASH representations receive stable scoped identities that include Period and AdaptationSet scope instead of trusting globally unique `Representation@id`.
- S08-03 and S08-19: HLS variants/renditions and yt-dlp subtitles use content-derived stable IDs instead of positional IDs.
- S08-04 and S08-16: HLS group association and DASH role/default semantics are preserved into `MediaFormat` and used during selection.
- S08-05 and S08-17: yt-dlp fallback runs for failed lightweight/native probes and returns contained diagnostic catalogs for tool/parse failures.
- S08-06 and S08-09 through S08-11: the desktop media inbox and download destination infer truthful audio/video containers and avoid stale `.mp4` carry-over.
- S08-07: DASH `ContentProtection` is surfaced as encrypted catalog metadata.
- S08-12: quality ceilings are now hard ceilings, not soft preferences.
- S08-13 and S08-14: yt-dlp `abr`/`vbr` are converted into bandwidth, while codecless/non-media entries are rejected.
- S08-15: yt-dlp receives the app proxy/direct-network policy through a private config file.
- S08-18: media inbox deduplication includes source page, browser, URL, and authenticated request metadata fingerprint instead of URL alone.
- S08-20 and S08-21: HLS live/VOD probing covers variants more truthfully and DASH catalog duration is retained for size estimates.
- S08-22: yt-dlp stderr/tool diagnostics are preserved in an Unknown catalog rather than disappearing as null.

## Implementation summary

### Native catalog parsing

- Added `MediaManifestResponse` so manifest reads return both content and final request URI.
- Updated HLS and DASH catalog creation to parse relative children against the redirected final URI while preserving the original catalog source.
- Extended DASH parsing with Period ID/index, AdaptationSet ID/index, Role, default-role detection, ContentProtection detection, container inference, duration retention, and scoped representation IDs.
- Updated `DashDownloader` to resolve selections by scoped ID while remaining backward-compatible with legacy raw representation IDs.

### Selection and destination truth

- Extended `MediaFormat` with audio/subtitle group, Period, AdaptationSet, and role metadata.
- Updated media selection so quality ceilings exclude over-ceiling video instead of falling back above the limit.
- Scoped audio/subtitle choices to the selected HLS group where available, preserving association even when the requested language would otherwise cross streams.
- Added automatic destination filename refresh that preserves user edits but updates stale auto-generated filenames when the selected format changes from video to audio or between containers.

### yt-dlp routing and diagnostics

- Added `YtDlpNetworkPolicy`, `IYtDlpNetworkPolicyProvider`, `StaticYtDlpNetworkPolicyProvider`, and an app settings-backed provider.
- Wired DI so yt-dlp receives current app proxy policy.
- Kept metadata/proxy config off the command line via private temporary config files.
- Added stable fragment/subtitle IDs, bandwidth inference from `tbr`/`abr`/`vbr`, codecless format rejection, and diagnostic Unknown catalogs for extractor failures.

### Inbox identity

- Updated `MediaInboxItemViewModel` identity generation to hash browser/source-page/catalog URL plus a normalized metadata fingerprint. This prevents different authenticated captures of the same URL from collapsing into one inbox entry while not exposing raw secrets in the ID.

## Contract tests added

- `Rem11MediaCatalogContractTests.HlsMasterChildrenResolveAgainstFinalRedirectedManifestUri`
- `Rem11MediaCatalogContractTests.DashCatalogPreservesScopedRepresentationIdentityDrmRolesAndDuration`
- `Rem11MediaCatalogContractTests.ProviderFallbackRunsWhenHttpProbeFailsBeforeParsing`
- `Rem11MediaCatalogContractTests.DirectMediaUsesFilenameEvidenceAndClassifiesAudioTruthfully`
- `Rem11MediaCatalogContractTests.SelectionUsesHardQualityCeilingAndHlsGroupAssociation`
- `Rem11MediaCatalogContractTests.YtDlpCatalogUsesAbrVbrStableSubtitleIdsAndRejectsCodeclessEntries`
- `Rem11MediaCatalogContractTests.YtDlpProviderPassesProxyPolicyThroughPrivateConfigFile`
- `Rem11MediaCatalogContractTests.YtDlpProviderContainsToolFailuresAsDiagnosticCatalogs`
- `Rem11MediaInboxIdentityTests.MediaInboxIdentityIncludesAuthenticatedRequestContext`

## Validation performed in artifact build environment

- C# changed-file structural scan for braces/brackets outside literals.
- REM11 sentinel checks for scoped DASH IDs, HLS group propagation, redirect final URI, yt-dlp proxy config, hard ceilings, authenticated inbox identity, and contract tests.
- Devtool manifest JSON parse.
- Overlay tarball extraction and manifest presence check.

The artifact build container does not include the .NET SDK, so `dotnet test` was not run here. Devtool should be used on the target Termux/repo host with the supplied `--no-validate` command for this intermediate overlay.
