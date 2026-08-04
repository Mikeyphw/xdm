
## XDM Android bug hunt Phase 10 release gate

- Adds signed APK/AAB release gate, signer pinning, APK-set verification hooks, aria2 runtime attestation, 16 KB native-alignment checks, backup/device-transfer exclusion validation, install/upgrade/reboot/downgrade smoke scripts, and signed/attested publication metadata.
- Bumps Android metadata to versionCode 22 / versionName 0.21.0 for a distributable Phase 10 candidate.


## Bug Hunt Remediation Phase 4 - Queue, Scheduling, And State Machines

- Added queue state-machine models for start-only, ongoing, and drain-only constraints.
- Added atomic queue-slot reservation, global concurrency/bandwidth budget modeling, durable Pause All holds, fail-closed schedule-window validation, failure-generation retry deadlines, and queue deletion anti-dangling plans.
- Added system stop-reason records for WorkManager and user-initiated JobScheduler execution, including Android 16 pending-job reason and history fields for final diagnostics.
- Added typed recovery operations, concrete artifact identities, and typed blocked outcomes so Recovery Doctor flows cannot silently convert unsafe records into blind queue starts.
- Added idempotent terminal-notification records, recovery review routing, Dismiss copy, and notification-permission denial state.

## XDM Android Bug Hunt Remediation Phase 1 r4 — External Control, Secrets, and Privacy

## Android Bug Hunt Phase 2 - Download Execution Correctness

- Serialized transfer execution ownership with per-download command generations.
- Added durable Pause/Cancel intent before backend lookup so controls are not lost during backend preparation.
- Made failed backend retry create a fresh attempt instead of re-observing an old failed task.
- Hardened WorkManager, UIDT JobService, and foreground-service stop paths to pause or preserve live work.
- Hardened aria2 pause/resume/cancel/remove so ownership is released only after confirmed RPC/session persistence.
- Hardened native HTTP resume with validator disappearance checks, effective-URL validation, `If-Range`, completed-segment normalization, expected-length enforcement, request-header parity, retry-after/backoff, active-call cleanup, and HTML/compression rejection.


- Supersedes the failed Phase 1, Phase 1 r2, and Phase 1 r3 overlays against the pre-Phase-1 baseline.
- Keeps the complete Phase 1 implementation plus r2 query-redaction precision fixes and r3's JVM-safe cleartext-policy boundary.
- Fixes `:scheduler:testDebugUnitTest` by aligning `CompletedNotificationOpenFileContractTest` with the narrowed FileProvider architecture: `OpenDownloadedFileActivity` delegates URI ownership checks and grants to `CompletedFileGrantPolicy`.
- Keeps production and instrumented Android behavior on the real platform `NetworkSecurityPolicy`, while avoiding local unit-test false failures in native segmented, retry, checkpoint, and content-range tests.

## XDM Android Bug Hunt Remediation Phase 1 r2 — External Control, Secrets, and Privacy

- Rebuilt the complete Phase 1 overlay after `:core-model:test` exposed query over-redaction: public parameters such as `quality=1080` were incorrectly replaced alongside credential values.
- Redacts only structurally identified credential query names, including percent-encoded names and common signed-URL key forms, while preserving public fields and avoiding substring false positives such as `author` and `monkey`.
- Added focused regressions across durable URL persistence, release diagnostics, and Debug Workbench export redaction.
- Moved exported automation into a dedicated review/authentication surface; launcher `MainActivity` no longer accepts public queue-control or download-mutation actions.
- Added one-use internal dispatch capabilities, a user-created integration secret verifier, explicit caller identity separation, and confirmation for untrusted or private-network handoffs.
- Added encrypted Android Keystore request envelopes bound to subject, host, expiry, attempt generation, and explicit private/cleartext approvals; durable Room records retain only redacted URLs and summaries.
- Added restrictive production network security, HTTPS-to-HTTP credential stripping/blocking, private/loopback target guards, sensitive clipboard handling, backup/device-transfer exclusions, persistence scrubbing, and independent support redaction.
- Narrowed completed-file FileProvider roots and validates completed-artifact ownership before issuing grants.
- Added JVM, Android instrumentation, migration, source-contract, and embedded Devtool validation for the complete remediation phase.


