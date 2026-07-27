## XDM Android Phase 42 Kotlin compile recovery hotfix

- Added a targeted app Kotlin compiler-state reset before Devtool validation compilation.
- Disabled Kotlin incremental compilation, classpath snapshots, Gradle build/configuration caches, VFS watching, and project parallelism for the release-validation lane.
- Moved validation compilation into one bounded in-process Kotlin compiler JVM, preventing stale daemon backup failures from cascading into false unresolved references.
- Added static contracts, CI wiring, and release-gate coverage for the recovery lane without slowing ordinary developer builds.

## XDM Android Phase 42 - Browser bridge release gate

- Added the full Devtool restore, build, test, XPI package, release-artifact, and lint matrix for the complete browser bridge.
- Added deterministic Dark/AMOLED XPI verification with exact inventory, fixed timestamps, stable identity, minimal permissions, SHA-256 metadata, and distinct theme hashes.
- Added consolidated Phase 37-42 architecture, secret-surface, MIME-case, route-topology, browser-runtime, and static-reference scans.
- Added IronFox installation documentation, automated Android resolver checks, and a manual direct/HLS/DASH/blob/iframe device acceptance matrix.
- Preserved the browser-free Android runtime, six-route topology, Room schema 14, versionCode 21, versionName 0.20.0-rc08, review-first intake, and credential-thin custom URI.

## XDM Android Phase 41 - Browser bridge settings, diagnostics, and hardening

- Added truthful browser-bridge health for Android scheme ownership, retained SAF access, verified XPI presence/checksum, and app-extension compatibility.
- Added result-bearing deep-link parsing with bounded accepted/rejected diagnostics that never persist raw media query values, cookies, authorization, tokens, signatures, sessions, or credentials.
- Added Settings recovery actions for folder reselection, status refresh, safe regeneration, opening the last verified XPI, and copying variant-specific IronFox setup instructions.
- Added interrupted-generation recovery, variant/contract/theme/target staleness reporting, tests, documentation, CI wiring, and a focused Devtool validator.
- Preserved the Phase 37 credential-thin scheme, Phase 39 staged replacement, Phase 40 themed FAB, six-route topology, Room schema 14, versionCode 21, and versionName 0.20.0-rc08.

## XDM Android Phase 40 - Shared extension theme and themed FAB

- Added one Dark/AMOLED palette, shape, and motion token catalog shared by Compose and the Firefox XPI generator.
- Added Follow app export selection with concrete-theme resolution and stale-export regeneration state.
- Replaced the large page launcher card with a safe-area-aware 56 px Shadow DOM FAB, candidate and HLS/DASH badges, fullscreen support, reduced motion, and XDM/1DM+/Ask targets.
- Preserved deterministic Phase 39 packaging, credential-thin Phase 37 handoffs, six-route topology, Room schema 14, versionCode 21, and versionName 0.20.0-rc08.

## XDM Android Phase 39 - Deterministic XPI generation and SAF export

- Added one Kotlin XPI generator and validator shared by Gradle and the Android runtime.
- Added sorted, timestamp-normalized, traversal-safe extension archives with exact inventory and SHA-256 verification.
- Added Settings → Browser extension with persisted SAF export directory, target, theme, and verified artifact metadata.
- Added staged export, rename/backup promotion, providers-without-rename fallback, checksum verification, and failed-partial cleanup.
- Added Dark and AMOLED package tasks, CI output generation, focused tests, documentation, and Devtool validation.
- Preserved the six-route topology, Room schema 14, versionCode 21, versionName 0.20.0-rc08, transfer engines, and browser-free runtime.

- Added Phase 38 repository-owned Firefox extension source with layered media detection, XDM-first handoff, optional 1DM+ fallback, unpacked development preparation, and focused validation.
## XDM Android Phase 37 - Browser scheme contract

- Added variant-specific `xdmdownload`, `xdmdownload-beta`, and `xdmdownload-debug` custom schemes owned only by `ExternalAddDownloadActivity`.
- Added a versioned, bounded parser for `capture` and `add` browser handoffs with review-first routing into the existing automation intake.
- Rejected nested or unsafe schemes, user-info credentials, oversized payloads, and standalone sensitive headers while preserving signed media URL query values.
- Added parser, manifest, routing, idempotency, browser-free architecture, static validator, and Android intent-resolution contracts.
- Preserved the six-route topology, Room schema 14, `versionCode 21`, `versionName 0.20.0-rc08`, and all existing external handoff paths.

