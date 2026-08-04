# Android Bug Hunt Remediation Roadmap

Source: `/home/mike/Downloads/ChatGPT-Android_app_bug_hunt.json`

Scope: Android app only. This completed roadmap turns every reported audit finding from the full Android audit series into trackable remediation work. The ordering favors security, data-loss prevention, transfer correctness, recovery, then UI/release polish.

## Exit Criteria

- No exported Android entry point can mutate downloads without a trusted user-mediated or authenticated path.
- Download state changes are serialized per download and survive process death, reboot, package replacement, and scheduler cancellation.
- Long user-started transfers, automatic work, and foreground execution use the Android execution primitive appropriate to their origin, duration, interruptibility, and Android 16 quota behavior.
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
5. Split the oversized Android `MainViewModel` into focused coordinators for external intake, automation, media capture, queue control, recovery, storage, and post-processing; keep the ViewModel responsible for state composition and UI delegation.

## Phase 1: External Control, Secrets, And Privacy

### 1.1 Lock Down Exported Control Surfaces

- Fix unauthenticated exported actions: `ADD_URL`, `CAPTURE_MEDIA`, `PAUSE_ALL`, and `RESUME_ALL`.
- Stop trusting caller-provided `originPackage`; derive caller identity from Android APIs where possible.
- Move automation/control actions into a dedicated component with explicit trust policy.
- Require confirmation for untrusted Add URL / Capture Media handoffs.
- Require an approved-package, token, nonce, or user-created integration secret for Tasker-style automation.
- Keep internal pause/resume/cancel actions non-exported or permission-protected.
- Add tests proving an unrelated app cannot enqueue, pause, resume, or capture.
- Treat untrusted requests to loopback, link-local, private IPv4/IPv6 ranges, local hostnames, routers, and device-control endpoints as privileged network access; block them by default or require explicit user review.
- Record claimed origin, platform-observed caller, and verified integration identity separately; never let claimed identity influence authorization.

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
- Replace global `android:usesCleartextTraffic="true"` behavior with a restrictive network-security configuration and a narrowly scoped, reviewed cleartext policy.
- Never retain Cookie, Authorization, or other sensitive headers across an HTTPS-to-HTTP redirect without a fresh explicit approval.
- Treat signed URLs and bearer-like request data as sensitive across every durable surface, not only Room: Native checkpoints, aria2 mappings, ownership JSON, finalization journals, automation command files, temporary metadata, Termux handoff files, support artifacts, and recovery records must be encrypted, redacted, expired, or removed according to the same retention policy.
- Apply an independent schema-aware sanitization pass when creating support bundles, even when events were already redacted at capture time.
- Mark sensitive clipboard entries using Android sensitive-clipboard metadata, avoid previewing bearer URLs, and optionally clear full-secret clips after a short bounded interval.

### 1.3 Narrow File Sharing

- Replace broad FileProvider `<root-path path=".">` exposure with specific roots used by XDM.
- Add merged-manifest tests for exported components, authorities, backup settings, cleartext policy, `debuggable`, `testOnly`, permissions, and custom schemes.
- Remove both broad `<root-path path=".">` and broad `<external-path path=".">` grants. Before issuing a FileProvider URI grant, canonicalize the file, prove it is inside an approved root, verify it belongs to the selected completed attempt, and reject malformed, migrated, or poisoned database paths.

## Phase 2: Download Execution Correctness

### 2.1 Make Execution Ownership Single And Durable

- Ensure only one execution owner can start a download at a time.
- Make runtime job registration atomic.
- Serialize Pause, Resume, Cancel, Retry, Redownload, and Start Now per download.
- Make WorkManager own or explicitly pause/cancel the actual transfer job when stopped.
- Classify transfers by origin, expected duration, user visibility, and interruptibility. On API 34+, use user-initiated data transfer jobs for long transfers explicitly started by the user; keep WorkManager for deferrable and safely interruptible automatic queue work, and use a direct foreground service only when its lifecycle and restrictions are explicitly satisfied.
- Account for Android 16 job-runtime quotas: long-running WorkManager workers, including workers using foreground services, must not be assumed quota-exempt; test quota exhaustion and define a durable pause, reschedule, or ownership-transfer response.
- Make JobScheduler stop wait for pause/cancel completion or transfer ownership to a foreground owner.
- Prevent foreground service destruction from orphaning an active transfer.
- Keep foreground ownership through aria2 verification and final promotion.
- Add tests for concurrent manual starts, worker cancellation, service destruction, and scheduler stop.
- Never release aria2 ownership, mappings, output files, or control files until aria2 confirms that the task stopped or was removed; on RPC failure, preserve ownership and transition to RecoveryRequired.
- Persist control intent and a monotonic control generation before backend lookup so Pause/Cancel cannot be lost during backend preparation.

