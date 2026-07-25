# XDM Android UI/UX Topography Contract

This document is authoritative for all XDM Android UI work. Human contributors and AI agents must preserve these rules unless a change explicitly updates this contract and the related tests in the same commit.

## Route Topography

The stable top-level routes are:

- Downloads
- Add
- Media
- Library
- Activity
- Settings

Compact layouts keep Downloads, Media, Library, Activity, and Settings in the bottom bar. Add remains a first-class route and is always reachable through the global floating action button. Expanded layouts expose all six routes in a navigation rail.

Library owns completed media, playback readiness, sidecar health, resume, and retry. Media remains the external media intake, resolver, track-selection, and execution-planning workbench.

Activity consolidates the previous Queues, Scheduler, Recovery, and Diagnostics destinations through visible sub-navigation. Those capabilities remain operational but no longer compete as separate top-level routes. Persisted legacy route names must restore to Activity, while unknown or removed route names fall back to Downloads.

Future features must extend one of these routes by default. Adding or removing a top-level route requires updating this contract and the route contract tests in the same change.

## Interaction Rules

All visible interactive controls must perform a real action. Do not ship placeholder buttons, clickable chips, or menu items with empty handlers. If an operation is not implemented yet, render it as a non-clickable status label or omit it.

Android back from secondary routes must return to Downloads. It must not exit the app from Add, Media, Library, Activity, or Settings.

Permission prompts must be contextual. Notification permission is requested when the user starts a transfer, not on cold launch.

Forms must be scrollable and keyboard-safe. Any form that can exceed a compact phone viewport or be covered by the IME must use vertical scrolling and IME padding.

Filename input is optional when XDM can infer a safe name from the URL. User-provided filenames override inferred names.

Long download names must not hide or crowd primary row actions. Download list rows must preserve pause, resume, retry, and future context affordances under long text.

## Content Rules

User-facing UI copy must describe the current app state or available action. Do not mention internal phase names, milestones, roadmap language, or implementation status in product UI.

Empty states must be actionable or explanatory. They should tell the user what is absent and, when possible, what action creates content.

Diagnostics may use technical language, but they must remain user-facing and must not expose secrets, cookies, authentication headers, or private full URLs.

## Future Phase Rules

New aria2, recovery, scheduling, storage, media, Tasker, diagnostics, and protocol-lab UI must respect this topography. New work should prefer adding panels, detail screens, dialogs, or sub-navigation inside existing routes instead of adding top-level destinations.

Every future phase that changes navigation or major screen behavior must add or update Compose tests and source contract tests. The tests are part of this contract.

## Visual Language Rules

Runtime screens must use the shared XDM UI primitives for typography, status, and spacing instead of ad hoc bold text or raw Material defaults. The app theme must install `XdmTypography`, and screen code should prefer `XdmSectionHeader`, `XdmCardTitle`, `XdmSupportingText`, `XdmMetadataText`, `XdmMetricText`, and `XdmStatusBadge` for reusable hierarchy.

User-facing enum values must be translated through UI labels. Do not render raw enum names such as `RecoveryRequired`, `RequiresRefresh`, `Sha256`, or backend identifiers directly in cards, chips, copied summaries, or accessibility descriptions. State, verification, checksum, backend, media, recovery, filename-conflict, and migration values must use readable labels and, where status is visible, a semantic status tone.

Numbers that update during transfers, such as bytes, speeds, percentages, and counts, should use the metric text role so live updates do not make the layout flicker.

## Downloads Scanability Rules

The Downloads route must prioritize the transfer list. Summary, history, search, filter, and sort controls must remain compact enough that download rows are visible quickly on a compact phone. History management belongs behind a visible tool affordance, not as a permanent card above the list.

Download cards should show the scan-critical row first: file name, readable state badge, backend label, progress, speed, and the primary pause/resume action. Verification, source URL, destination URI, backend migration, copy actions, and history removal belong in a details area so each row does not become a miniature control room.

The list must support text search and sort choices. Empty results caused by filters or search must explain how to recover from the narrow result set.

## Form and Settings Workflow Rules

The Add route must present the common path first: URL, optional filename, destination, recommendation, and a persistent bottom action. Existing-file behavior, backend overrides, fallback, and checksum verification are advanced options and must stay folded by default.

Settings must make deferred-save sections explicit. Proxy and post-processing drafts must show saved versus unsaved state, expose real save actions, and provide a reset path. Import/export must remain user-facing, secret-safe, and clear about what is ready to import.

## Activity and Library Operational Rules

Activity is an operational workspace, not a read-only dashboard. Its sub-navigation must expose Overview, Queues, Schedule, Recovery, and Diagnostics with visible selected state.

Queues must expose create, edit, enable or disable, and delete controls. The default queue may be protected from deletion, but the UI must explain the disabled action through its enabled state rather than shipping a placeholder.

