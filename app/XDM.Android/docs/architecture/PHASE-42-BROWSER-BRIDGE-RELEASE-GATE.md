# Phase 42 - Browser Bridge Release Gate

## Purpose

Phase 42 seals the complete XDM Android to Firefox extension bridge introduced in Phases 37 through 41. It does not add a new download workflow. It makes the existing scheme, detector, package generator, theme/FAB, settings, diagnostics, and recovery contracts release-qualifiable as one system.

## Automated gate

The Phase 42 Devtool overlay requires restore, build, test, package, and lint validation. `.devtool.toml` declares the full matrix:

- `help` restore/preflight;
- `assembleDebug`, `assembleBeta`, and `:app:assembleDebugAndroidTest`;
- Dark and AMOLED XPI packaging and release-artifact verification;
- all configured module and app unit tests;
- browser-extension Kotlin, JavaScript, package, and app integration checks;
- `lintDebug` and `lintBeta`.

The artifact validator runs Phase 37 through Phase 42 static contracts and the extension JavaScript suites before Devtool starts the full Gradle phases. Any failure is rollback-eligible.

## Kotlin compile recovery

The Android validation compile lane is deliberately clean-room. Before any app Kotlin compile, `:app:resetKotlinValidationState` removes only app-local Kotlin compiler outputs and incremental state. Devtool then compiles with Kotlin incremental compilation, classpath snapshots, Gradle configuration cache, Gradle build cache, and project parallelism disabled. The Kotlin compiler runs in-process inside one bounded Gradle JVM.

This prevents a stale `built_in_kotlinc` tree or a missing `kotlin-backups/*.backup` file from turning one damaged incremental compile into hundreds of false unresolved-reference diagnostics. Normal developer builds remain incremental unless `-Pxdm.cleanKotlinValidation=true` is supplied.

## Release artifact contract

`verifyFirefoxExtensionReleaseArtifacts` checks exact archive inventory, stable extension identity, version, minimal declared permissions, fixed ZIP timestamps, generated scheme, generated theme, and distinct Dark/AMOLED hashes. It writes `browser-extension/build/outputs/xpi/release-artifacts.json` after both XPIs pass.

## Architecture invariants

- `ExternalAddDownloadActivity` alone owns XDM browser schemes.
- `capture` remains Media review and `add` remains Add Download.
- No Android WebView, GeckoView, built-in browser route, or JavaScript-enabled browser runtime is reintroduced.
- No static Context, Activity, or WebView reference is added to browser-bridge code.
- Manifest MIME declarations remain lowercase.
- Custom URI payloads remain credential-thin.
- Diagnostics and release metadata remain bounded and redacted.
- Room stays at schema 14, `versionCode` stays 21, and `versionName` stays `0.20.0-rc08`.

## Device acceptance

Static and Gradle success do not prove that IronFox hands custom schemes to Android on a particular device. `tools/run-browser-bridge-device-acceptance.sh` verifies package installation, scheme ownership, and Android intent launches. `docs/browser-extension/DEVICE-ACCEPTANCE.md` defines the remaining direct media, HLS, DASH, blob/MediaSource, iframe, target, theme, SAF recovery, coexistence, deduplication, and privacy checks.

## Exit criteria

- Devtool completes with zero warnings and zero errors.
- Dark and AMOLED XPIs are generated, verified, and hashed.
- Android resolver checks pass for the intended installed variant.
- The manual IronFox matrix passes on IronFox 152 or newer.
- Release metadata contains no raw credentials.

Until the device matrix is signed off, the code and automated release gate are landed but device release qualification remains pending.