## XDM Android Phase 65 Diagnostic Export / Download Action Fix

- Added Android share-sheet export actions for redacted support and runtime self-test reports.
- Runtime self-test exports now include the check IDs that actually ran, such as `media-sniffer`, `redaction`, and `support-report`.
- Fixed download list three-dot actions so Cancel and Delete record / Remove from list work from the list.
- Removing an active/queued record is explicit and confirmation-gated: XDM cancels the transfer first, cleans owned backend/recovery/finalization records, then removes the list record without deleting saved files.
- Kept Room schema 14, no all-files permission, no automatic upload, no automatic transfer start, and no Debug Workbench reopening.

## XDM Android Phase64 — Final Android Downloader RC Seal

- Sealed the downloader-only Android RC track after Debug Workbench D1-D7 and Phases49-63 without changing runtime behavior, Room schema, top-level routes, storage permissions, transfer start behavior, deletion behavior, upload behavior, release criteria, or browser-removal status.
- Added a pure final RC seal planner and contract tests covering operational hardening, runtime recovery, validator harmony, real-device smoke evidence, support-bundle readiness, redacted diagnostics, signed-artifact/checksum expectations, and deferred full validation.
- Wired the Phase64 validator into the final release gates and made older overlay-pinning validators accept the final RC overlay as the current product overlay.

## XDM Android Phase63 — Release Readiness / Support Bundle Seal

- Added a support-bundle readiness seal that verifies copied diagnostics include operational context, release-security status, install/update readiness, final-release warning explanations, real-device smoke status, and privacy redaction guarantees.
- Wired the support-bundle seal into the generated support report without adding routes, schema changes, storage permissions, uploads, automatic transfer actions, or persisted browser/session/header values.
- Added Phase63 validator coverage and final-gate wiring while keeping older Phase54–Phase62 validators forward-compatible with the new current overlay.


## XDM Android Phase63 Release Readiness Support Bundle Seal r2

- Repacked Phase63 as r2 after Gradle exposed an older BrowserRemovalPhase7 contract test that loaded the full production source tree into one heap-sized string.
- Streams bounded source files during the browser-free runtime check so unit tests stay memory-safe on constrained validation workers.
- Keeps the Phase63 support-bundle release-readiness behavior unchanged.
## XDM Android Phase62 — Real-device Operational Smoke Seal

- Added a pure real-device operational smoke checklist covering browser handoff, extension media capture, authenticated 403 recovery, completed storage visibility, and Recovery Doctor partial/orphan review.
- Wired the Phase62 validator into the final release gates and made Phase54–Phase61 validators tolerate Phase62 as the later current overlay.
- Kept the seal manual and redacted: no automatic retry, no file deletion, no all-files permission, no persisted browser/session/header values, no Room migration, and no Debug Workbench reopening.

## XDM Android Phase61 — Final Gate Validator Harmony

- Updated the older UIX R3 downloads/add validator so it accepts the current Phase44 planner-backed download row instead of requiring the retired row-local `primaryRowAction` implementation.
- Preserved the Phase44 contract that forbids reviving private row-local action planning in `DownloadRow.kt`.
- Wired the Phase61 validator into the final release gates and made Phase54–Phase60 validators tolerate Phase61 as the later current overlay.
- Kept the runtime/product boundary unchanged: no Room migration, no top-level route, no all-files permission, no automatic transfer start, no deletion, no upload, and no Debug Workbench reopening.

## XDM Android Phase60 — Runtime Recovery Flow Seal

- Added a pure runtime recovery flow seal that summarizes whether recovery planning, execution guarding, action previews, and redacted reporting are all present before users act.
- Wired the Phase60 validator into the final release gates and made Phase54–Phase59 validators tolerate Phase60 as the later current overlay.
- Preserved the Phase57–Phase59 safety boundaries: no automatic retry, no automatic deletion, no all-files permission, no Room migration, no persisted session values, and no Debug Workbench reopening.