## UIX R6 - Accessibility, performance, and release seal

- Added stable screen semantics and 48 dp minimum touch targets across the adaptive shell and primary workflows.
- Qualified shared headers, metrics, long filenames, empty/error states, and modal flows at 200% font scale.
- Added Compact, Medium, Expanded, Add-modal, and current-product instrumentation contracts plus a connected-device smoke runner.
- Persisted transient Downloads, Add, Media, Library, Activity, and navigation state through rotation and modal dismissal where appropriate.
- Enforced lazy Developer planner composition and consumer-safe source scans that reject debug language, raw machine values, full secret-bearing URLs, and command templates in normal UI.
- Expanded Devtool, CI, final-gate, clean-install, upgrade, external-handoff, accessibility, and recovery qualification while preserving routes, Room schema 14, app version, engines, and runtime behavior.

## UIX R5 - Activity, Settings, and Developer boundary

- Refocused Activity on Needs attention and Recent, with plain-language consequences, compact metrics, and a Manage activity sheet for queue decisions, queues, schedules, and recovery.
- Reordered Settings around save location, smart queue, notifications, appearance, privacy, support, and About, while retaining power-user settings on secondary pages.
- Added persisted dark/AMOLED theme selection and Developer options, disabled by default.
- Moved runtime probes, engine matrices, media planners, dispatch and worker dashboards, privacy audits, validation checks, intake diagnostics, and redacted logs into a grouped gated workspace.
- Kept the support report available with Developer options off and redacted clipboard URLs inside developer diagnostics.
- Preserved Room schema 14, app version, routes, queue and schedule operations, recovery semantics, Termux, aria2, proxy, automation, rules, and external handoff.

## UIX R4 - Media and Library consumer workflows

- Rebuilt Media as a consumer-first review workspace with Paste page URL, private-session guidance, common quality chips, complete video/audio/subtitle selection, estimated size, explicit download, and compact recently queued progress.
- Rebuilt Library as a playable-first adaptive list/grid with All, Video, Audio, and Recently added filters plus clear Play, Resume, Retry, More, open-file, and record-removal actions.
- Removed resolver history, runtime plans, Termux controls, telemetry, worker bridges, sidecar JSON, privacy audits, validation decks, and routine Media3 diagnostics from normal user surfaces.
- Kept redacted playback support details hidden until an actual player error.
- Preserved Room schema 14, app version, routes, engines, queue behavior, resolver persistence, recovery, external handoff, and developer diagnostics.

## UIX R3 - Downloads and Add workspace

- Rebuilt Downloads as a transfer-first adaptive workspace with compact metrics, Active/Queued/Finished/All filters, contextual queue notices, on-demand search, flat transfer rows, and long-press selection.
- Added a persistent expanded list-detail layout and adaptive compact detail sheet with useful information first and redacted technical details folded.
- Moved archive, sort, bulk selection, tags, saved searches, history, and activity links into Organize downloads.
- Rebuilt Add as a two-step Review download -> Add to queue flow with folded advanced settings and explicit non-queueing media inspection.
- Preserved Room schema 14, app version, routes, engines, external handoff, queue behavior, recovery, and developer diagnostics.

## UIX R2 - Flat dark adaptive shell

- Added a dark-first, flat, zero-elevation Android visual system with semantic status colors and shared responsive primitives.
- Added explicit compact, medium, and expanded window classes with bottom navigation below 840 dp and a persistent 224 dp sidebar at 840 dp and above.
- Reduced visible navigation to Downloads, Media, Library, Activity, and Settings while preserving Add as an internal restorable route presented through an adaptive modal.
- Added safe-drawing and IME-aware edge-to-edge layout handling, bounded expanded content width, accessibility semantics, and shell contract tests.
- Preserved Room schema 14, app version, all download engines, external handoff, queue behavior, recovery, and developer diagnostics.

## UIX R1 - Surface contract and modularization

- Split the Android UI monolith into feature-owned sources while preserving public composable APIs.
- Added User, Advanced, and Developer audience contracts.
- Removed phase dashboards, planner telemetry, worker bridges, runtime adapters, privacy audits, and validation decks from normal Media and Library workflows.
- Preserved those redacted diagnostics lazily in the existing developer diagnostics workspace.
- Added package-aware architecture tests and the UIX R1 surface-boundary validator.

## XDM Android Phase 8E Gradle architecture-contract repair — 2026-07-25

