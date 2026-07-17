# XDM Android

Standalone Android download manager implemented through Phase 5 with the Phase 6B embedded-aria2 runtime foundation: modular Kotlin/Compose architecture, Room persistence, reconciled physical-artifact ownership, native HTTP/HTTPS transfers, Android long-running execution, public/SAF storage, and a supervised authenticated loopback aria2 process boundary.

## Build

The project targets JDK 17, Android SDK 36, target SDK 36, Gradle 9.4.1, and the pinned version catalog.

```bash
tools/devtool-gradle.sh lintDebug lintBeta :transfer-api:test :storage:test :transfer-native:test :transfer-aria2:test :scheduler:test testDebugUnitTest assembleDebug assembleBeta
```

`tools/devtool-gradle.sh` delegates to `~/.local/bin/build-apk`, which selects or installs the pinned Gradle 9.4.1 distribution and handles Termux/chroot execution. The repository intentionally does not ship a partial wrapper. Run `tools/bootstrap-gradle-wrapper.sh` only when you want to generate and commit a complete standard wrapper, including `gradle-wrapper.jar`.

To build debug and official signed release APKs from the repository root, provide release signing inputs and run:

```bash
./build-release-apk.sh
```

Use `./build-release-apk.sh debug` for a debug APK without release signing, or `./build-release-apk.sh release` for only the signed release APK. The script accepts signing values from environment variables or `app/XDM.Android/release-signing.env`: `XDM_RELEASE_STORE_FILE`, `XDM_RELEASE_STORE_PASSWORD`, `XDM_RELEASE_KEY_ALIAS`, and `XDM_RELEASE_KEY_PASSWORD`. It writes APKs to `dist/android/`.

## Implemented through Phase 5 + Phase 6B runtime foundation

- Fourteen-module Compose project and Room schema v5.
- Transactional per-file backend ownership using physical artifact sets, stable backend instance IDs, rotating process-session IDs, and capability-based backend selection.
- Startup reconciliation quarantines legacy, missing, malformed, conflicting, and unavailable backend artifacts instead of silently releasing their destinations.
- Reconciled resumable artifacts can be adopted only through a new ownership generation.
- Native HTTP/HTTPS segmentation, pause/resume, durable checkpoints, strict range/validator checks, retry, and crash-safe staging.
- UIDT on supported Android versions with foreground-service fallback, notification actions, WorkManager restoration, and reboot recovery.
- Debug, beta, and release variants plus repository-root Android CI.
- MediaStore public collections and SAF folder/SD-card destinations.
- Persisted URI permissions and destination-health records.
- App-private staging followed by atomic file promotion or copy/flush/content-provider commit.
- Overwrite, resume, rename, skip, and compare conflict policy persistence.
- Optional embedded aria2 process foundation using an ARM64 APK-native executable, loopback-only authenticated JSON-RPC, random app-private secret, session saving, bounded shutdown, and unexpected-exit observation.
- Diagnostics can run a real start/authenticate/save/shutdown smoke probe without adding a new top-level route.
- Production aria2 task creation remains gated until durable GID mapping and event reconciliation are installed; native and aria2 ownership still cannot overlap.

See `docs/architecture/PHASE-6B-ARIA2-RUNTIME-FOUNDATION.md`, `docs/architecture/PHASE-6A-OWNERSHIP-HARDENING.md`, `docs/architecture/PHASES-0-1.md`, `PHASES-2-3.md`, `PHASE-4.md`, and `PHASE-5.md`.


## Supplying the ARM64 aria2 runtime

Place a PIE ARM64 Android build of aria2 at `transfer-aria2/src/main/jniLibs/arm64-v8a/libaria2c.so`. XDM executes it only from Android's installed `nativeLibraryDir`; it never copies executable code into writable app storage. Builds without the file remain usable and report the optional backend as unavailable.