## XDM Android Phase59 — Runtime Recovery Action Transparency

- Added a redacted action-preview layer to the runtime recovery card so retry, method switch, Recovery Doctor, guidance, and report actions explain what will happen before a tap.
- Recovery reports now include a safe action-preview summary without full links, cookies, authorization values, bearer tokens, or credential-bearing query values.
- Preserved Phase58 execution-guard boundaries: no automatic retry, no automatic deletion, no all-files permission, no Room migration, and no Debug Workbench reopening.

## XDM Android Phase58 — Runtime Recovery Execution Guard

- Added a pure runtime recovery execution guard between Phase57 recovery recommendations and retry/method/recovery callbacks.
- Download details now show an Action safety summary so partial data, captured-session retry, method switching, guidance-only actions, and redacted reports have explicit reviewed behavior.
- Partial and recovery-required retries route through Recovery Doctor first; browser refresh and yt-dlp remain guidance-only; report copy stays redacted and copy-only.
- No Room migration, top-level route, all-files permission, automatic transfer start, automatic deletion, upload, release-criteria change, or Debug Workbench reopening.

# XDM Android Phase 56 Stale Copy / Architecture Noise Sweep


### Phase57 r2 Debug Workbench seal contract compatibility
- Rebased the Phase57 overlay so the D7 final-seal unit contract checks the D7 manifest block instead of pinning the global current overlay to D7.
- Updated the D7 static validator to allow later product overlays while preserving the sealed Debug Workbench boundary.

## XDM Android Phase 57 Runtime Failure Recovery UX

- Added a download-details recovery options card for failed, recovery-required, queue-held, and storage-visibility problem states.
- Added a pure RuntimeFailureRecoveryPlanner that classifies server-access, stale-session, media-resolver, storage, partial-recovery, backend-fallback, queue-policy, and generic retry cases without starting work.
- Added safe actions for refresh from browser, retry with captured session, yt-dlp/media inspection, aria2/native method switch, storage re-check, Recovery Doctor, and redacted report copy.
- Kept Room schema 14, no top-level route, no automatic deletion, no all-files permission, no automatic upload, and no Debug Workbench reopening.


- Removed stale implementation-phase wording from runtime errors and release-gate details that can surface in diagnostics.
- Replaced machine-style operational diagnostics labels such as `engine=` with human method labels.
- Humanized recovery classifications, external handoff sources/statuses, media source kinds, media intents, and sniffing sources in normal copy.
- Kept support exports redacted and actionable without changing release criteria, Room schema, top-level routes, or Debug Workbench status.


## XDM Android Phase 55 Final Release Warning Explainer

- Added a human-readable release warning explainer for final-release gate diagnostics.
- Release warnings now include impact, safe-to-ignore guidance, fix action, and owning validator/test.
- Updated support export text to include the redacted warning explanation instead of only a bare warning count.
- Preserved release criteria, Room schema 14, route topology, Debug Workbench seal, and privacy boundaries.

## XDM Android Phase 54 Engine Escalation Planner

- Added a review-only Engine Escalation Planner to external Add Download handoffs so XDM explains whether Native, aria2, or media resolver/yt-dlp is the safest next method before queueing.
- Recommends browser recapture instead of blind retry when a server requires browser access, and recommends media inspection for pages, HLS, DASH, and uncertain media handoffs.
- Recommends aria2 for large direct files without browser-session signals, while keeping Native preferred for fresh captured sign-in or expiring links.
- Shows only human method labels, reason labels, next action, and safe alternatives; raw URLs, header names, cookies, Authorization values, bearer tokens, and credential query values stay out of normal UI.
- Keeps the Debug Workbench D-series sealed: no D8, no Room migration, no top-level route, no all-files permission, no automatic upload, and no automatic transfer start.

## XDM Android Phase 53 Extension Detection Quality Gate