- Rebased stale source-shape assertions onto the downloader-first Phase 8A–8E implementation without weakening behavior contracts.
- Fixed external Add draft state-flow, dashboard ordering, Activity/Library ownership, route restoration, filename inference, and archived-browser checks.
- Made retired-browser directory checks tolerate an empty filesystem directory while still rejecting any active browser contract files.
- Preserved the cumulative Compose scope, storage-action, and JUnit 4 compilation repairs.

## XDM Android Phase 8E cumulative compile repair — 2026-07-25

- Superseded the rolled-back Compose/storage hotfix with a cumulative overlay that applies directly to the Phase 8E activity-diagnostics tree.
- Kept the Compose scoped-weight and deprecated storage-action repairs.
- Fixed `OperationalActivityTest.kt` to use the core-model module's configured JUnit 4 API instead of the undeclared `kotlin.test` library.
- Added static and JVM regression coverage for the module-compatible test imports.

## XDM Android downloader experience: Phase 8E

- Turned Activity into a unified operational timeline for transfers, queue decisions, handoffs, verification, finalization, and recovery.
- Added searchable Timeline, unresolved Attention, explainable Decisions, bounded transfer-transition retention, and privacy-safe diagnostics export.
- Added Downloads health links for attention and policy holds while preserving queues, schedules, recovery, diagnostics, all engines, the Phase 8D resolver, Room schema 14, `versionCode 21`, and `versionName 0.20.0-rc08`.
- Kept the built-in browser absent and external browser handoff intact.

## XDM Android downloader experience: Phase 8D

- Promoted the Media destination into a first-class resolver workspace with explicit source, probe, stream, selection, review, ready, failed, and protected states.
- Added rich format comparison for resolution, codec, container, bitrate, duration-based size estimates, HDR evidence, compatibility, efficiency, quality, and compactness guidance.
- Added persistent video, audio, and subtitle choices outside Room, plus redacted session review and recent resolution history derived from downloader media captures.
- Preserved review-first queue handoff, Phase 8C policy, native/aria2/Termux/yt-dlp engines, browser-free routes, Room schema 14, `versionCode 21`, and `versionName 0.20.0-rc08`.

## XDM Android downloader experience: Phase 8C

- Added an explainable queue policy gate for validated network, network type, charging, battery, storage reserve, schedules, per-queue concurrency, priority fairness, and classified retry backoff.
- Moved automatic condition-driven execution into a foreground WorkManager worker so background evaluation does not spawn a second foreground service.
- Added condition-change monitoring, persistent secret-free decision history, and an explicit Start anyway action for soft policy overrides.
- Preserved native, aria2, Termux, resolver, recovery, library, Media3, six-route navigation, Room schema 14, `versionCode 21`, and `versionName 0.20.0-rc08`.

## XDM Android downloader experience: Phase 8A + 8B

- Added a pure review planner for manual and external Add Download intake with URL normalization, semantic classification, readiness steps, and explicit direct-versus-media choice.
- Added explicit clipboard URL detection and manual Inspect in Media without auto-probing or auto-queueing.
- Rebuilt Downloads as a grouped control center for Needs attention, Active, Queued, Completed, and History.
- Added smart section ordering and actionable authentication, storage, permission, verification, network, recovery, and retry guidance.
- Preserved all downloader engines, six-route topology, Room schema 14, `versionCode 21`, and `versionName 0.20.0-rc08`.

## XDM Android built-in browser removal: Phase 7 final seal

- Sealed XDM Android as a downloader-only product with an authoritative architecture contract.
- Removed the remaining generic HTTP/HTTPS/FTP navigation intent filter so XDM is not offered as a normal browser.
- Preserved share-sheet, typed MIME, file-extension, Android browser download-manager, media resolver, and review-first intake paths.
- Added permanent static, JVM, and PackageManager contracts for browser absence, Room schema 14, stable navigation, and downloader-engine preservation.
- Added Android-test APK compilation to the final Gradle and CI gates without changing `versionCode 21`, `versionName 0.20.0-rc08`, or Room schema 14.

# Unreleased

## XDM Android built-in browser removal: Phase 6

- Consolidated the Android shell into Downloads, Add, Media, Library, Activity, and Settings.
- Promoted the offline media library and playback diagnostics into a focused Library destination.
- Folded Queues, Scheduler, Recovery, and Diagnostics into an Activity workspace without removing their actions.
- Made Add Download globally reachable through the floating action button and migrated persisted legacy route names safely.
- Preserved external handoff, Room schema 14, version 0.20.0-rc08, and every native, aria2, Termux, worker, resolver, queue, library, and playback component.

