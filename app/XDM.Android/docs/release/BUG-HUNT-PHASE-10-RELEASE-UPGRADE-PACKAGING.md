# Bug Hunt Remediation Phase 10: Release, Upgrade, Packaging, and Publication

Phase 10 turns Android release qualification from static source claims into a signed artifact gate. It is intentionally stricter than earlier rc gates.

## Release signing

Publishable `assembleRelease` and `bundleRelease` require `XDM_RELEASE_STORE_FILE`, `XDM_RELEASE_STORE_PASSWORD`, `XDM_RELEASE_KEY_ALIAS`, `XDM_RELEASE_KEY_PASSWORD`, and `XDM_RELEASE_SIGNER_SHA256`. The pinned signer SHA-256 is compared during artifact verification so certificate continuity is not a vibe, it is a locked door with a fingerprint scanner.

Unsigned developer handoff is deliberately named `developmentUnsigned`; CI and publication scripts never search debug output when a release artifact is required.

## Required artifact matrix

The signed release gate runs `lintRelease`, `testReleaseUnitTest`, `assembleRelease`, `bundleRelease`, APK signature verification, AAB inspection, aria2 payload attestation, 16 KB native alignment checks, release inventory allow/deny checks, checksum generation, and publication metadata generation. APK-set generation must pass signing inputs to bundletool; debug-key fallback is rejected.

## Packaging and native payload policy

Release packaging disables legacy JNI extraction, scopes debug-symbol retention to `libaria2c.so`, pins supported ABIs, and requires strict aria2 runtime attestation. AAB/APK-set inspection must reject 4 KB alignment reports and fail if generated split APKs do not match the pinned signer.

## Upgrade and backup policy

Version metadata is now `versionCode 22` / `versionName 0.21.0`. Device acceptance must cover clean install, previous-release-to-candidate upgrade, reboot after upgrade, downgrade rejection, SAF/MediaStore permission preservation or explicit recovery, notification behavior, durable ownership recovery, and no duplicate execution.

Backup and device-transfer rules explicitly exclude Room databases, checkpoints, signed URLs, cookies, Authorization/session material, aria2 ownership, finalization journals, Termux handoffs, recovery records, diagnostics, support bundles, WorkManager preferences, and stale execution state from both cloud backup and device-to-device transfer.

## Publication bundle

The publication bundle contains APK/AAB, SHA-256/SHA-512 sums, signer metadata, build ID, SBOM/provenance hooks, and checksum attestation instructions. Checksums must be signed or attested; APK and checksum replacement together is treated as a release failure.


## Phase 10 r2 gap closure

Phase 10 r2 closes the promise-audit gaps found after the first overlay:

- Runtime readiness reports Room schema 17 and signer/build attestation fields, never `!BuildConfig.DEBUG`.
- The release contract test is valid Kotlin and checks the runtime attestation wiring.
- The publishable release gate requires `XDM_ARIA2_ARCHIVE_SHA256`; unpinned official downloads are rejected.
- Supported native ABI scope is truthfully limited to the attested `arm64-v8a` payload until additional signed runtime payloads are added.
- APK verification now requires apksigner, APK manifest inspection, inventory allow/deny checks, supported ABI enforcement, and pinned signer continuity.
- AAB verification now requires jarsigner and bundletool config inspection with `PAGE_ALIGNMENT_16K`.
- Publication bundles include release metadata, checksums, minimal SBOM/provenance documents, mapping/native-symbol copies when present, and checksum attestation requirements.
- The device matrix performs a clean candidate install before previous-to-candidate upgrade, reboot, and downgrade rejection.
