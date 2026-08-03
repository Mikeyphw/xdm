# Android Bug Hunt Remediation Roadmap

Source: `/home/mike/Downloads/ChatGPT-Android_app_bug_hunt.json`

Scope: Android app only. This roadmap turns every reported audit finding into trackable remediation work. The ordering favors security, data-loss prevention, transfer correctness, recovery, then UI/release polish.

## Exit Criteria

- No exported Android entry point can mutate downloads without a trusted user-mediated or authenticated path.
- Download state changes are serialized per download and survive process death, reboot, package replacement, and scheduler cancellation.
- File publication is transactional for app-private files, MediaStore, SAF, and backend-specific artifacts.
- Verification happens before publication and works for `content://` destinations.
- Browser/session handoff preserves the data required for legitimate downloads without leaking secrets.
- UI actions and labels match real behavior in every transfer state.
- Release CI builds, signs, verifies, installs, upgrades, and publishes a real Android release artifact.

## Phase 0: Triage Infrastructure

1. Create a bug-hunt tracking epic with one ticket for every checklist item in this file.
2. Add a regression-test tag, for example `BugHuntRegression`, so repaired findings can be queried.
3. Add an Android test fixture layer for fake `ContentResolver`, fake MediaStore, local HTTP server, fake Termux result receiver, fake WorkManager stop, and process-death recovery.
4. Convert source-marker validators into secondary checks; executable tests must be the primary release signal.

## Phase 1: External Control, Secrets, And Privacy

### 1.1 Lock Down Exported Control Surfaces

- Fix unauthenticated exported actions: `ADD_URL`, `CAPTURE_MEDIA`, `PAUSE_ALL`, and `RESUME_ALL`.
- Stop trusting caller-provided `originPackage`; derive caller identity from Android APIs where possible.
- Move automation/control actions into a dedicated component with explicit trust policy.
- Require confirmation for untrusted Add URL / Capture Media handoffs.
- Require an approved-package, token, nonce, or user-created integration secret for Tasker-style automation.
- Keep internal pause/resume/cancel actions non-exported or permission-protected.
- Add tests proving an unrelated app cannot enqueue, pause, resume, or capture.

### 1.2 Protect URLs, Cookies, Authorization, And Diagnostics

- Preserve required browser auth for legitimate downloads in an encrypted, scoped session envelope.
- Bind stored credentials to host, download ID, expiry, and attempt generation.
- Delete session material after terminal state or expiry.
- Never persist signed URLs, cookies, or bearer credentials unencrypted in Room.
- Prevent Cookie and Authorization headers from being sent over cleartext HTTP unless explicitly approved for that host.
- Redact diagnostics structurally, not with heuristic string replacement.
- Make "safe to copy" checks conservative and test-backed.
- Stop clipboard/share actions from exposing full bearer URLs unless the UI explicitly warns and the user confirms.
- Avoid placing full signed URLs in custom-scheme intents where possible; pass opaque IDs or short-lived handoff records.

### 1.3 Narrow File Sharing

- Replace broad FileProvider `<root-path path=".">` exposure with specific roots used by XDM.
- Add merged-manifest tests for exported components, authorities, backup settings, cleartext policy, `debuggable`, `testOnly`, permissions, and custom schemes.

## Phase 2: Download Execution Correctness

### 2.1 Make Execution Ownership Single And Durable

- Ensure only one execution owner can start a download at a time.
- Make runtime job registration atomic.
- Serialize Pause, Resume, Cancel, Retry, Redownload, and Start Now per download.
- Make WorkManager own or explicitly pause/cancel the actual transfer job when stopped.
- Make JobScheduler stop wait for pause/cancel completion or transfer ownership to a foreground owner.
- Prevent foreground service destruction from orphaning an active transfer.
- Keep foreground ownership through aria2 verification and final promotion.
- Add tests for concurrent manual starts, worker cancellation, service destruction, and scheduler stop.

### 2.2 Repair Retry, Resume, And Backend State

- Make Retry create a real new network attempt instead of reusing a failed backend task.
- Make Failed Retry preserve necessary request/session/destination metadata while clearing failure-only state.
- Flush a final native checkpoint on explicit Pause.
- Validate resume using durable validators; fail closed when validators disappear.
- Prevent zero-byte segmented checkpoints from launching several full downloads.
- Prevent completed-but-incomplete segment metadata from generating invalid Range requests.
- Honor known expected length when a server omits response length.
- Cancel sibling HTTP calls promptly after a segment failure.
- Remove completed OkHttp calls from `activeCalls` in per-call `finally` blocks.
- Respect `Retry-After` and server backoff guidance.
- Fix inflated speed calculations after resume.