- Added explicit strong/possible/rejected quality buckets to the Firefox media detector so only high-confidence media is offered by default.
- Put extensionless and stream-shaped possible media behind an advanced popup toggle instead of treating generic API/player responses as videos.
- Added detection support for media filenames in `Content-Disposition`, range/length context, and stronger body-derived candidate classification.
- Updated detector diagnostics to label high-confidence versus possible media without showing cookies, Authorization values, bearer tokens, or full raw URLs in normal UI.
- Folded in the Phase52 nullable contract-test warning cleanup so the next validated baseline can return to zero diagnostics.
- Kept the Debug Workbench D-series sealed: no D8, no Room migration, no top-level route, no all-files permission, and no automatic upload.

## XDM Android Phase 52 Browser Session Health

- Added a safe Browser Session Health report for external Add Download handoffs so users can see whether browser context was captured before queueing.
- Shows source site, sign-in context, page context, browser identity, expiry risk, suggested method, and the safest next action using human labels only.
- Keeps Cookie, Authorization, token, signature, and full URL values out of normal UI, Room, sidecars, and copy reports.
- Adds model tests, UI/source contracts, documentation, and a Devtool temp-root-compatible validator.
- Keeps the Debug Workbench D-series sealed: no D8, no Room migration, no top-level route, no all-files permission, and no automatic upload.

## XDM Android Phase 51 Recovery + Storage Doctor

- Added a Recovery + Storage Doctor summary to Activity → Recovery so unresolved recovery records are grouped into resumable, missing-partial, orphaned-artifact, completed-visibility, and interrupted-finalization buckets.
- Added a validate-all-safe action that reuses the existing recovery validation path for linked downloads without deleting files or adopting orphaned artifacts automatically.
- Added a redacted recovery report export that omits raw paths, raw URLs, cookies, tokens, and Authorization values.
- Replaced expanded recovery technical details that previously exposed raw artifact paths and download IDs with human-safe artifact labels.
- Added Phase51 model, UI, contract tests, documentation, and a Devtool temp-root-compatible validator.
- Kept the Debug Workbench D-series sealed: no D8, no Room migration, no top-level route, no automatic deletion, and no automatic upload.

## XDM Android Phase 50 Operational Repair - session handoff, checkpoint recovery, and storage visibility

- Fixed the native checkpoint segment parser by escaping the closing brace in the Android runtime regex, addressing malformed-checkpoint recovery errors.
- Added process-local browser session handoff for external Add Download and media captures when Android intents provide headers, while keeping cookies and Authorization values out of Room, sidecars, normal UI, and support bundles.
- Added Referer handoff from page context and browser-like default headers for native metadata probes so ordinary servers behave more like a browser request.
- Improved HTTP 401/403 native metadata failures with a user-facing authentication/session explanation instead of only a raw probe failure.
- Strengthened MediaStore public-file visibility after completion by clearing pending state, updating modified time, notifying the resolver, and requesting a media scan.
- Kept the Debug Workbench D-series sealed: no new D phase, no Room migration, no top-level route, and no automatic upload.

## XDM Android Phase 49 Field Bugfix - Download actions, storage labels, and media sniffing

- Fixed the download item action sheet so actions no longer collapse into the download details fallback: cancel, redownload, queue movement, open/share file, delete record, and delete file plus record now have explicit dispatch paths and destructive actions stay behind confirmation.
- Made normal download details show human storage labels and hints instead of raw `content://`/`xdm://` destination values.
- Kept Android scoped-storage behavior: public MediaStore saves still use access-safe content links internally, while the UI explains the visible Downloads/Movies/Documents destination.
- Refreshed MediaStore commit metadata when clearing `IS_PENDING`, improving file-manager visibility after successful promotion.
- Hardened app-side media page probing for HTTP 401/403 by setting safe browser-like headers, checking HTTP status before reading the body, and telling the user to use extension capture when the site requires the live browser session.
- Tightened Firefox extension detection using the same high-level signals observed in the 1DM APK string scan: MIME/manifest/media extension and strong media keys count; generic JSON `url`/`src` fields and small API endpoints no longer produce video candidates.

## XDM Android Debug Workbench D7 Final Debug Seal

