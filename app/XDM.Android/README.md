## XDM Android 0.20.0-rc02

Adds Phase 8 checksum verification, persisted verification results, trusted block manifests, and selective repair planning.

# XDM Android

Standalone Android download manager implemented through Phase 7: modular Kotlin/Compose architecture, Room persistence, reconciled physical-artifact ownership, native HTTP/HTTPS transfers, Android long-running execution, public/SAF storage, and a supervised authenticated loopback aria2 process boundary.


## Phase 17: final public release gate

Phase 17 is the final release-candidate gate. It does not add a route or database migration. It locks the package identity, keeps Room at schema v14, exposes the final release gate in Diagnostics and Settings, runs static validators through Phase 17, documents the signed release flow, and requires the full devtool validation pass before a public artifact is accepted.

For the final gate from the repository root, use:

```bash
cd "$HOME/Code/xdm" && devtool   --copy   --auto-hud   --hud-mode desktop-window   --yes   -r "$HOME/Code/xdm"   apply-overlay "$HOME/Downloads/xdm_android_phase17_final_public_release_gate_overlay.zip"   --validate
```

Inside `app/XDM.Android`, the static final gate is also available as:

```bash
tools/run-final-release-gate.sh
```

## Phase 16: packaging, recovery and install/update readiness

Phase 16 prepares the Android app for installable beta packaging without changing the database. The app now exposes an install/update readiness report, keeps release diagnostics privacy-safe, records the package identity contract, confirms recovery surfaces remain available before update, refreshes CI/static validators through Phase 16, and removes the deprecated Compose clipboard API in favor of the Android clipboard service. Room remains at schema v14.

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


## Phase 14 release safety

XDM Android now includes privacy-safe diagnostic summaries, redaction helpers, and a schema-free beta release gate for pre-release validation.


## Phase 15 UX and accessibility polish

Phase 15 keeps the existing route topography while tightening the Android surface for compact phones and assistive technology. Downloads now expose a compact overview, action labels include the target file, progress indicators publish screen-reader state, release Diagnostics has an accessible copy action, and Settings records the polish contract without adding a new top-level route. Room remains at schema v14.


### Post-17 desktop parity

XDM Android now exposes settings import/export, history/file management, proxy/credential profile metadata, conversion/post-processing policy, protocol coverage polish, and release/non-debug packaging helpers without adding a new top-level route or migrating Room past schema v14.

### Built-in browser media downloader

XDM Android now includes a Browser workspace inside the existing Media route for review-first media discovery. The embedded WebView observes page loads, resource requests, and browser download callbacks, then captures likely HLS, DASH, progressive video, and audio streams into the Media inbox. The Media route explains the preferred engine for each capture: native direct download, aria2 segmented download, yt-dlp resolver, or live recording workflow.

### Browser media continuity

The Media route now contains a browser workspace with persisted tabs, recent history, and explicit cookie profiles. Captured HLS/DASH media is enriched with audio/subtitle variants, live/protected classification, and page-first yt-dlp probing. The offline library panel summarizes direct, adaptive, audio-only, and subtitle-ready captures without adding a new top-level route.

### Media resolver and player

The Media route now resolves captured HLS/DASH manifests through a real picker surface: video quality, audio tracks, and subtitle tracks are selected before download planning. yt-dlp metadata previews show title, thumbnail availability, duration, extractor, and format count before the download action runs. Session handoff is review-first: referer, Origin, and cookie jar hints are passed only to typed yt-dlp/aria2/native planning paths, while diagnostics keep cookies, authorization, tokens, and signed query values redacted.

Completed direct media can open in the embedded Media3 player card. Adaptive or protected streams remain resolver-first, and protected media is diagnostic-only: XDM reports the protection marker but does not bypass DRM or queue protected media.

### Media execution and offline library

The Media route now carries resolver-selected video, audio, and subtitle tracks into actual queue planning. Direct/progressive media can run through the native or aria2 backends, while adaptive/page-context jobs use the typed Termux yt-dlp pipeline and stay attached to the originating media capture. Request headers are handed off through a short-lived in-process store and removed after terminal execution; Room, diagnostics, and sidecar metadata keep only redacted summaries.

The offline library is derived from media captures plus completed downloads inside the existing Media route. Rows show title, filename, source host, duration, thumbnail availability, state, redacted sidecar metadata, retry/resume actions, and Media3 player access for completed direct media. Protected media remains diagnostic-only with no DRM bypass and no raw shell exposure.

### Media download engine hardening

Phase 21 adds the next execution-hardening layer without adding a route or database migration. Media queue specs are now classified into explicit engine lanes for native direct downloads, aria2 segmented downloads, yt-dlp adaptive jobs, live recording, and protected diagnostics. The lane model chooses a UIDT-ready Android 14+ policy, WorkManager foreground fallback, legacy dataSync foreground-service fallback, Termux external job, or blocked diagnostic state before queueing.

Temporary Netscape cookie files and aria2 input/session files are modeled as transient cleanup-owned artifacts only. Persistent metadata keeps redacted summaries, and the Media screen exposes the hardening policy plus no-cookie-leak status. The Media3 player card now reports player error diagnostics and exposes retry prepare for completed direct media. Per project direction, validation is deferred until the final phase; apply this overlay with `--no-validate`.

### Media dispatch control tower

Phase 22 keeps the existing Media route as the single workspace and adds a dispatch control tower before jobs leave the inbox. Each media capture now receives a pure-Kotlin dispatch runbook with readiness, lane, background policy summary, retry policy, progress signals, warnings, terminal cleanup, and redacted diagnostics.

The dashboard counts ready, blocked, refresh-required, and Termux-required plans while preserving the no-secret contract from earlier phases. Only ready dispatch plans can proceed. Protected media remains diagnostic-only, metadata-expired media asks for refresh, and yt-dlp/live jobs require the Termux media pipeline. Per project direction, this intermediate overlay is applied with `--no-validate`; the validator and architecture contract are included for the final phase replay.

## Phase 23: Media queue telemetry

Phase 23 adds a Media-route telemetry deck on top of the Phase 22 dispatch control tower. It merges dispatch readiness with current execution jobs so the user can see what is ready, active, stalled, terminal, cleanup-armed, or blocked by redaction before the final validation phase.

The telemetry layer is pure Kotlin and does not launch processes, persist cookies, write Room rows, add routes, or expose raw shell. It renders safe progress pulse, next action, terminal cleanup state, and redacted diagnostics inside the existing Media inbox.


## Phase 24: Media queue actions

Phase 24 turns queue telemetry into safe action eligibility inside the existing Media route. The Media queue actions card shows launch, pause, resume, retry, cancel, cleanup, refresh metadata, choose tracks, Termux setup, diagnostics, and library handoff availability without launching workers or exposing raw shell.

Destructive actions are modeled as confirmation-required, terminal cleanup remains tied to redaction verification, and blocked/protected captures stay diagnostic-only. This intermediate phase is intended for `--no-validate`; the validator and architecture contract are included for the final validation replay.


## Phase 25: Media worker bridge

Phase 25 converts ready media queue actions into worker bridge requests without actually enqueueing workers yet. The bridge models Android UIDT, WorkManager foreground, foreground dataSync fallback, native direct, aria2, and Termux yt-dlp adapters with durable job IDs, redacted foreground notification text, cleanup-owned transient files, and typed arguments only.

The Media worker bridge card stays inside the existing Media route. No Room migration, no new top-level route, no raw shell, and no persistent cookies or tokens are introduced. This intermediate phase is intended for `--no-validate`; the final media validation gate will replay its validator and architecture contract.