### 2.3 Align HTTP Probes With Payload Requests

- Create one shared request builder for HEAD, range probe, page probe, and payload GET.
- Apply the same browser-like defaults and caller headers to probe and payload requests.
- Prevent engine-owned headers such as `Range` from being accepted through external handoff.
- Reject HTTP 200 HTML/error pages as completed media unless explicitly expected.
- Add local HTTP-server tests for header parity, forbidden missing-header payloads, range refusal, HTML error pages, and authenticated retries.

## Phase 3: Storage, Publication, Verification, And Repair

### 3.1 Transactional Destination Publication

- Replace destructive SAF/MediaStore overwrite with temp-sibling or pending-row publication.
- Copy, fsync where supported, verify size/checksum, then atomically replace or publish.
- Preserve the previous destination if replacement fails.
- Do not mark MediaStore publication failure as Completed.
- Model MediaStore commit details in the finalization journal, including committed URI.
- Include attempt generation in journal identity.
- Ensure actual production backends write and consume finalization journals.
- Make RecoveryRequired able to resume through finalization stages.

### 3.2 Verify Before Publication

- Validate checksum input instead of silently normalizing or changing it.
- Enter visible Verifying state while checksum work runs.
- Verify staging artifacts before publication.
- Support checksum verification for `content://` destinations through `ContentResolver` streams.
- Avoid thousands of Room writes during checksum progress; throttle progress persistence.
- Avoid repeated full-file reads for multiple checksum consumers.
- Preserve verification history instead of overwriting it.
- Confirm the file remains stable between verification and publish.

### 3.3 Fix Storage Addressing And Capacity

- Split `Download.destinationUri` into separate concepts, such as selected destination spec/root and completed content/file URI.
- Preserve the original destination after completion.
- Use exact MediaStore `RELATIVE_PATH = ?` matching with normalized trailing slash, not `LIKE "Download%"`.
- Prevent direct document URIs from overwriting unintended documents.
- Reconcile completed rows with the real file at startup and on details/open actions.
- Restore settings only when URI permissions are still available; otherwise require re-grant.
- Preflight both staging space and final destination space.
- Report staging-space peak requirements for SAF/MediaStore.
- Clean empty staging directories after successful content downloads.
- Run real write-probe health checks, not string-only destination checks.
- Harden filenames for Android provider limits and shell/post-processing contexts.
- Use and expose calculated destination atomicity instead of discarding it.
- Document Android 8/9 app-private download uninstall behavior.

### 3.4 Repair And Cleanup Safety

- Make selective repair fail if the server ignores Range.
- Keep repair credentials and request identity from the original attempt.
- Repair into a temporary artifact, then verify and promote; never patch the only file in place.
- Detect extra trailing data during trusted-block repair.
- Fail closed on malformed trusted manifests.
- Wire selective repair into production only after the above guarantees exist.
- Ensure "clear finished history" removes or retains partial artifacts according to explicit policy.
- Expand recovery scanning to all partial, staging, journal, aria2, and temporary artifact types.
- Make Android deletion recoverable where provider APIs support trash/pending-delete flows.

## Phase 4: Queue, Scheduling, And State Machines

### 4.1 Queue Semantics

- Enforce queue constraints on active downloads, not only on new starts.
- Add a global concurrency and bandwidth budget.
- Make manual Start Now respect queue concurrency limits.
- Make "Pause all" atomic and durable.
- Make disabling a queue pause or stop current execution according to explicit policy.
- Add a normal UI path to assign downloads to custom queues.
- Delete queues transactionally or prevent deletion while downloads reference them.
- Prevent dangling queue references.

### 4.2 Scheduling And Retry Timing

- Validate schedule times; invalid windows must fail closed with user-visible errors.
- Replace approximate scheduler timing with alarms/work scheduling that can hit short windows.
- Define deterministic behavior for overlapping schedules.
- Schedule retry deadlines instead of only recording them.
- Tie retry attempts to failure generation, not row update time.
- Treat unknown battery state conservatively or show "unknown" instead of passing constraints.
- Make immediate reevaluation events durable or coalesced without loss.