- Seals the complete Debug Workbench roadmap after the D6 green baseline of 414 passed, 0 failed, 0 skipped.
- Converts Debug Workbench shell UI/copy state labels from raw enum names to human-readable support labels.
- Adds a final Debug Workbench seal contract test, architecture document, and static validator.
- Wires the Phase 48 release-gate script to run the D7 final-seal validator instead of the stale D1-only validator.
- Keeps the boundary unchanged: no new route, no Room migration, no automatic upload, no runtime probes, and no transfer controls.


## XDM Android Debug Workbench D6 Runtime Self-Test Suite r2

- Fixed Runtime self-test suite UI spacing to use the existing `XdmSpacing.TightGap` token instead of a nonexistent `XdmSpacing.sm` alias.
- Added D6 validator coverage for this compile-safety regression.


## Debug Workbench D6 - Runtime self-test suite

- Adds a read-only Runtime self-test suite to Settings > Debug Workbench.
- Checks manifest route coverage, browser-scheme readiness, completed-file access path, static media sniffer smoke, redaction smoke, notification diagnostics boundary, recorder health, support report readiness, and current app state context.
- Keeps the suite read-only: no downloads, viewer launches, file probes, browser probes, network probes, database changes, or uploads.
- Adds D6 model, UI card, tests, docs, and validator.

## XDM Android Debug Workbench D4 - Browser Bridge + Add Download Debugger

- Added Browser bridge debugger in Settings > Debug Workbench with copy-only redacted status and test URI report.
- Added Add Download debugger that mirrors `DownloadReviewPlanner` for the active external draft without queueing anything.
- Added human-label-only UI rendering for debugger origins and kinds; no raw enum names or raw URLs are rendered.
- Added D4 docs, validator, app tests, and affected UI release-seal contract coverage.

## XDM Android Debug Workbench D3 Media Sniffing Lab r4

- Fixed the D3 app contract test string assertion so it compiles.
- Removed enum `.name` usage from Media Sniffing Lab UI state and rendering paths; the UI now uses private stable source keys plus human labels.
- Added validator coverage for UI release-seal compatibility.

### XDM Android Debug Workbench D2 Shell r4
- Fixed the stale Phase 47 shared-sniffer contract assertion so recorder-backed D2 construction is accepted.
- Added a D2 contract check for this assertion repair and validator coverage before packaging.
- Re-ran focused app contract tests and core-model D1/D2 tests locally with stubs before zipping.

### XDM Android Debug Workbench D2 Shell r3
- Fixed D2 app contract-test Kotlin string escaping by using raw strings for nested `next_phase`, `Debug Workbench`, and `debug-sessions` literals.
- Added validator coverage so future D2 overlays cannot ship these broken nested test strings again.


### XDM Android Debug Workbench D2 Shell r2
- Fixed the Debug Workbench screen import for the shared SettingsPageHeader helper.
- Added static validator coverage for the root-package SettingsPageHeader import so the UI compile contract matches the actual SettingsScreen package.


## XDM Android Debug Workbench D2 — Shell

- Added a Settings → Debug Workbench secondary page with live recorder/redaction/support-bundle status.
- Installed the app-wide DebugRecorderProvider backed by the bounded D1 rolling JSONL recorder.
- Wired Add Download and media planners in MainViewModel to the shared debug recorder.
- Added copyable debug status and support-report controls without automatic upload or top-level routes.
- Added D2 model/app contract tests, docs, and static validator.
## XDM Android Phase 43B - Add Download media recommendation demotion

### XDM Android Debug Workbench D1 r5

- Fixed the D1 redaction model test to assert key-preserving value redaction for both `Authorization` and `Cookie` diagnostics.
- Kept the D1 recorder foundation, support bundle skeleton, safe hooks, and Debug Workbench roadmap handoff intact.


### XDM Android Debug Workbench D1 r4

- Fixed the D1 redaction model test to assert secret value removal while preserving diagnostic key casing.
- Kept the D1 backend event recorder, Debug Workbench handoff, and Kotlin 2.3 compile-hygiene fixes intact.


