# XDM Android

Standalone Android download manager implemented through Phase 5: modular Kotlin/Compose architecture, Room persistence, transactional backend ownership, native HTTP/HTTPS transfers, Android long-running execution, and public/SAF storage.

## Build

The project targets JDK 17, Android SDK 36, target SDK 36, Gradle 9.4.1, and the pinned version catalog.

```bash
tools/devtool-gradle.sh lintDebug lintBeta :transfer-api:test :storage:test :transfer-native:test :scheduler:test testDebugUnitTest assembleDebug assembleBeta
```

`tools/devtool-gradle.sh` delegates to `~/.local/bin/build-apk`, which selects or installs the pinned Gradle 9.4.1 distribution and handles Termux/chroot execution. The repository intentionally does not ship a partial wrapper. Run `tools/bootstrap-gradle-wrapper.sh` only when you want to generate and commit a complete standard wrapper, including `gradle-wrapper.jar`.

To build debug and official signed release APKs from the repository root, provide release signing inputs and run:

```bash
./build-release-apk.sh
```

Use `./build-release-apk.sh debug` for a debug APK without release signing, or `./build-release-apk.sh release` for only the signed release APK. The script accepts signing values from environment variables or `app/XDM.Android/release-signing.env`: `XDM_RELEASE_STORE_FILE`, `XDM_RELEASE_STORE_PASSWORD`, `XDM_RELEASE_KEY_ALIAS`, and `XDM_RELEASE_KEY_PASSWORD`. It writes APKs to `dist/android/`.

## Implemented through Phase 5

- Fourteen-module Compose project and Room schema v4.
- Transactional per-file backend ownership and capability-based backend selection.
- Native HTTP/HTTPS segmentation, pause/resume, durable checkpoints, strict range/validator checks, retry, and crash-safe staging.
- UIDT on supported Android versions with foreground-service fallback, notification actions, WorkManager restoration, and reboot recovery.
- Debug, beta, and release variants plus repository-root Android CI.
- MediaStore public collections and SAF folder/SD-card destinations.
- Persisted URI permissions and destination-health records.
- App-private staging followed by atomic file promotion or copy/flush/content-provider commit.
- Overwrite, resume, rename, skip, and compare conflict policy persistence.
- aria2 remains an explicit Phase 6 placeholder and cannot own the same destination as the native backend.

See `docs/architecture/PHASES-0-1.md`, `PHASES-2-3.md`, `PHASE-4.md`, and `PHASE-5.md`.