## XDM Android built-in browser removal: Phase 5

- Removed browser-only persistence terminology, dormant mobile browser state, active Phase 18 and Phase 37-50 browser contracts, and their obsolete validators.
- Preserved capture quality as browser-neutral `MediaCaptureQuality` with grouping, confidence scoring, noise suppression, refresh labels, and redacted diagnostics.
- Replaced browser-profile privacy auditing with transient external page-context auditing.
- Kept the WebKit-free `browser-integration` module because it receives external browser shares and Android download actions.
- Archived a concise non-contractual browser history while keeping Room schema 14, version 0.20.0-rc08, and every downloader engine unchanged.
## XDM Android built-in browser removal: Phase 4

- Removed `BrowserScreen`, `BrowserActivity`, `AppRoute.Browser`, the browser launcher, and generic browser-owned HTTP/HTTPS VIEW handling.
- Removed browser startup URL state and Android WebKit runtime wiring from the app shell.
- Preserved `ExternalAddDownloadActivity`, share-sheet/ClipData intake, download-manager actions, Add Download classification, and explicit media inspection.
- Retired Phase 18 and Phase 37-50 browser-runtime validators from the active Android CI and final release gate; Phase 5 later consolidates their history into one non-contractual archive.
- Kept Room schema 14, version 0.20.0-rc08, native/aria2/Termux execution, queue workers, media resolver, offline library, and Media3 playback unchanged.

## XDM Android built-in browser removal: Phase 3

- Classified external handoffs as direct files, direct media, HLS/DASH, torrents, or page/unknown links without starting transfers.
- Preserved MIME type, content length, page context, and safe source labels through the review-first Add Download flow.
- Added an explicit Inspect as media action that seeds the existing resolver and yt-dlp workbench without auto-probing or auto-queueing.
- Added page-probe records for reviewed HTTP/HTTPS pages while keeping raw headers redacted.
- Kept BrowserActivity, WebView, routes, manifest filters, Room schema, transfer engines, and app version unchanged.

## XDM Android built-in browser removal: Phase 2

- Extracted browser-neutral download intake drafts, URL policy, and media request facts.
- Rewired the temporary browser to emit neutral review and media contracts instead of browser-shaped ViewModel methods.
- Kept all download execution review-first and left browser runtime removal deferred.

## XDM Android built-in browser removal: Phase 0 and Phase 1

- Added a machine-readable browser/downloader ownership inventory without removing runtime code.
- Added preservation contracts for external share/view handoff, ClipData intake, review-first download prompts, URL normalization, and secret redaction.
- Locked native, aria2, scheduler, media resolver, queue, worker, Termux, offline-library, diagnostics, and Media3 playback source contracts before browser extraction.
- Recorded the stale pre-Phase-37 browser validator mismatch and repaired Phase 49/50 validator paths in the final-gate script.
- Kept Android version metadata, Room schema, production Kotlin, routes, activities, and manifest behavior unchanged.

## 0.11.0-alpha01

- Added Phase 11 media manifest resolution for HLS and DASH captures.
- Added persisted media variant rows and selected-variant state in Room schema v11.
- Added variant quality labels and Media-route variant selection without adding a new top-level route.
- Added manifest expiry/refresh metadata so stale playlist captures are resolved before download.
- Added JUnit media regression coverage for HLS/DASH variant selection.

## XDM Android 0.8.0-alpha01

## 0.10.0-alpha01

- Added Phase 10 media capture detection for shared browser URLs, direct media links, HLS playlists, and DASH manifests.
- Added persisted media capture metadata and schema v10 media_captures storage.
- Added real Media-route actions to download or remove captures without adding a new top-level route.
- Added recovery-safe media capture wiring and JUnit regression coverage.


Adds Phase 8 checksum verification, persisted verification results, trusted block manifests, and selective repair planning.

# Changelog

## 0.12.0-alpha01

- Added durable automation command records for Tasker, browser, share, and view intents.
- Added idempotency keys to prevent duplicated downloads and duplicated media captures.
- Added Tasker pause/resume-all action contracts.
- Bumped Android Room schema to 12.


## 0.9.0-alpha01

- Added Phase 9 startup recovery scanning and recovery actions.
- Added schema v9 finalization-journal metadata and deterministic promotion stages.
- Added recovery UI actions without changing Android topography.


## XDM Android 0.7.0-alpha01 — 2026-07-17

### Added