- Added a pure `MediaInspectionPolicy` with Hidden, Optional, and Recommended visibility weights for Add Download media/page analysis.
- Kept HLS/DASH and browser-extension direct-media handoffs strongly recommended while demoting ordinary manual page/unknown links back to the normal Add Download path.
- Preserved a dedicated `BrowserExtension` intake origin so extension-captured media can be promoted without making manual Add Download noisy.
- Updated Add Download to use planner-owned labels and guidance, including neutral `Analyze page for media` wording for page-shaped external links.
- Added Phase 43B core-model and app contract tests plus static validation, without changing XPI packaging, download-list actions, notifications, Room schema, versionCode, or versionName.

## XDM Android Phase 43A - Browser extension FAB/detector parity repair

- Added a dependency-free browser-extension bridge self-test so the popup can prove the active top frame can host a Shadow DOM launcher before detector checks run.
- Made manual FAB injection top-frame-required and iframe-best-effort, preventing blocked child frames from breaking the user-visible launcher.
- Added popup bridge health diagnostics for self-test, bridge, handoff, FAB, page host, sniffer status, and offer counts.
- Repaired HLS/DASH network fallback so encrypted/blob/blocked playback no longer suppresses a high-confidence media FAB.
- Added focused Phase 43A static and JavaScript regression gates while leaving Add Download UX and later app-side sniffing roadmap phases untouched.

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

- Added variant-specific `xdmdownload` and `xdmdownload-debug` custom schemes owned only by `ExternalAddDownloadActivity`.
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

## Debug Workbench D1 r3 compile hygiene

- Rebuilt the D1 event recorder overlay to use `createTempDirectory` in debug recorder tests and named `DownloadIntakePlanner(idFactory = ...)` construction in legacy intake tests so the optional debug recorder parameter remains source-compatible under Kotlin 2.3.

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

## 0.6.0-release-candidate01

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
- Added stable backend instance identities, per-process session identities, and Room schema v6 persistence.
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

### Phase 42 Kotlin source compatibility hotfix

- Aligned the Media3 diagnostics call with the current `positionMs` API.
- Added the required Material 3 opt-in for the adaptive bottom sheet.
- Removed stale `ui.common` and internal Compose `weight` imports.
- Replaced K2-invalid cross-module nullable-property smart casts with stable local values.
- Added a focused source-compatibility validator to the browser bridge release gate.


### Phase 42 contract-test modernization hotfix

- Rebased the remaining Android architecture and UI contract tests on the modular UI source tree.
- Fixed Phase 41 tests to locate the Android Gradle root instead of assuming the module working directory.
- Updated Activity labels, shared theme tokens, touch-target tokens, developer gating, media track selection, and review-only intake assertions without weakening their behavior contracts.
- Preserved the already-applied Kotlin source compatibility fixes and added the modernization validator to the browser bridge release gate.


## Phase 45 — Completed notification open-file intent

- Completed download notifications now tap into a safe non-exported open-file trampoline instead of opening the app by default.
- The trampoline revalidates the download id/state, resolves the completed URI, grants temporary read access, and falls back to XDM details on missing files, lost permissions, or no viewer.
- Transfer completion persistence now keeps concrete backend completed URIs so notification taps can open MediaStore/content destinations.

## Phase 46 — Media batch intake

- Added a review-first Media batch intake panel for pasted URLs, HTML, JSON, and page text.
- Added a pure parser/planner that trims input, accepts LF/CRLF, extracts HTTP(S) URLs, rejects unsafe schemes, deduplicates normalized URLs, caps large input, and reports accepted/duplicate/invalid/page-inspection counts.
- Saves only concrete media candidates into the Media Inbox; page/watch URLs are flagged for the later shared sniffing engine instead of being silently misclassified.

## Debug Workbench D1 - Event Recorder Foundation

- Added privacy-first debug event models, redaction, bounded JSONL recording, and support-bundle skeleton.
- Added optional no-op-by-default instrumentation hooks for Add Download intake, MediaSniffingEngine, media batch intake, external media review, and completed notification fallback.
- Added D1 contract tests and validator without introducing a Room migration or user-facing UI.

## Debug Workbench D3: Media Sniffing Lab