### 2.2 Repair Retry, Resume, And Backend State

- Make Retry create a real new network attempt instead of reusing a failed backend task.
- Make Failed Retry preserve necessary request/session/destination metadata while clearing failure-only state.
- Flush a final native checkpoint on explicit Pause.
- Validate resume using durable validators; fail closed when validators disappear.
- Compare the checkpoint's effective URL with the newly resolved effective URL and reject object-changing redirects.
- Send `If-Range` with a strong ETag or Last-Modified validator for resumed range requests.
- Prevent zero-byte segmented checkpoints from launching several full downloads.
- Prevent completed-but-incomplete segment metadata from generating invalid Range requests.
- Honor known expected length when a server omits response length.
- Cancel sibling HTTP calls promptly after a segment failure.
- Remove completed OkHttp calls from `activeCalls` in per-call `finally` blocks.
- Respect `Retry-After` and server backoff guidance.
- Coordinate retry timing across segments with host-level exponential backoff, jitter, and a circuit breaker so rate-limited or failing hosts do not receive synchronized retry storms.
- Fix inflated speed calculations after resume.

### 2.3 Align HTTP Probes With Payload Requests

- Create one shared request builder for HEAD, range probe, page probe, and payload GET.
- Apply the same browser-like defaults and caller headers to probe and payload requests.
- Prevent engine-owned headers such as `Range` from being accepted through external handoff.
- Reject HTTP 200 HTML/error pages as completed media unless explicitly expected.
- Define explicit `Content-Encoding` behavior for byte downloads and ranges; transparent compression must not corrupt offsets, expected lengths, resumes, or checksums.
- Add local HTTP-server tests for header parity, forbidden missing-header payloads, range refusal, HTML error pages, and authenticated retries.

## Phase 3: Storage, Publication, Verification, And Repair

### 3.1 Transactional Destination Publication

- Replace destructive SAF/MediaStore overwrite with temp-sibling or pending-row publication.
- Copy, fsync where supported, verify size/checksum, then atomically replace or publish.
- After a filesystem rename or replacement, fsync the parent directory where supported so the directory entry itself survives power loss, not only the file contents.
- Preserve the previous destination if replacement fails.
- Do not mark MediaStore publication failure as Completed.
- Model MediaStore commit details in the finalization journal, including committed URI.
- Include attempt generation in journal identity.
- Ensure actual production backends write and consume finalization journals.
- Make RecoveryRequired able to resume through finalization stages.
- Journal destination commit before any post-commit metadata query; a failure after commit must reconcile the existing committed file instead of creating a duplicate on retry.
- Verify that a reconstructed aria2 destination key and final URI exactly match the persisted ownership claim before promotion.
- Persist checksum algorithm, expectation ID, expected digest, actual digest, artifact generation, and verification timestamp in finalization journals.
- Define an explicit destination-commit boundary: accept Pause/Cancel before commit, defer them while commit and Room reconciliation are in progress, and never publish Paused or Cancelled after a destination commit succeeded.
- Require a nonzero MediaStore publication update count, re-query `IS_PENDING` and final size, and recover or remove hidden pending rows after publication failure or process death.

### 3.2 Verify Before Publication

- Validate checksum input instead of silently normalizing or changing it.
- Parse exactly one unambiguous hexadecimal digest token; require 64 hexadecimal characters for SHA-256 and 128 for SHA-512, reject contaminated or ambiguous strings such as `SHA-256: ...`, and never filter characters in a way that silently changes the supplied value.
- Enter visible Verifying state while checksum work runs.
- Verify staging artifacts before publication.
- Support checksum verification for `content://` destinations through `ContentResolver` streams.
- Avoid thousands of Room writes during checksum progress; throttle progress persistence.
- Avoid repeated full-file reads for multiple checksum consumers.
- Preserve verification history instead of overwriting it.
- Confirm the file remains stable between verification and publish.
- Make checksum verification observe a durable control generation or cancellation token before, during, and after hashing; a newer Cancel request must win and verification must never publish Completed after cancellation.

### 3.3 Fix Storage Addressing And Capacity