- Added a live backend capability matrix and explainable automatic selection using protocol, destination, authentication, expiry, mirrors, expected size, metering, and previous host performance.
- Persisted requested and selected backends, recommendation reasons, explanations, and fallback policy in Room schema v7.
- Added pre-start-only fallback. Backend failures after task creation never jump engines.
- Added journaled native-to-aria2 and aria2-to-native migration with transactional ownership generation transfer.
- Added source task retirement, physical artifact inspection, distinct target preparations, and recovery-required failure states.
- Added Settings history and compatible migration actions while preserving the Android topography contract.

### Safety

- Cross-backend partial files are never interpreted by another engine.
- Existing source artifacts are preserved when migration restarts from zero.
- Unavailable or destination-incompatible migration controls are not presented.

## 0.6.0-beta01

- Completed the on-device aria2 backend with durable Room-to-GID mappings and every task operation.
- Added paused-before-ownership activation, authenticated event polling, session reconciliation, orphan/conflict handling, and provisional completion promotion.
- Added database schema v6 and migration coverage.
- Added official ARM64 runtime installation, ELF validation, SHA-256 attestation, CI packaging, and exact APK payload verification.


## XDM Android 0.6.0-alpha01 — 2026-07-16

### Added

- Added the Phase 6B embedded aria2 runtime foundation for ARM64 Android packages.
- Added APK-native executable discovery and ARM64 ELF validation through `ApplicationInfo.nativeLibraryDir`.
- Added a supervised shell-free aria2 process with ephemeral loopback RPC, a random per-installation secret, authenticated JSON-RPC, session persistence, bounded shutdown, and unexpected-exit handling.
- Added distinct `.xdm.aria2.part` artifact identities and preserved the Phase 6A ownership quarantine boundary.
- Added a Diagnostics smoke probe that starts aria2, authenticates RPC, saves the session, and shuts it down.
- Added runtime, RPC authentication, and lifecycle regression tests plus a Phase 6B static contract validator.

### Security

- aria2 is never copied into writable app storage and RPC is never bound beyond loopback.
- Short-lived launch configuration files are owner-only and deleted after RPC readiness.
- RPC secrets are redacted from object rendering and failure messages.

### Deferred

- Production aria2 task creation remains disabled until durable GID mapping, polling, and process-death reconciliation are implemented.

## XDM Android 0.5.1-alpha01 — 2026-07-15

### Changed

- Replaced synthesized partial ownership keys with backend-prepared physical artifact identities.
- Added stable backend instance identities, per-process session identities, and Room schema v5 persistence.
- Added startup reconciliation, quarantine classifications, and generation-safe artifact adoption.
- Prevented startup from releasing stale claims before the owning backend has validated its task and artifacts.
- Prepared the ownership boundary required before the embedded aria2 backend can write transfer data.

## 9.0.0-preview.1 — 2026-07-11

First modern Avalonia preview from the `Mikeyphw/xdm` fork.

### Added

- .NET 10 and Avalonia 12 desktop application for Linux and Windows
- Resumable HTTP/HTTPS download engine with validated range requests
- Crash-safe state checkpoints and finalization recovery
- Batch downloads, request metadata, categories, queues, scheduler, concurrency, and speed limits
- Authenticated browser capture and native-host installation/repair
- Direct media, HLS, and DASH probing
- Structured diagnostics, redacted bundle export, safe mode, and recovery tools
- Single-instance activation, tray/background mode, notifications, search, filters, bulk actions, and timeline
- Self-contained Linux x64/ARM64 and Windows x64 packaging
- Modern-only Linux/Windows CI, package qualification, final parity certification, and large-history performance checks
- Full browser takeover, HLS/DASH media acquisition, conversion, completion actions, migration, file management, localization, and accessibility
- FTP/FTPS transport with resume and TLS-protected data channels
- Bounded PAC proxy rules, integrated enterprise proxy authentication, 120 device profiles, and verified update staging

### Changed

- `app/XDM/XDM.Modern.sln` is the supported solution
- WPF, GTK, WinForms, MSIX, and .NET Framework projects are no longer part of active builds

### Product scope and preview boundaries

- Verified update packages are staged in-app but never executed automatically
- macOS is outside the maintained Linux/Windows modernization scope
- Adobe HDS remains a documented stale upstream claim because no retained working parser exists
- Browser extension store distribution and signing remain release-channel tasks
- Linux desktop integration may vary between desktop environments
- Retain a backup before migrating legacy state even though recorded migration fixtures are qualified
