## XDM Android 0.20.0-rc08

Adds Phase 8 checksum verification, persisted verification results, trusted block manifests, and selective repair planning.

# XDM Android

## Browser bridge integration

Phases 37–41 provide a variant-specific `xdmdownload` intake contract, repository-owned Firefox extension, deterministic in-app XPI export, shared XDM theme/FAB, and a truthful Settings health surface. Phase 41 verifies Android scheme ownership, retained SAF access, XPI size/SHA-256, extension/app compatibility, interrupted generation, and redacted accepted/rejected handoff diagnostics. See `docs/architecture/PHASE-41-BROWSER-BRIDGE-INTEGRATION.md`.

## UIX R6 final experience seal

The Android UI is now sealed as a dark, flat, adaptive five-destination experience with Downloads, Media, Library, Activity, and Settings. Add remains an internal review-first route presented as an adaptive sheet/dialog. UIX R6 adds stable accessibility semantics, 48 dp targets, 200% font-scale qualification, Compact/Medium/Expanded contracts, lazy Developer diagnostics, consumer-safe source scans, device smoke tests, and the final release checklist. See `docs/architecture/UIX-R6-ACCESSIBILITY-PERFORMANCE-RELEASE-SEAL.md`.

## Downloader-only release seal

XDM Android is a focused download manager with six stable destinations: Downloads, Add, Media, Library, Activity, and Settings. It integrates with external browsers through explicit sharing, typed download intents, file-extension handlers, and Android download-manager actions. It does not contain WebView or register as a general browser.

The permanent product and release contract is documented in `docs/architecture/DOWNLOADER_PRODUCT_CONTRACT.md`. The final browser-removal validator is `tools/validate-browser-removal-phase-7.py`.

Phase 8A + 8B adds a review-first manual intake planner and a grouped Downloads control center. The contract is documented in `docs/downloader/PHASE-8AB-DOWNLOADER-INTAKE-DASHBOARD.md`, and its validator is `tools/validate-downloader-experience-phase-8ab.py`.

Phase 8C adds explainable queue policy, classified retry backoff, condition-driven evaluation, foreground WorkManager ownership for automatic transfers, persistent decision history, and an explicit soft-policy override. The contract is documented in `docs/downloader/PHASE-8C-QUEUE-INTELLIGENCE.md`, and its validator is `tools/validate-downloader-experience-phase-8c.py`.

Phase 8D promotes Media into a first-class resolver workspace with rich format comparison, persistent video/audio/subtitle choices outside Room, redacted request-context review, protected-media diagnostics, and recent resolution history derived from downloader captures. The contract is documented in `docs/downloader/PHASE-8D-MEDIA-RESOLVER-POLISH.md`, and its validator is `tools/validate-downloader-experience-phase-8d.py`.

Phase 8E turns Activity into a unified operational flight recorder with searchable Timeline, unresolved Attention, explainable queue Decisions, bounded transfer-transition retention, and privacy-safe diagnostics export. The contract is documented in `docs/downloader/PHASE-8E-ACTIVITY-DIAGNOSTICS.md`, and its validator is `tools/validate-downloader-experience-phase-8e.py`.

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

Phase 16 prepares the Android app for installable release packaging without changing the database. The app now exposes an install/update readiness report, keeps release diagnostics privacy-safe, records the package identity contract, confirms recovery surfaces remain available before update, refreshes CI/static validators through Phase 16, and removes the deprecated Compose clipboard API in favor of the Android clipboard service. Room remains at schema v14.

## Build

The project targets JDK 17, Android SDK 36, target SDK 36, Gradle 9.4.1, and the pinned version catalog.