- Split `Download.destinationUri` into separate concepts, such as selected destination spec/root and completed content/file URI.
- Preserve the original destination after completion.
- Use exact MediaStore `RELATIVE_PATH = ?` matching with normalized trailing slash, not `LIKE "Download%"`.
- Prevent direct document URIs from overwriting unintended documents.
- Reconcile completed rows with the real file at startup and on details/open actions.
- Persist explicit completed-artifact health states such as `Present`, `Missing`, `PermissionLost`, `ProviderChanged`, and `SizeMismatch`, and drive opening, sharing, recovery, and UI copy from the current health state.
- Restore settings only when URI permissions are still available; otherwise require re-grant.
- Preflight both staging space and final destination space.
- Report staging-space peak requirements for SAF/MediaStore.
- For resumed transfers, calculate required capacity from remaining bytes plus publication overhead and reserve, not from the original full object size.
- Clean empty staging directories after successful content downloads.
- Run real write-probe health checks, not string-only destination checks.
- Harden filenames for Android provider limits and shell/post-processing contexts.
- Use and expose calculated destination atomicity instead of discarding it.
- Document Android 8/9 app-private download uninstall behavior.
- On Android 8 and 9, label app-private storage as uninstall-sensitive and guide the user to select a public SAF destination instead of presenting it as an ordinary durable Downloads location.
- Use the canonical completed artifact URI directly for playback; never append a filename to an already complete MediaStore item URI.
- Make media captures, direct downloads, Redownload, and post-processing outputs honor the selected destination and destination rules instead of hardcoding Public Downloads.
- Reject raw-file path traversal after canonicalization and enforce filename limits by UTF-8 byte length as well as character count.
- Use collision-resistant staging directory identities; different malformed download IDs must not collapse to the same sanitized directory.

### 3.4 Repair And Cleanup Safety

- Make selective repair fail if the server ignores Range.
- For partial repair require HTTP 206, an exact `Content-Range`, the exact response-body length, matching remote identity, and `If-Range`; accept HTTP 200 only for a complete replacement beginning at byte zero.
- Keep repair credentials and request identity from the original attempt.
- Repair into a temporary artifact, then verify and promote; never patch the only file in place.
- Detect extra trailing data during trusted-block repair.
- Fail closed on malformed trusted manifests.
- Wire selective repair into production only after the above guarantees exist.
- Offer selective repair only when a previously trusted block manifest or independently trusted remote block manifest exists; a first checksum mismatch without trusted block evidence must require full restart or replacement.
- Ensure "clear finished history" removes or retains partial artifacts according to explicit policy.
- Expand recovery scanning to all partial, staging, journal, aria2, and temporary artifact types.
- Make Android deletion recoverable where provider APIs support trash/pending-delete flows.

## Phase 4: Queue, Scheduling, And State Machines

### 4.1 Queue Semantics

- Enforce queue constraints on active downloads, not only on new starts.
- Add a global concurrency and bandwidth budget.
- Classify each queue condition as start-only, ongoing, or drain-only, and define what happens when concurrency is reduced below the number of active transfers: continue, drain naturally, or pause excess work, with matching UI copy.
- Make manual Start Now respect queue concurrency limits.
- Reserve a queue slot atomically in the same transaction that checks concurrency so two manual or automatic claims cannot both observe and consume the same free slot.
- Make "Pause all" atomic and durable.
- Implement Pause All as a durable global queue hold that blocks new starts before pausing current work, continues processing other items when one pause fails, and covers Connecting, Downloading, Finalizing, Verifying, and Repairing.
- Make disabling a queue pause or stop current execution according to explicit policy.
- Add a normal UI path to assign downloads to custom queues.
- Delete queues transactionally or prevent deletion while downloads reference them.
- Prevent dangling queue references.

### 4.2 Scheduling And Retry Timing

- Validate schedule times; invalid windows must fail closed with user-visible errors.
- Define and enforce one-sided schedule-window semantics: either reject a start without an end and an end without a start, or implement precise documented behavior that matches the UI; never silently treat incomplete windows as all-day.
- Replace approximate scheduler timing with alarms/work scheduling that can hit short windows.
- Define deterministic behavior for overlapping schedules.
- Schedule retry deadlines instead of only recording them.
- Tie retry attempts to failure generation, not row update time.
- Treat unknown battery state conservatively or show "unknown" instead of passing constraints.
- Make immediate reevaluation events durable or coalesced without loss.
- Persist and expose Android system stop reasons for every interrupted WorkManager and JobScheduler attempt, including `WorkInfo.getStopReason()` and `JobParameters.getStopReason()` where available.
- On Android 16, record pending-job reasons and reason history so quota exhaustion, timeout, constraint loss, cancellation, app-requested stops, and scheduler deferral are distinguishable in the UI, recovery logic, diagnostics, and support bundles.

### 4.3 Recovery After Process Death, Reboot, And Update