Schedule must expose create, edit, enable or disable, delete, queue selection, human-readable condition editing, and a next eligible window summary. It must continue to store scheduler conditions through the existing model while never rendering raw JSON as the primary UI.

Recovery cards must lead with the user consequence and safe recommendation. Artifact paths and IDs belong behind technical details, and destructive-looking actions must clarify whether they only remove a recovery record or also affect files.

Library must prioritize completed-media scanability, playback readiness, missing-file and sidecar health, safe retry/resume actions, and Media3 handoff. Media cards must emphasize origin, selected quality, and download readiness before technical URLs. Variant selection belongs in an explicit selector area with clear selected state and variant details.

## Browser and Share Handoff Rules

`docs/architecture/DOWNLOADER_PRODUCT_CONTRACT.md` is authoritative for product identity and intent ownership.

XDM must be discoverable as an Android download target when browsers delegate a typed MIME or file-extension-specific download intent. It must not claim ordinary HTTP or HTTPS navigation and must not appear as a general browser choice.

XDM has no built-in browsing surface. Shared text and explicit external handoffs must never fall through to a normal cold-launch experience. Media URLs may open the Media route when stream metadata is detected. Reviewed URLs must open Add or Media with the candidate prefilled; no handoff may silently start a transfer.

The ShareSheet intake path must extract URLs from `EXTRA_TEXT`, `EXTRA_SUBJECT`, or the first ClipData text item before rejecting the handoff. Rejections should be visible in Diagnostics, but supported links must navigate to the relevant user workflow. Externally shared HLS, DASH, progressive video, and audio requests must be captured into the Media route instead of starting surprise downloads.


## Phase 7 Termux Bridge Rules

- Termux support must appear inside Diagnostics and Settings, not as a new top-level route.
- The Android app may launch only typed XDM commands through Termux RUN_COMMAND.
- Do not add a raw shell textbox or arbitrary root command endpoint.
- Root mode defaults to Off and represents policy only until a typed privileged action is implemented.
- Chroot support is intentionally excluded from the Android product surface.

## Phase 8 Termux aria2 Cockpit Rules

- Settings must include a Termux aria2 backend card that is disabled by default and can rotate its RPC secret while the daemon is stopped.
- Diagnostics must include a Termux aria2 cockpit card with start, stop, probe, task refresh, pause-all, resume-all, session-save and copy-diagnostics actions.
- Termux aria2 controls must be typed actions, never a raw shell text box.
- The cockpit must not add a new top-level route; it lives under Diagnostics and Settings.
- Root must remain optional and must not be required for Termux aria2 daemon control.


## Phase 9 Termux Media Pipeline Rules

- The Media route may expose Termux-powered media actions, but they must remain typed actions, not a raw shell surface.
- yt-dlp metadata extraction, yt-dlp download, FFprobe inspection, and FFmpeg conversion must be discoverable from captured media cards.
- The route must show a pipeline summary, recent jobs, and a copyable diagnostics payload.
- Root must stay optional and chroot support must stay absent.

## Phase 10 Optional Root Mode Rules

- Root mode remains off by default and is never required for the Termux bridge, aria2 cockpit, or media pipeline.
- Root operations must be typed actions such as root probe, process diagnostics, stuck aria2 termination, permission repair, or completed-file move. XDM must not expose a raw root shell.
- Medium-risk root actions require root mode to be enabled and a successful root probe before launch.
- Every root action must create a visible audit record and be included in copyable Termux diagnostics.
- Root actions must be launched through the existing Termux RUN_COMMAND bridge and remain chroot-free.

## Phase 11 Post-processing Automation Rules

- Post-processing automation must be visible from Settings and Diagnostics.
- Media captures must expose preview and run actions for matching rules.
- The UI must show enabled rules, recent events, and failures without exposing raw shell commands.
- Diagnostics must include copyable post-processing automation evidence.
- Root-backed post-processing must remain optional and typed.
## Phase 8A + 8B Downloader Experience Rules

Add Download must show a review-first path for both manual entry and external handoff: Link, Destination, and Review. Clipboard reading must be initiated by a visible user action. Classification must distinguish direct files, direct media, adaptive playlists, torrents, and page/unknown endpoints before submission. Media inspection must be explicit and must never queue a transfer automatically.

Downloads must group visible records into Needs attention, Active, Queued, Completed, and History in that order. Smart ordering should surface recovery work first, then active priority and throughput, queued priority, and recent completed/history records. Authentication, storage, permission, verification, network, recovery, and retry guidance should explain the next safe action without exposing secrets.


## Phase 8C queue policy UX

The Activity workspace owns queue policy status, schedules, recent decision history, and explicit evaluation. Downloads may show policy holds and a **Start anyway** action, but the action must explain that only soft constraints are bypassed and a validated internet connection is still required.

Schedule editing exposes network type, charging, minimum battery, storage reserve, retry strategy, and retry limit without creating another top-level destination. Queue policy status is supplemental to the existing Downloads dashboard, not a replacement for transfer state, recovery, or diagnostics.