```bash
tools/devtool-gradle.sh lintDebug :transfer-api:test :storage:test :transfer-native:test :transfer-aria2:test :scheduler:test :media:test testDebugUnitTest assembleDebug
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

XDM Android now includes privacy-safe diagnostic summaries, redaction helpers, and a schema-free release gate for release-candidate validation.


## Phase 15 UX and accessibility polish

Phase 15 keeps the existing route topography while tightening the Android surface for compact phones and assistive technology. Downloads now expose a compact overview, action labels include the target file, progress indicators publish screen-reader state, release Diagnostics has an accessible copy action, and Settings records the polish contract without adding a new top-level route. Room remains at schema v14.


### Post-17 desktop parity

XDM Android now exposes settings import/export, history/file management, proxy/credential profile metadata, conversion/post-processing policy, protocol coverage polish, and release/non-debug packaging helpers without adding a new top-level route or migrating Room past schema v14.

### Downloader-focused navigation

The browser-free shell now uses six stable destinations: Downloads, Add, Media, Library, Activity, and Settings. Compact layouts keep Downloads, Media, Library, Activity, and Settings in the bottom bar, while Add remains globally available through the floating action button. Expanded layouts expose all six destinations in the navigation rail.

Library owns completed media, playback readiness, sidecar health, resume, and retry. Activity keeps a searchable operational timeline, unresolved attention, explainable queue decisions, queue management, schedules, recovery, diagnostics, and privacy-safe external handoff history in one workspace. Persisted Queues, Scheduler, Recovery, and Diagnostics routes migrate to Activity automatically.

### External browser handoff and media discovery

XDM Android is a focused download manager and no longer contains a built-in browser or WebView runtime. External browsers and applications can share links or delegate typed and file-extension-specific downloads through `ExternalAddDownloadActivity`. Add Download classifies the incoming link, preserves safe MIME and page context, and never starts a transfer silently.

The Media route remains the review and resolver workbench for direct media, HLS, DASH, and page-level yt-dlp probes. Captures can still expose audio/subtitle variants, live/protected classification, engine recommendations, offline-library state, and Media3 playback without an internal browsing surface.

Browser-era tabs, history, bookmarks, private sessions, permissions, WebView resources, and active browser-product validators are gone. Capture quality remains as a browser-neutral media intake service, and privacy auditing treats external page context as transient data. The `browser-integration` module remains intentionally because it handles external browser and download-manager intents without embedding a browser engine.

### XDM browser custom scheme

External browser extensions can target XDM through a versioned custom scheme owned only by `ExternalAddDownloadActivity`. Release and debug builds use `xdmdownload` and `xdmdownload-debug` respectively, preventing parallel installations from fighting over one handler. `capture` opens the existing Media review flow, while `add` opens Add Download. Browser-extension media capture uses an encrypted v2 envelope: exact URLs and request headers stay inside authenticated ciphertext, while the outer URI carries only bounded envelope identifiers/key-wrap material. The legacy v1 capture parser remains compatibility-only and is not generated by the production extension or acceptance tooling.

Encrypted release handoff shape:

```text
xdmdownload://capture?v=2&sid=<session>&kid=<key-id>&ek=<wrapped-key>&iv=<iv>&ct=<ciphertext>
```

### Media resolver and player

The Media route now resolves captured HLS/DASH manifests through a real picker surface: video quality, audio tracks, and subtitle tracks are selected before download planning. yt-dlp metadata previews show title, thumbnail availability, duration, extractor, and format count before the download action runs. Session handoff is review-first: referer, Origin, and short-lived cookie/header hints are passed only to typed yt-dlp/aria2/native planning paths, while diagnostics keep cookies, authorization, tokens, and signed query values redacted.

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

## Repository-owned Firefox extension

The canonical Firefox Android media bridge source now lives in `browser-extension/`. The current bridge preserves the layered detector while superseding Phase 38 plaintext capture with the encrypted-v2 `xdmdownload://capture` envelope, retains an optional 1DM+ target, and prepares unpacked development output without committing an XPI. See `docs/architecture/PHASE-38-REPO-OWNED-FIREFOX-EXTENSION.md`.


### Deterministic Firefox XPI export

Phase 39 lets Gradle and XDM Android generate the same validated Firefox extension package through a shared Kotlin packager. Settings → Browser extension stores a user-selected SAF directory, default target, and generated theme, then stages and verifies the XPI before promotion. Successful exports expose filename, size, app/extension version, timestamp, and SHA-256. See `docs/architecture/PHASE-39-XPI-GENERATION-SAF-EXPORT.md`.

### Shared extension theme and compact media FAB

Phase 40 moves the XDM Dark and AMOLED palettes, extension shape values, and motion values into one non-Compose token catalog consumed by both the Android theme and XPI generator. Follow-app exports capture the current app theme and become visibly stale when XDM changes theme. The extension now uses a safe-area-aware 56 px Shadow DOM FAB with candidate and stream-kind badges instead of the former floating card. See `docs/architecture/PHASE-40-SHARED-THEME-FAB.md`.

### Browser bridge release qualification

Phase 42 binds the `xdmdownload` receiver, repository-owned Firefox detector, deterministic Dark/AMOLED XPI generator, shared theme/FAB, and Settings recovery surface into one release gate. Run `bash tools/run-browser-bridge-release-gate.sh --full` in the Android build environment, then complete `docs/browser-extension/DEVICE-ACCEPTANCE.md` on IronFox 152 or newer. Automated success does not replace physical-device sign-off.


### Phase 43 — Pre-release channel removal

The Android app now carries only release and debug variants. Pre-release build types, package suffixes, browser schemes, release-gate tasks, and extension channel generation have been removed from active source.