- Replace startup recovery, reboot recovery, and package-replacement recovery with one idempotent transaction.
- Prevent cancelled downloads from resurrecting as Paused.
- Prevent completed downloads from becoming RecoveryRequired.
- Prevent recovered aria2 tasks from running while UI says Paused or RecoveryRequired.
- Make aria2 Resume immediately crash-durable.
- Require successful durable aria2 session persistence after Pause, Resume, and Cancel; when persistence fails, report RecoveryRequired rather than a false successful control result.
- Preserve browser-authenticated resume data across process recreation where policy allows.
- Persist notification control commands before execution.
- Implement one classification-aware recovery executor used by Recovery Doctor, notifications, Download Details, startup recovery, and activity surfaces.
- Route every Open Recovery Doctor action to `ActivityPanel.Recovery`, preselect the exact recovery record or download, and preserve that item context across notification, list, details, and activity navigation.
- Remove or disable `Validate all safely` until each record can be proven safe; never turn unsafe classifications into blind queue starts.
- Implement distinct operations for safe resume, remote validation, selective repair, restart from zero, orphan adoption, file location, storage recheck, and recovered-completion reconciliation.
- Make Restart From Zero remove or archive stale checkpoints, mappings, ownership claims, and attempt-specific failure state before creating a fresh generation.
- Implement Locate File with a document picker, permission grant, size/checksum validation, and transactional artifact reassociation.
- Resolve or close recovery records after successful recovery and add dismissal tombstones so `Forget record` does not immediately regenerate unchanged warnings.
- Exclude artifacts already claimed by durable backend ownership from orphan scanning.
- Deduplicate recovery execution by download ID and attempt generation when multiple records reference one transfer.
- Commit recovery classification, download state, ownership result, journal state, and recovery record in one transaction.
- Store the actual staging, checkpoint, control, output, ownership, and finalization-journal artifact identities in recovery records; never substitute the configured destination URI for an artifact pathname.
- Return typed outcomes such as Completed, NeedsUserConsent, NeedsFileSelection, Rejected, and Failed so no recovery action fails silently.

### 4.4 Notifications And Foreground Execution

- Implement one durable, idempotent notification dispatcher backed by terminal-event records rather than temporary non-replayed service collectors.
- Guarantee exactly one terminal notification per download attempt generation, regardless of whether WorkManager, JobScheduler, or a foreground service owns execution.
- Make WorkManager foreground notifications collect live per-download progress, speed, filename, phase, and total size instead of a static placeholder.
- Use per-download snapshots for user-initiated job notifications; never display global aggregate progress while controlling one unrelated item.
- Make every notification tap open the exact download, recovery case, or completed file requested by its action.
- Replace blind Retry on RecoveryRequired notifications with Review Recovery routed through the central recovery executor.
- Keep foreground ownership through backend preparation, checksum verification, repair, and destination commit.
- Prevent duplicate terminal notifications when multiple Android execution components are alive.
- For grouped notifications, expose only truthful aggregate controls such as Pause All; never Cancel an unidentified hidden primary item.
- Add durable notification-permission denial UX with an in-app warning, explanation, and settings shortcut.
- Test notification permission states including Allow, Deny, dismissed prompt, upgrade pre-grant, and previously denied upgrade. When denied on Android 13+, explain that foreground-service notices remain visible in the system Task Manager but not the notification drawer, and ensure every critical Pause, Resume, Cancel, and recovery control remains available in-app.
- Never interpret notification denial as permission to run invisibly: expose active transfer ownership and accessible controls inside XDM whenever notification-drawer actions are unavailable.
- Rename Mute to Dismiss unless a durable mute preference is implemented, and ensure future events respect the chosen policy.
- Derive Pause All, Resume All, Pause, Resume, and Cancel visibility from the actual aggregate and per-item state.
- Move completed-file provider validation and other provider I/O off the main thread.

## Phase 5: Browser Handoff And Media Sniffing

### 5.1 Browser Extension To Android Handoff

- Deliver cookies, Authorization, Referer, frame URL, tab URL, MIME type, content length, content disposition, and other non-secret metadata through a protected channel.
- Preserve one immutable request context across Native, aria2, yt-dlp, networked FFmpeg, Retry, backend migration, and selective repair, including frame Referer, Origin, Cookie, Authorization, expiry, validators, and request generation.
- Add delivery acknowledgment from Android back to the extension.
- Do not design the Firefox handoff around webpage `externally_connectable` runtime messaging, which Firefox does not support for website-to-extension communication; capability-test the actual Firefox Android/GeckoView transport and provide a supported fallback.
- Capture proposed request headers and, where supported, the final headers observed in `webRequest.onSendHeaders`; record unavailable headers honestly, because `onBeforeSendHeaders` can be incomplete and another extension can modify headers afterward.
- Add interference tests in which another extension modifies or removes request headers, and preserve iframe/request context independently of the top page.
- Support sending/opening all detected candidates, not only one.
- Update existing captures when refreshed session data arrives for the same URL.
- Avoid permanent deduplication of refreshed sessions.
- Treat user-selected backend as a preference, not a guarantee.
- Separate stable media identity from exact request URL and session revision so rotating signed tokens refresh an existing unresolved capture instead of creating permanent duplicates.
- Use deterministic expiry and oldest-first eviction for bounded session handoff storage, bind eviction to capture state, and visibly mark affected captures as Session Lost.