- Added Settings → Debug Workbench → Media Sniffing Lab.
- The lab runs the shared Phase 47 `MediaSniffingEngine` in static mode over pasted URLs, HTML, JSON, or script snippets.
- Added optional base page URL, MIME hint, origin selection, candidate ranking display, and sanitized copy report.
- Preserved safety boundaries: no network page probe, no arbitrary JavaScript execution, no DRM bypass, no download enqueue, and no automatic upload.
- Added D3 media/unit tests, app contract tests, docs, manifest entry, and validator.


### XDM Android Debug Workbench D3 Media Sniffing Lab r3
- Fixed Media Sniffing Lab source chips to render human labels instead of raw enum names.
- Added validator and contract coverage for the UI release-seal raw enum rule.

## XDM Android Debug Workbench D5, Transfer + notification debugger

- Added a read-only Transfer + notification debugger under Settings → Debug Workbench.
- Explains active transfer summary, selected transfer lifecycle, backend choice, terminal notification path, completed open-file trampoline behavior, and failure labels.
- Copy report redacts source URLs and fingerprints destination URI; normal UI does not render raw URLs, raw enum names, raw machine values, or secret-bearing details.
- Preserves safety boundaries: no transfer controls, viewer launch, file probe, custom-scheme opener, Room migration, top-level route, or automatic upload.

### XDM Android Debug Workbench D6 Runtime Self-Test Suite r3
- Fixed the runtime self-test redaction smoke so it uses the existing key-aware DebugRedactor path for Authorization and Cookie values.
- Preserved the D6 read-only boundary and kept missing browser/device/session state as notes instead of failures.
- Added validator coverage for the redaction-smoke regression that caused the r2 app unit-test failure.

## Android Bug Hunt Phase 1 r4

- Supersedes Phase 1, r2, and r3 after Devtool rolled each back during focused validation.
- Fixes `CompletedNotificationOpenFileContractTest.trampolineIsNonExportedAndUsesTemporaryReadGrant` by updating the source contract to match the Phase 1 FileProvider hardening architecture: `OpenDownloadedFileActivity` delegates grant resolution to `CompletedFileGrantPolicy`, and that policy owns the narrow FileProvider root check plus temporary read grant URI creation.
- Keeps the full Phase 1 implementation from r3, including r2 query-redaction precision and the JVM-safe `NetworkSecurityPolicy` boundary.

## XDM Android Bug Hunt Phase 1 r5 validation-contract hotfix

- Supersedes Phase 1 r4 after Devtool rollback at `:app:testDebugUnitTest`.
- Preserves the full Phase 1 security/privacy implementation, r2 key-aware query redaction, r3 JVM-safe `NetworkSecurityPolicy` boundary, and r4 narrowed FileProvider grant policy.
- Restores the existing browser-removal and external-download handoff contracts required by app unit tests:
  - `ExternalAddDownloadActivity` again reuses `MainActivity`'s Compose shell.
  - `MainActivity` again owns review-first external Add/Media handoff parsing while ignoring non-external launcher intents.
  - Custom-scheme rejection still returns before generic Sharesheet/VIEW parsing.
  - handoff MIME type, content length, page URL, subject, shared text, and ClipData are preserved for review.
- Keeps Room schema at v14 because Phase 1 durable request envelopes are app-private/no-backup encrypted files rather than Room tables.
- Restores the process-local handoff marker without reintroducing raw cookie persistence.


## XDM Android Bug Hunt Phase 3: Storage, Publication, Verification, and Repair

- Adds journaled destination publication contracts for filesystem, SAF, and MediaStore paths.
- Records explicit publication commit boundaries, committed URI, attempt/artifact generation, and checksum metadata fields.
- Switches MediaStore conflict lookup to exact relative-path matching and verifies publication update count, pending state, and final size.
- Adds strict checksum input parsing for user-provided SHA-256/SHA-512 values.
- Hardens selective repair so partial repairs require HTTP 206, exact Content-Range, exact length, If-Range, no trailing bytes, and temporary-artifact promotion.
- Adds Phase 3 audit documentation, app contract tests, and validator coverage.
- JSON artifact manifest includes commit_message: Phase 3: harden storage publication, verification, and selective repair.

