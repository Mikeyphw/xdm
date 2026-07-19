## XDM Android 0.13.0-alpha01

Adds Phase 8 checksum verification, persisted verification results, trusted block manifests, and selective repair planning.

# XDM Android

Standalone Android download manager implemented through Phase 7: modular Kotlin/Compose architecture, Room persistence, reconciled physical-artifact ownership, native HTTP/HTTPS transfers, Android long-running execution, public/SAF storage, and a supervised authenticated loopback aria2 process boundary.

## Build

The project targets JDK 17, Android SDK 36, target SDK 36, Gradle 9.4.1, and the pinned version catalog.

```bash
tools/devtool-gradle.sh lintDebug lintBeta :transfer-api:test :storage:test :transfer-native:test :transfer-aria2:test :scheduler:test :media:test testDebugUnitTest assembleDebug assembleBeta
```

`tools/devtool-gradle.sh` delegates to `~/.local/bin/build-apk`, which selects or installs the pinned Gradle 9.4.1 distribution and handles Termux/chroot execution. The repository intentionally does not ship a partial wrapper. Run `tools/bootstrap-gradle-wrapper.sh` only when you want to generate and commit a complete standard wrapper, including `gradle-wrapper.jar`.

To build debug and official signed release APKs from the repository root, provide release signing inputs and run:

```bash
./build-release-apk.sh
```

Use `./build-release-apk.sh debug` for a debug APK without release signing, or `./build-release-apk.sh release` for only the signed release APK. The script accepts signing values from environment variables or `app/XDM.Android/release-signing.env`: `XDM_RELEASE_STORE_FILE`, `XDM_RELEASE_STORE_PASSWORD`, `XDM_RELEASE_KEY_ALIAS`, and `XDM_RELEASE_KEY_PASSWORD`. It writes APKs to `dist/android/`.

## Implemented through Phase 7

- Fourteen-module Compose project and Room schema v7.
- Native HTTP/HTTPS and operational embedded aria2 backends with exclusive transactional destination ownership.
- Capability-based automatic selection explains protocol, destination, authentication, expiry, mirror, size, host-history, diagnostics, and battery factors.
- Optional fallback is permitted only before a backend task is created and owns the destination.
- Requested backend, actual backend, selection reason, explanation, and fallback policy survive process restarts.
- Controlled native-to-aria2 and aria2-to-native migration pauses and retires the source writer, inspects artifacts, prepares a distinct target partial, transfers ownership by generation, and journals every stage.
- Cross-backend partial files are never silently reused. Existing bytes are preserved as recovery artifacts when the user explicitly restarts with another backend.
- Settings exposes the live backend capability matrix and recent migration history without adding new top-level navigation.
- Native segmentation, strict resume validation, Android background execution, MediaStore/SAF storage, and aria2 authenticated loopback RPC remain fully integrated.

See `docs/architecture/PHASE-7-BACKEND-STRATEGY-MIGRATION.md` and the earlier architecture documents in `docs/architecture/`.


## Supplying the ARM64 aria2 runtime

Place a PIE ARM64 Android build of aria2 at `transfer-aria2/src/main/jniLibs/arm64-v8a/libaria2c.so`. XDM executes it only from Android's installed `nativeLibraryDir`; it never copies executable code into writable app storage. Builds without the file remain usable and report the optional backend as unavailable.

## Embedded aria2 runtime

Phase 6 provides an operational on-device aria2 backend with durable Room-to-GID mappings, paused-before-ownership activation, authenticated loopback RPC, session reconciliation, and XDM-controlled completion promotion. The optional official ARM64 payload is installed and attested with:

```bash
python3 tools/install-aria2-runtime.py --download-official
python3 tools/verify-aria2-runtime.py --require-payload
```

Distribution builds should pass `-Pxdm.requireAria2Runtime=true`. Builds without the optional payload remain valid native-only builds and report aria2 as unavailable in Diagnostics.


## Phase 9 startup recovery, atomic finalization, and Phase 10 media capture intelligence

XDM Android now scans interrupted transfers, backend ownership records, aria2 session mappings, backend migration journals, finalization journals, and app-private transfer artifacts at startup. Recovery records remain paused until the user validates, resumes, repairs, adopts, locates, or removes them. Finalization is journaled so process death during promotion can be recovered deterministically.


## Phase 10 media capture and Phase 11 media resolution

The existing Media route captures shared browser links and direct VIEW intents for video, audio, HLS, and DASH sources. Captures persist metadata such as title, MIME type, container, codec summary, duration, thumbnail URL, variant count, and the created download relationship.

Phase 11 resolves HLS/DASH manifests into persisted variants, keeps a selected variant on the capture record, labels variants by quality, and blocks stale playlist downloads until the capture is refreshed. Media downloads use the selected variant URL when present and stay inside the existing Media route.


### Phase 12: external automation intake

XDM Android now records external Tasker, browser, share-sheet, and deep-link style commands in a durable automation journal. Commands use stable idempotency keys so repeated intents do not duplicate downloads or media captures. Diagnostics reports the automation command count.


### Phase 13: browser integration hardening

Browser, share-sheet, Tasker, and deep-link handoffs now use a shared normalization and idempotency policy so repeated URLs from different external sources collapse to the same command instead of duplicating downloads. Sensitive request headers such as Authorization and Cookie are redacted before persistence, while Diagnostics exposes only safe origin host, source, status, and rejection reason summaries.