### 4.3 Recovery After Process Death, Reboot, And Update

- Replace startup recovery, reboot recovery, and package-replacement recovery with one idempotent transaction.
- Prevent cancelled downloads from resurrecting as Paused.
- Prevent completed downloads from becoming RecoveryRequired.
- Prevent recovered aria2 tasks from running while UI says Paused or RecoveryRequired.
- Make aria2 Resume immediately crash-durable.
- Preserve browser-authenticated resume data across process recreation where policy allows.
- Persist notification control commands before execution.

## Phase 5: Browser Handoff And Media Sniffing

### 5.1 Browser Extension To Android Handoff

- Deliver cookies, Authorization, Referer, frame URL, tab URL, MIME type, content length, content disposition, and other non-secret metadata through a protected channel.
- Add delivery acknowledgment from Android back to the extension.
- Support sending/opening all detected candidates, not only one.
- Update existing captures when refreshed session data arrives for the same URL.
- Avoid permanent deduplication of refreshed sessions.
- Treat user-selected backend as a preference, not a guarantee.

### 5.2 Page And Media Sniffing

- Make Paste Page URL actually sniff the page.
- Make Inspect All inspect page URLs as well as direct media.
- Make Check Again resolve HLS and DASH manifests.
- Preserve iframe request context instead of replacing it with the top-page URL.
- Make DRM detection consistent across direct, page, HLS, DASH, and extension-captured candidates.
- Do not suppress failed or hidden offers as successful no-ops.
- Authenticate or validate page-observation events so they cannot be forged.
- Allow the Android page probe to inspect streams safely more than once.
- Do not strip authenticated headers from page probes.
- Keep page metadata from contaminating extracted media candidates.
- Replace broad substring filters with typed candidate classification so legitimate media is not filtered out.
- Avoid monkey-patching global browser APIs unless isolated and reversible.
- Fix tab removal so it deletes stored headers/session data by the correct key.

### 5.3 Backend Selection And Fallback

- Consolidate the two backend-selection systems or define their responsibilities clearly.
- Stop planning progressive media for aria2 when execution is forced to Native.
- Make the default Android destination compatible with the chosen backend or expose why aria2 is unavailable.
- Support FTP/SFTP destination constraints explicitly; do not present impossible default-destination paths.
- Allow fallback after task-creation/preparation failures where safe.
- Include preparation failures in fallback decisions.
- Support browser-authenticated direct files with aria2 only when credentials can be passed safely.
- Define unified fallback behavior for yt-dlp and FFmpeg jobs.
- Feed the smart selector the runtime facts it claims to consider.
- Correct Native backend capability claims to match implementation.
- Preserve browser session requirements during backend migration.
- Prevent recovery from recommending impossible backends.

## Phase 6: Database Integrity And Migrations

- Add foreign keys or explicit transactional cleanup for every download-linked table.
- Make download deletion complete and transactional across metadata, progress, queues, media captures, variants, automation, journals, aria2 ownership, and recovery records.
- Replace whole-row upserts that can overwrite newer state with targeted updates or compare-and-swap writes.
- Fix the 5-to-6 migration so aria2 mappings are preserved or deliberately migrated with recovery markers.
- Extend migration tests through schema 14: 1->14, 5->14, 9->14, 13->14, and every adjacent migration.
- Open migrated databases with the real Room schema.
- Make automation command deduplication atomic.
- Do not apply durable permanent deduplication to Pause All and Resume All unless an explicit retry nonce is provided.
- Keep media captures and variants consistent under transactional updates.
- Harden malformed enum handling so one bad value cannot break database flows.
- Replace delimiter-fragile portable settings parsing with structured encoding.

## Phase 7: Post-Processing And Termux Integration

- Connect download/media events to post-processing automation automatically.
- Persist event claims by subject ID, attempt generation, trigger, rule ID, and action ID.
- Route Termux completion by `runId` back to the owning media job, automation event, capture, download row, and output artifact.
- Persist post-processing ownership so process death does not lose Running/Queued jobs.
- Add pause, cancel, timeout, and progress for post-processing runs.
- Bridge Android `content://` inputs into Termux-readable files or verified shared paths.
- Import post-processing outputs back into Android storage through MediaStore/SAF.
- Write outputs transactionally and verify before replacing or exposing them.
- Keep credentials out of Termux command lines, shell history, logs, and process listings.
- Use yt-dlp metadata and FFprobe output to update media variants and UI.
- Make cleanup, move, rename, chmod, and verification operate on the correct artifact.
- Require an expected checksum for "Verify SHA-256" or hide the action.
- Merge the two unrelated post-processing settings systems.

