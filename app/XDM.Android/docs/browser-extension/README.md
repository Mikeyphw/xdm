# XDM Android Firefox extension

The canonical Firefox Android media bridge lives in `browser-extension/`. It is part of the XDM Android release contract, not an independently copied binary.

## Release artifacts

The Gradle build creates deterministic Dark and AMOLED XPIs:

```bash
./gradlew \
  :browser-extension:packageFirefoxExtensionDark \
  :browser-extension:packageFirefoxExtensionAmoled \
  :browser-extension:verifyFirefoxExtensionReleaseArtifacts
```

Outputs:

```text
browser-extension/build/outputs/xpi/
  XDM-Android-Firefox-1.1.0-release-dark.xpi
  XDM-Android-Firefox-1.1.0-release-amoled.xpi
  release-artifacts.json
```

`release-artifacts.json` records the deterministic SHA-256 and byte count for each generated XPI. Generated XPI files are build outputs and are never committed as overlay payloads.

## Architecture boundary

- The extension detects media; it does not embed an Android browser runtime.
- A physical tap on the in-page Shadow DOM FAB opens an encrypted-v2 `xdmdownload://capture` handoff or the optional `idmdownload:` target.
- The XDM capture URI outer layer carries only bounded envelope fields (`v`, `sid`, `kid`, `ek`, `iv`, `ct`). Exact request URLs, page metadata, request fingerprints, and eligible request headers remain inside authenticated ciphertext rather than appearing in the routable URI.
- The Android receiver remains `ExternalAddDownloadActivity` and routes all accepted work through review-first intake.

## Phase 43A launcher parity

The popup now treats the top-frame Shadow DOM launcher as a first-class health gate. `bridge-selftest.js` verifies that a normal HTTPS page can mount a temporary host before the real FAB is injected, and the popup shows separate bridge, handoff, FAB, page-host, sniffer, and offer diagnostics. Child-frame detector injection is best-effort and cannot block the manual top-frame FAB.

High-confidence HLS/DASH network observations may show the FAB even when a playing video exposes only blob, MediaSource, encrypted, or otherwise non-downloadable playback URLs.

See `IRONFOX-INSTALLATION.md` and `DEVICE-ACCEPTANCE.md` for installation and release qualification.
