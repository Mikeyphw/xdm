# Browser bridge device acceptance

Phase 42 requires both automated Android checks and manual IronFox checks. Do not mark the bridge release-qualified from static or JVM tests alone.

## Android resolver checks

Print commands for the release package:

```bash
bash tools/run-browser-bridge-device-acceptance.sh --print
```

Run the automatable checks on a connected device:

```bash
bash tools/run-browser-bridge-device-acceptance.sh --adb
```

For debug:

```bash
bash tools/run-browser-bridge-device-acceptance.sh --adb \
  --package com.mikeyphw.xdm.android.debug \
  --scheme xdmdownload-debug
```

The fixture URLs must be public and credential-free.

## Manual IronFox matrix

Record device model, Android version, IronFox version, XDM variant/version, extension SHA-256, date, and tester.

- Direct MP4 produces the themed FAB and opens XDM Media review.
- HLS and DASH prefer the manifest rather than segments.
- Blob/MediaSource playback backed by fetch or XHR is correlated to a network candidate.
- Cross-origin iframe playback surfaces the top-page FAB.
- XDM, 1DM+, and Ask targets behave correctly.
- Repeated taps do not create duplicate work.
- Dark and AMOLED generated packages use the matching XDM tokens.
- Follow app export becomes stale after an app theme change and regeneration replaces the selected XPI safely.
- Revoked SAF access, missing export, checksum mismatch, wrong variant, and interrupted generation each show a specific recovery state.
- Release and debug can coexist without competing for one scheme.
- No raw cookie, authorization, credential, or separate secret value appears in the custom URI, logs, diagnostics, screenshots, or release metadata.

## Sign-off

Release qualification requires zero Devtool warnings, zero Devtool errors, successful Android resolver checks, and a completed manual matrix on IronFox 152 or newer.