### 5.2 Page And Media Sniffing

- Make Paste Page URL actually sniff the page.
- Make Inspect All inspect page URLs as well as direct media.
- Make Check Again resolve HLS and DASH manifests.
- Never mark a capture Resolved from a synthetic `Primary` placeholder; require a successful network fetch and parse of the actual manifest or resolver output before setting Resolved.
- Preserve iframe request context instead of replacing it with the top-page URL.
- Make DRM detection consistent across direct, page, HLS, DASH, and extension-captured candidates.
- Make DRM/protection classification authoritative and evidence-based: use browser encryption events, parsed HLS key metadata, DASH `ContentProtection`, or resolver evidence; remove broad substring heuristics as a basis for declaring media protected.
- Do not suppress failed or hidden offers as successful no-ops.
- Authenticate or validate page-observation events so they cannot be forged.
- Allow the Android page probe to inspect streams safely more than once.
- Implement bounded reading as a loop until the byte limit, EOF, cancellation, or timeout; never assume one `InputStream.read()` fills the inspection buffer.
- Do not strip authenticated headers from page probes.
- Keep page metadata from contaminating extracted media candidates.
- Replace broad substring filters with typed candidate classification so legitimate media is not filtered out.
- Avoid monkey-patching global browser APIs unless isolated and reversible.
- Fix tab removal so it deletes stored headers/session data by the correct key.

### 5.3 Backend Selection And Fallback

- Consolidate the two backend-selection systems or define their responsibilities clearly.
- Stop planning progressive media for aria2 when execution is forced to Native.
- Replace the overloaded `isMediaRequest` Boolean with an explicit transfer shape such as `DirectFile`, `DirectMedia`, `AdaptivePlaylist`, `SiteResolver`, or `LiveRecording`, and derive backend requirements from that shape.
- Make the default Android destination compatible with the chosen backend or expose why aria2 is unavailable.
- Support FTP/SFTP destination constraints explicitly; do not present impossible default-destination paths.
- Allow fallback after task-creation/preparation failures where safe.
- Add review-first runtime migration after real execution failures such as socket exhaustion, repeated 5xx responses, range corruption, backend crash, or aria2 RPC loss; classify the failure, preserve or discard partial bytes explicitly, stop the source owner, and never switch engines blindly after bytes were written.
- Include preparation failures in fallback decisions.
- Represent backend preparation failures with typed categories such as runtime unavailable, source unsupported, destination unsupported, permission required, temporary initialization failure, and fatal configuration error; only explicitly safe categories may trigger automatic pre-start fallback.
- Support browser-authenticated direct files with aria2 only when credentials can be passed safely.
- Define unified fallback behavior for yt-dlp and FFmpeg jobs.
- Feed the smart selector the runtime facts it claims to consider.
- Correct Native backend capability claims to match implementation.
- Preserve browser session requirements during backend migration.
- Make backend migration transactional: pause or terminate the source before target writes, validate partial-artifact compatibility, keep exactly one destination owner, and roll ownership back if target preparation fails.
- Prevent recovery from recommending impossible backends.
- Derive hard transfer requirements first, filter incompatible engines, rank only the compatible engines, and persist why every rejected engine was incompatible.
- Classify failures before fallback: route 401/403 browser captures to session refresh, expired signed links to recapture, playlists to media resolution, and only backend-capability failures to engine migration.
- Persist and display complete fallback provenance: requested backend, selected backend, rejected requirement, fallback phase, trigger, bytes already written, partial-data disposition, and session data preserved or lost.

## Phase 6: Database Integrity And Migrations

- Add foreign keys or explicit transactional cleanup for every download-linked table.
- Make download deletion complete and transactional across metadata, progress, queues, media captures, variants, automation, journals, aria2 ownership, and recovery records.
- Replace whole-row upserts that can overwrite newer state with targeted updates or compare-and-swap writes.
- Fix the 5-to-6 migration so aria2 mappings are preserved or deliberately migrated with recovery markers.
- Extend migration tests through schema 14: 1->14, 5->14, 9->14, 13->14, and every adjacent migration.
- Open migrated databases with the real Room schema.
- Export every Room schema version retained for upgrade support and use `MigrationTestHelper.runMigrationsAndValidate()` for adjacent and long-path migrations, validating dropped tables, indices, foreign keys, defaults, nullability, and schema identity before opening the database through the production Room configuration.
- Make automation command deduplication atomic.
- Persist automation commands through `Received`, `Claimed`, `Executing`, `Applied`, and `Failed` states; bind every side effect to the command ID so process-death recovery can determine whether it was already applied.
- Do not apply durable permanent deduplication to Pause All and Resume All unless an explicit retry nonce is provided.
- Keep media captures and variants consistent under transactional updates.
- Replace media variants transactionally: remove variants no longer present, expire obsolete signed URLs, invalidate a selected variant that disappeared, update `variantCount`, and commit the capture and variant set together.
- Harden malformed enum handling so one bad value cannot break database flows.
- Replace delimiter-fragile portable settings parsing with structured encoding.