## Phase 8: Download Actions And UI Truthfulness

### 8.1 Three-Dot Menu Actions

- Rename "record" language to user-facing terms such as "history item" or "download entry".
- Make Start Now start queued downloads instead of routing through pause.
- Hide or disable Pause during Verifying/Repairing unless it works.
- Make Open Location open the containing folder/location, or rename it to Open File.
- Implement Rename and Refresh Link before showing them as enabled actions.
- Prevent removing an active item from racing with runtime updates and reappearing.
- Make Delete Record delete the complete record graph.
- Make Delete File + Record resolve ambiguous URIs safely.
- Move deletion storage I/O off the UI thread.
- Report deletion success only after file and record deletion complete.
- Make Redownload preserve original settings, headers, backend preference, queue, destination, checksum, and post-processing rules where appropriate.
- Keep selected item context for Review Recovery, Locate File, and Restart.
- Rename Copy Path to Copy URI where applicable, or provide a real display path when available.
- Disable queue move actions when movement is impossible.

### 8.2 List, Details, Filters, And Labels

- Stop labeling every queued item "Next in queue"; calculate actual queue order.
- Replace "Tap to retry/resume" with labels that match the actual control.
- Stop calling every policy hold a network problem.
- Present Completed as verified only when checksum/content verification actually succeeded.
- Distinguish byte progress from overall completion that includes verification/finalization.
- Align Active filter terminology and metrics with actual active states.
- Do not categorize Paused downloads as "Up next".
- Hide raw Android destination URIs in list and details; show friendly storage labels with an explicit copy-URI action when needed.
- Do not use action words as status badges.
- Prevent stale speed from overriding terminal or paused state.
- Clear or reconcile details selection when the selected item is absent from the current list.
- Add a primary Completed action such as Open, Share, or Show in Downloads.
- Make Resume preservation claims based on validated partial artifact state, not byte counters only.
- Remove unsupported blanket claims like "Request data: Protected and redacted".
- Ensure "Copies the public URL only" cannot copy bearer credentials.
- Redact Copy File Information.
- Hide post-processing actions for Failed/Cancelled items unless a valid input artifact exists.
- Make recovery button copy match the operations they perform.
- Validate backend migration availability before offering it.
- Add Cancel to active details.
- Make storage information specific enough to diagnose the file, URI, provider, and permissions.

## Phase 9: Accessibility And Adaptive Layout

- Rework Expanded breakpoint behavior so sidebars and two-pane downloads never collapse the list below usable width.
- Base adaptive layout decisions on width, height, window size class, fold posture, and available pane width.
- Add pane semantics and focus management for sheets, dialogs, and detail panes.
- Remove duplicate announcements from reusable primitives.
- Ensure icon actions announce labels once.
- Remove nested duplicate controls from media variant rows.
- Add large-font tests for risky surfaces, including list rows, details, menus, sheets, post-processing, and media variants.
- Fix Settings switches that create duplicate non-actionable focus nodes.
- Ensure adaptive-sheet content is scrollable at short heights and large font scales.
- Ensure embedded player controls remain accessible and are not hidden/compressed.
- Define keyboard, D-pad, and switch-access traversal order.
- Add Compose screenshot/semantics tests at phone, split-screen, 840 dp threshold, tablet, landscape, and large-font configurations.

## Phase 10: Release, Upgrade, Packaging, And Publication

### 10.1 Release CI And Signing

- Update Android CI to run `lintRelease`, `testReleaseUnitTest`, `assembleRelease`, `apksigner verify`, APK manifest inspection, aria2 payload verification, and artifact upload.
- Stop Debug-only CI from searching for Release APKs it did not build.
- Make the final public release gate run Gradle build/test/lint/signature checks, not only static validators.
- Fix stale Phase 34, 35, 36, and 65 validators.
- Require release signing for publishable builds.
- Provide a deliberately named unsigned development variant if needed.
- Make signature verification mandatory; fail when `apksigner` is missing in release mode.
- Pin expected signer certificate SHA-256 and verify certificate continuity.
- Stop reporting `releaseSigningConfigured = !BuildConfig.DEBUG`; derive release readiness from artifact/signing attestation.
- Remove hardcoded in-app release claims unless backed by generated validation results.