## Runtime Foundation Phase 55–56

The personal sideload build supports repaired embedded aria2 RPC diagnostics/recovery, a deep loopback aria2 lifecycle smoke test, a first-class direct `Download/XDM` destination backed by Android All Files Access, guarded custom direct shared-storage folders, and a Storage Doctor that verifies filesystem durability plus embedded aria2 / Termux yt-dlp / FFmpeg path access while retaining MediaStore and SAF fallbacks. See `docs/quality/RUNTIME-FOUNDATION-PHASE55-56-PROMISE-CLOSURE-AUDIT.md` for the r2 promise audit.


### App-process restart ownership closure

Phase 55 now persists an app-private aria2 runtime ownership lease containing only the loopback endpoint, secret generation, and start timestamp, never the secret itself. On a later XDM process start, a matching-generation daemon must answer authenticated RPC before XDM treats it as its own. Proven XDM-owned orphan daemons are session-saved and shut down over authenticated RPC before a fresh runtime is launched; stale or unreachable markers are cleared without killing an unproven process.


## Master remediation Overlay 10 — media resolver/execution

The remediation campaign's media execution phase now makes the real download button honor the selected variant's exact request headers and the configured destination, and it enforces the existing dispatch-readiness plan before queue state changes. App-owned direct/aria2 media creates the normal XDM `Download`; adaptive/live yt-dlp work is represented only by its durable Termux job, avoiding a second synthetic queue owner.

Room schema 20 adds `media_outputs`, a one-capture-to-many output/generation history that preserves app-download and Termux-job ownership separately. Migration 19→20 backfills the legacy capture→download link, and Library rows are keyed by output generation so multiple successful/retry outputs from one capture can coexist. “Remove library record” now removes only the selected generation rather than cascading the parent capture; Termux owner/output metadata is removed atomically and production disables legacy-link fallback so deleted final generations do not reappear. Media execution failure categories now come from structured strategy/state/backend signals rather than error-message substring matching. Authenticated Termux session transport remains intentionally held for Overlay 11 rather than placing secrets on command lines.
## Master remediation combined Overlays 11–12 — Termux security + UI/navigation truth

The Termux/post-processing phase now treats the durable process token as mandatory ownership: result callbacks without the exact token fail closed, controls are signalled only after durable CAS acceptance, delayed callbacks cannot resurrect terminal jobs, and a child whose attach CAS loses is immediately force-cancelled through its exact owner. Capture-backed yt-dlp jobs use an opaque durable media-session identity and recover exact URL/header state only from the encrypted handoff into private transient Termux files/FIFO transport; raw yt-dlp paths are denied. Shared metadata staging receives only a restricted non-secret yt-dlp projection rather than raw `-J` output. Root filesystem actions are canonical-path authorized, terminal bridge artifacts are cleaned before graph detachment, and Developer Tools can run the same bounded privacy audit that scans private bridge files/FIFOs/run directories plus shared `.xdm-*` staging artifacts.

Navigation/UI remediation keeps Add ephemeral while persisting actual Activity/Settings panels, Downloads detail targets, and Recovery target/action state. Downloads uses measured content width plus the exact window-space hinge rectangle for list/detail behavior, and fold-aware sheets choose a physical safe pane rather than straddling an off-center separating hinge. Completed-file actions re-check current provider/generation state and destructive confirmation is re-planned before execution. Destination rules use safe host boundaries with fallback-after-specific ordering, saved searches can actually restore their query/filter/archive state, bulk controls consume the real action planner, saved destination types use human labels, review/health/escalation surfaces have unique semantics tags, and the active accessibility validator enforces Add `imePadding()`, measured/fold behavior, and behavioral semantics instrumentation.

Campaign Gradle/unit/lint and final validator harmony remain deferred to Overlay 13.


## Master remediation Overlay 13 — privacy, quality, final gate

The final remediation stage makes validation evidence fail-closed, audits real app-private media/browser persistence roots, groups media quality by exact request identity, and keeps player/execution failure classification on structured Media3/state/backend signals. The final media gate now targets Overlay 13 and Room schema 20 instead of the historical media phase ledger.

The final gate also repaired the browser bridge after direct JS testing: popup/page/manual/probe Add uses the separate `xdmdownload://add?v=1` compatibility contract, while detected media requires a prebuilt encrypted-v2 capture link and fails closed without one. The optional `idmdownload:` route is restored without reintroducing plaintext XDM capture.

`finalRemediationStaticGate` is wired into Devtool validation. The final Overlay-13 artifact does not allow deferred validation: Android compile/unit/lint and browser-extension Gradle gates must pass in the target environment before the final remediation commit is accepted.