## Phase 7: Post-Processing And Termux Integration

- Connect download/media events to post-processing automation automatically.
- Persist event claims by subject ID, attempt generation, trigger, rule ID, and action ID.
- Route Termux completion by `runId` back to the owning media job, automation event, capture, download row, and output artifact.
- Persist post-processing ownership so process death does not lose Running/Queued jobs.
- Add pause, cancel, timeout, and progress for post-processing runs.
- Persist a PID or equivalent XDM-owned process token for every Termux run; cancellation must request graceful termination, confirm process exit, and only then offer a bounded force-stop path that cannot target unrelated Termux processes.
- Bridge Android `content://` inputs into Termux-readable files or verified shared paths.
- Import post-processing outputs back into Android storage through MediaStore/SAF.
- Write outputs transactionally and verify before replacing or exposing them.
- Keep credentials out of Termux command lines, shell history, logs, and process listings.
- Use yt-dlp metadata and FFprobe output to update media variants and UI.
- Make cleanup, move, rename, chmod, and verification operate on the correct artifact.
- Require an expected checksum for "Verify SHA-256" or hide the action.
- Merge the two unrelated post-processing settings systems.
- Preflight input staging, output staging, and final-destination capacity, including peak temporary space for audio/video merges.
- Verify FFmpeg, ffprobe, and yt-dlp availability and versions before offering actions; expose unsupported codec, container, or feature requirements before launch.
- Enforce output filename byte/provider limits and conflict policy before starting external tools.
- Make Retry Last Failed create a new durable attempt generation from the preserved immutable yt-dlp, FFmpeg, ffprobe, or automation job specification instead of merely instructing the user to reopen the item.

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
- Make Download Details expose the complete applicable completed-artifact action set: Open, Share, Open location or provider location, Copy friendly location, Copy Android URI, Delete file, and Delete file plus history, with capability-aware hiding and confirmation.
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
- Test high-contrast and color-contrast behavior for status, warning, progress, disabled, and selected states.
- Use polite live regions for meaningful phase changes and terminal outcomes while preventing continuous byte-progress updates from flooding TalkBack.
- Add explicit tests for five-item bottom navigation at 200% font, player aspect ratio and controls in compact-height landscape, focus restoration to the control that opened a sheet or dialog, and Downloads panes separated by a fold or hinge.

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
- Always provide the release keystore, alias, and passwords when `bundletool build-apks` generates installable APK sets; reject bundletool's debug-key fallback and verify every generated split APK against the pinned release certificate fingerprint.
- Sign Android App Bundles through the Gradle signing configuration or `jarsigner`, not `apksigner`; reserve `apksigner` for verifying or signing APK artifacts and verify certificate continuity across the AAB-derived APK set.
- Give every distributable build an identifiable `versionName`; verify required APK signing schemes plus signer certificate validity and expiry, and publish signer metadata with the release artifacts.
- Stop reporting `releaseSigningConfigured = !BuildConfig.DEBUG`; derive release readiness from artifact/signing attestation.
- Remove hardcoded in-app release claims unless backed by generated validation results.

### 10.2 Release Dependencies, Variants, And APK Contents

- Pin the aria2 runtime archive digest in the manifest or verify a signed upstream release plus pinned digest.
- Test release-specific behavior with release lint and release unit/instrumentation tests.
- Decide and document the R8/minification policy; either enable and qualify R8 or explicitly ship unminified.
- Scope `jniLibs.keepDebugSymbols` to the required aria2 payload or replace it with controlled packaging.
- Define supported ABIs and test each one; explicitly handle unsupported ABIs.
- Prove 16 KB native alignment on the actual Release APK.
- Verify AAB page-size configuration with `bundletool dump config` and require `PAGE_ALIGNMENT_16K`; inspect every native library in the generated APK set and test install, launch, aria2 execution, process restart, and upgrade on a 16 KB emulator or device.
- Fail the release when a directly assembled APK passes alignment but AAB-generated split or universal APKs do not.
- Inspect merged release manifest for security and packaging invariants.
- Inspect the final Release APK/AAB contents against an explicit allow/deny inventory so debug-only classes, test resources, development certificates, fixtures, source-marker payloads, internal diagnostics, or accidental support artifacts cannot ship.
- When an Android App Bundle is a publication target, run `bundleRelease`, validate the AAB with bundletool, generate installable APK sets, and execute clean-install and upgrade tests from those generated APKs.