### 10.2 Release Dependencies, Variants, And APK Contents

- Pin the aria2 runtime archive digest in the manifest or verify a signed upstream release plus pinned digest.
- Test release-specific behavior with release lint and release unit/instrumentation tests.
- Decide and document the R8/minification policy; either enable and qualify R8 or explicitly ship unminified.
- Scope `jniLibs.keepDebugSymbols` to the required aria2 payload or replace it with controlled packaging.
- Define supported ABIs and test each one; explicitly handle unsupported ABIs.
- Prove 16 KB native alignment on the actual Release APK.
- Inspect merged release manifest for security and packaging invariants.

### 10.3 Upgrade, Backup, And Publication

- Increase Android `versionCode` for every distributable artifact.
- Add a human-readable build ID or commit hash to diagnostics.
- Add automated clean-install tests.
- Add previous-release-to-candidate upgrade tests with retained downloads, queues, settings, SAF grants, MediaStore links, captures, interrupted tasks, and recovery state.
- Test downgrade rejection.
- Replace competing package-replacement recovery paths with the shared recovery transaction from Phase 4.
- Add backup-policy tests proving sensitive databases, signed URLs, headers, and debug sessions are not backed up.
- Document "updates preserve data; uninstall/reinstall does not" if backup remains disabled.
- Add Android release publication workflow for APK/AAB, checksum, signer certificate details, merged manifest, mapping file, native symbols, SBOM, provenance, and attestation.
- Sign or attest checksums so an APK and checksum cannot be replaced together.

## Phase 11: Validation Matrix

Required executable regression coverage:

- Untrusted external apps cannot mutate downloads.
- Tasker Pause All and Resume All work repeatedly.
- Probe and payload requests receive equivalent default/request headers.
- Failed SAF/MediaStore overwrite preserves the old file.
- Worker cancellation stops or pauses the real transfer.
- MediaStore exact-path lookup ignores nested directories.
- Completed OkHttp calls leave the active set.
- Browser-authenticated recovery after process death is explicit and usable.
- Staging-space exhaustion fails before the download begins.
- Retry performs a new network attempt.
- Two execution owners cannot run one download.
- Finalization journal recovery works for app-private, MediaStore, and SAF destinations.
- Cancel/Pause during checksum and finalization cannot later become Completed incorrectly.
- Selective repair refuses servers that ignore Range.
- Queue concurrency holds under rapid manual starts.
- Invalid schedules fail closed.
- Retry deadlines trigger at the intended time.
- Database migrations pass every adjacent and long-path version to schema 14.
- Deletion removes the complete graph or fails transactionally.
- Post-processing runs automatically, survives process death, and imports outputs.
- UI action matrix matches queued, connecting, downloading, paused, failed, verifying, repairing, finalizing, completed, cancelled, and recovery-required states.
- Accessibility tests pass at large font, TalkBack semantics, keyboard/D-pad, split-screen, landscape, and tablet widths.
- Release CI builds, signs, verifies, installs, upgrades, and publishes the release artifact.

## Suggested Work Order

1. Security gate: exported actions, caller identity, secret handling, cleartext policy, FileProvider roots.
2. Data-loss gate: transactional publication, checksum-before-publish, deletion graph, storage path correctness.
3. Runtime gate: single execution owner, cancellation ownership, retry/resume correctness, active-call cleanup.
4. Recovery gate: one durable recovery transaction, process-death/reboot/package-replacement behavior.
5. Browser/media gate: handoff session persistence, media sniffing correctness, backend fallback.
6. Queue gate: concurrency, schedules, retry timing, custom queue assignment.
7. Post-processing gate: durable automation, Termux result routing, content URI bridge.
8. UI gate: truthful actions, labels, details, menu behavior, accessibility, adaptive layout.
9. Release gate: signed release CI, upgrade tests, migration tests, publication artifacts.

## Definition Of Done Per Finding

Each roadmap item is done only when:

- The implementation is merged.
- At least one executable regression test covers the original failure mode.
- The UI copy or developer docs reflect the actual behavior.
- The release gate or CI workflow runs the relevant test.
- The issue is linked back to this roadmap item and marked complete.