## XDM Android Bug Hunt Remediation Phase 4 r2 - Runtime wiring hotfix

- Rebuilt Phase 4 as a runtime-wired overlay instead of a scaffold-only contract slice.
- Added `FileBackedQueueSchedulingRecoveryStore`, an app-private fsynced evidence store for queue commands, queue-slot reservations, system stop reasons, immediate reevaluations, recovery plans, terminal-notification idempotency, and queue-deletion plans.
- Wired `XdmApplication` to expose `QueueSchedulingRecoveryProvider` and install a persistent root for stop-reason records.
- Wired queue admission through `QueueStateMachinePlanner.reserveSlotAtomically(...)` and persisted each accepted or rejected reservation before claiming work.
- Wired Pause All from the UI, notification receiver, foreground service timeout/destruction path, and WorkManager stop path so the durable hold is written before `runtime.pauseAll()`.
- Replaced raw queue deletion with `deleteQueueSafely(...)`, recording reject-or-reassign plans to avoid dangling references.
- Added terminal notification `terminalIfFirst(...)` so completed/failed/recovery terminal notifications pass through a persisted idempotency gate before dispatch.
- Wired notification permission-denial state into active-notification copy.
- Extended Phase 4 validator and contract tests so a scaffold-only implementation cannot pass again.


## XDM Android Bug Hunt Remediation Phase 5

- Added browser handoff/media sniffing runtime wiring: page URL sniffing from Media, bounded prefix reads, preserved session headers, stable media session revisions, iframe-aware referer, evidence-based DRM classification, explicit media transfer shapes, and backend fallback provenance contracts.

## Bug Hunt Remediation Phase 5 r2

- Fixed invalid Kotlin raw CR/LF character literals in `MediaSniffingEngine`.
- Ensured `MediaDownloadPlanner` has a single `selectedMimeType` declaration.
- Wired browser handoff stable media ID, session revision, frame URL, proposed headers, final headers, and page-observation proof fields into Android custom-scheme and intent intake.
- Added an app-private file-backed browser handoff media session store.
- Strengthened the Phase 5 validator so syntax blockers and scaffold-only handoff markers cannot pass.

## XDM Android Bug Hunt Phase 6 - Database Integrity And Migrations

- Added transactional download graph deletion across download metadata, progress, queues, media captures, variants, automation, journals, aria2 ownership, recovery, and notification records.
- Added compare-and-swap download update hook for runtime writers that must not overwrite newer state.
- Added transactional media variant replacement that removes obsolete variants, updates variant counts, and invalidates disappeared selections.
- Added durable automation command states and stateful persistence mapping from legacy Accepted/Executed rows to Received/Applied.
- Fixed the 5-to-6 migration so legacy aria2 mappings are preserved as RecoveryRequired rows with LEGACY_SCHEMA evidence.
- Added Phase 6 source-contract tests and validator coverage for migration/schema integrity.

## XDM Android Bug Hunt Phase 6 r2 - Runtime Database Integrity Wiring

- Replaced raw download upsert writes with stale-write guarded repository saves backed by `upsertDownloadPreservingNewerState`.
- Wired automation commands through `Received/Accepted -> Claimed -> Executing` before MainViewModel side effects and terminal updates.
- Routed queue reassignment deletion through the transactional `reassignQueueThenDelete` helper instead of manual row-by-row saves.
- Hardened stored enum parsing for downloads, backends, destination health, media captures, variants, saved searches, and automation records.
- Strengthened Phase 6 tests and the embedded validator so marker-only implementations cannot pass.

- Hardened backend ownership/migration store enum parsing so malformed persisted backend evidence cannot crash model conversion.

## Phase 10 r2 release gate honesty closure

- Fixed Phase 10 Kotlin contract compilation, Room schema 17 readiness reporting, signer-attestation wiring, pinned aria2 digest enforcement, truthful ARM64-only ABI scope, APK/AAB manifest/signature/page-size verification, publication SBOM/provenance stubs, and clean-install upgrade smoke coverage.