### 10.3 Upgrade, Backup, And Publication

- Increase Android `versionCode` for every distributable artifact.
- Add a human-readable build ID or commit hash to diagnostics.
- Add automated clean-install tests.
- Add previous-release-to-candidate upgrade tests with retained downloads, queues, settings, SAF grants, MediaStore links, captures, interrupted tasks, and recovery state.
- Reboot the device or emulator after the upgrade and revalidate restored transfers, package-replacement recovery, notification behavior, durable backend ownership, SAF/MediaStore access, and absence of duplicate or restarted work.
- Test downgrade rejection.
- Replace competing package-replacement recovery paths with the shared recovery transaction from Phase 4.
- Add backup-policy tests proving sensitive databases, signed URLs, headers, and debug sessions are not backed up.
- Define explicit `data-extraction-rules` exclusions for both `cloud-backup` and `device-transfer`, because `android:allowBackup="false"` alone is not a sufficient cross-OEM device-to-device policy on modern Android.
- Exclude Room databases, checkpoints, signed URLs, cookies, authorization/session data, ownership files, Termux handoffs, recovery records, diagnostics, and other stale execution state from cloud backup and device transfer.
- Run an actual device-to-device migration test and verify that OEM transfer behavior cannot restore sensitive data or revive stale downloads, backend ownership, scheduled work, or recovery commands.
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

- Untrusted private-network and loopback URL requests are blocked or explicitly reviewed.
- Notification progress, exact-item navigation, permission denial, grouping, and terminal delivery are correct and non-duplicated.
- Recovery Doctor actions execute classification-specific behavior, unsafe Validate All is impossible, and successful recovery resolves its records.
- aria2 RPC cancellation failure preserves ownership and does not report a false terminal state.
- Reconstructed aria2 promotion cannot change the originally claimed destination.
- A post-commit MediaStore metadata failure reconciles the committed item instead of creating a duplicate.
- Completed media playback uses the canonical URI and media captures honor destination selection.
- Rotating signed media tokens refresh stable captures without producing permanent duplicates.
- Fallback classification never treats missing authentication as an engine-capability problem.
- Support-bundle export applies an independent final redaction pass.
- Sensitive clipboard entries use Android privacy metadata and bounded retention.
- Resume rejects object-changing redirects, uses `If-Range`, and handles compressed responses safely.
- Filename traversal, UTF-8 byte limits, and staging-ID collisions are rejected.
- High-contrast states and TalkBack live-region behavior pass accessibility tests.
- Post-processing refuses insufficient capacity, unavailable tools, unsupported codecs, and invalid output names before execution.

- Pause/Cancel requested during destination commit is deferred and cannot leave a committed file labeled Paused or Cancelled.
- Segmented retries honor coordinated host backoff, jitter, and circuit breaking.
- MediaStore publication requires a successful update count, clears `IS_PENDING`, verifies provider size, and recovers hidden pending rows.
- FileProvider refuses arbitrary app-readable files even when a database row contains a poisoned path.
- Recovery records identify real staging/checkpoint/control/output artifacts rather than destination configuration URIs.
- Android 8/9 app-private storage is visibly uninstall-sensitive and public SAF selection works.
- aria2 Pause, Resume, and Cancel are durably persisted or fail into RecoveryRequired.
- Retry Last Failed creates a real new Termux/post-processing attempt.
- Selective repair is unavailable without trusted block evidence and validates exact 206 range responses.
- Checksum parsing rejects contaminated, ambiguous, and wrong-length digests without changing user input.
- Resumed storage preflight uses remaining bytes plus publication overhead and reserve.
- Explicit transfer shapes drive backend compatibility, and immutable browser request context survives retry, repair, yt-dlp, FFmpeg, and migration.
- Backend migration preserves one owner and rolls back safely when target preparation fails.
- Media variant refresh removes stale variants and invalidates disappeared selections transactionally.
- Queue start-only, ongoing, and drain-only policies behave as documented when limits change during execution.
- Page probing reads repeatedly up to the bounded limit and handles short reads.
- Accessibility passes five-item navigation at 200% font, compact-height player layouts, focus restoration, and separating hinges.
- Release verification covers `versionName`, APK signing schemes, certificate validity/expiry, `bundleRelease`, bundletool validation, and install tests from generated APK sets.
- Cancel during checksum verification wins over an in-flight hash and cannot later publish Completed.
- Runtime backend migration after bytes are written is review-first, preserves one owner, and handles partial data according to the classified failure.
- Every Recovery Doctor entry point opens `ActivityPanel.Recovery` with the exact affected record preselected.
- Synthetic Primary variants cannot mark HLS/DASH captures Resolved without fetching and parsing real resolver output.
- Signed URLs and bearer-like values are protected or removed in checkpoints, mappings, ownership files, journals, command files, temporary metadata, and recovery artifacts as well as Room.
- One-sided schedule windows are rejected or execute exactly according to documented UI semantics.
- Filesystem publication syncs the parent directory after rename or replacement where the platform supports it.
- Termux cancellation targets only the XDM-owned PID/process token, confirms graceful exit, and bounds any force-stop escalation.
- Release artifact inventory rejects debug-only classes, test resources, development certificates, fixtures, and accidental diagnostic payloads.
- Previous-release upgrade tests reboot afterward and verify ownership, notifications, storage grants, and no duplicate execution.
- Pause All establishes a durable global hold before pausing every active phase and continues past individual failures.
- Queue slot checking and reservation are atomic across simultaneous manual and automatic claims.
- Download Details exposes every applicable completed-artifact action with truthful capability and confirmation behavior.
- DRM classification requires browser or parsed manifest/resolver evidence and is not based on broad substring heuristics.
- Typed backend preparation failures trigger fallback only for explicitly safe categories.
- Completed-artifact health transitions among Present, Missing, PermissionLost, ProviderChanged, and SizeMismatch drive UI and recovery correctly.

- Long user-started transfers use UIDT on supported Android versions, automatic deferrable work remains in WorkManager, and Android 16 quota exhaustion produces a durable, truthful pause/reschedule/ownership outcome.
- WorkManager and JobScheduler stop reasons plus Android 16 pending-job reason history are persisted, surfaced, and never misreported as generic network failures.
- Notification permission Allow, Deny, dismissed, pre-granted upgrade, and previously denied upgrade states preserve accessible in-app controls and match Task Manager versus notification-drawer behavior.
- Firefox handoff does not depend on unsupported webpage `externally_connectable` messaging, records final sent headers where supported, and remains correct when another extension changes headers.
- Every retained Room schema is exported and adjacent/long-path migrations pass `MigrationTestHelper.runMigrationsAndValidate()` before the production Room database opens.
- `bundletool build-apks` receives release signing inputs, every generated split APK matches the pinned release certificate, and no debug-key fallback is accepted.
- AAB configuration reports `PAGE_ALIGNMENT_16K`, all generated APK-set native libraries satisfy 16 KB requirements, and install/upgrade smoke tests pass on a 16 KB device or emulator.
- Cloud backup and device-to-device transfer rules exclude all sensitive and stale execution state, and an actual D2D test cannot restore credentials, ownership, partial jobs, or recovery commands.

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

## Official Documentation Baseline

The platform-specific requirements in this roadmap must be rechecked against the current official documentation during implementation and before release:

- [User-initiated data transfer jobs](https://developer.android.com/develop/background-work/background-tasks/uidt)
- [Long-running WorkManager workers and Android 16 quota behavior](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running)
- [Android 16 behavior changes for jobs](https://developer.android.com/about/versions/16/behavior-changes-all)
- [Auto Backup and data extraction rules](https://developer.android.com/identity/data/autobackup)
- [Notification runtime permission and foreground-service visibility](https://developer.android.com/develop/ui/compose/notifications/notification-permission)
- [bundletool signing and APK-set generation](https://developer.android.com/tools/bundletool)
- [16 KB page-size support](https://developer.android.com/guide/practices/page-sizes)
- [Room migration testing](https://developer.android.com/training/data-storage/room/migrating-db-versions)
- [Firefox `externally_connectable` support](https://developer.mozilla.org/en-US/docs/Mozilla/Add-ons/WebExtensions/manifest.json/externally_connectable)
- [Firefox `webRequest.onBeforeSendHeaders`](https://developer.mozilla.org/en-US/docs/Mozilla/Add-ons/WebExtensions/API/webRequest/onBeforeSendHeaders)
- [Firefox `webRequest.onSendHeaders`](https://developer.mozilla.org/en-US/docs/Mozilla/Add-ons/WebExtensions/API/webRequest/onSendHeaders)

## Definition Of Done Per Finding

Each roadmap item is done only when:

- The implementation is merged.
- At least one executable regression test covers the original failure mode.
- The UI copy or developer docs reflect the actual behavior.
- The release gate or CI workflow runs the relevant test.
- The issue is linked back to this roadmap item and marked complete.
