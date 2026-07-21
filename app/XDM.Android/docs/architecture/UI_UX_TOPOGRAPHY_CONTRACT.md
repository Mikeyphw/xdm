# XDM Android UI/UX Topography Contract

This document is authoritative for all XDM Android UI work. Human contributors and AI agents must preserve these rules unless a change explicitly updates this contract and the related tests in the same commit.

## Route Topography

The stable top-level routes are:

- Downloads
- Add
- Queues
- Scheduler
- Media
- Recovery
- Diagnostics
- Settings

Mobile layouts must keep primary work areas in the bottom bar: Downloads, Queues, Scheduler, and Media. Add, Recovery, Diagnostics, and Settings belong in the overflow menu unless a future contract revision promotes them. When an overflow route is selected, the overflow affordance must expose selected state through visible styling and accessibility text.

Expanded layouts may expose all top-level routes in a navigation rail. Future features must extend one of the existing routes by default. Adding a new top-level route requires updating this contract and the route contract tests.

## Interaction Rules

All visible interactive controls must perform a real action. Do not ship placeholder buttons, clickable chips, or menu items with empty handlers. If an operation is not implemented yet, render it as a non-clickable status label or omit it.

Android back from secondary routes must return to Downloads. It must not exit the app from Add, Recovery, Diagnostics, Settings, Queues, Scheduler, or Media.

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

## Secondary Route Operational Rules

Queues and Scheduler are management surfaces, not read-only dashboards. Queues must expose create, edit, enable or disable, and delete controls inside the Queues route. The default queue may be protected from deletion, but the UI must explain the disabled action through its enabled state rather than shipping a placeholder.

Scheduler must expose create, edit, enable or disable, delete, queue selection, human-readable condition editing, and a next eligible window summary. It must continue to store scheduler conditions through the existing model while never rendering raw JSON as the primary UI.

Media cards must emphasize origin, selected quality, and download readiness before technical URLs. Variant selection belongs in an explicit selector area with clear selected state and variant details.

Recovery cards must lead with the user consequence and safe recommendation. Artifact paths and IDs belong behind technical details, and destructive-looking actions must clarify whether they only remove a recovery record or also affect files.

## Browser and Share Handoff Rules

XDM must be discoverable as an Android download target from browsers that delegate downloads through typed `ACTION_VIEW` intents. The app manifest must keep plain HTTP(S) view handling and also advertise typed HTTP(S) view handling with a MIME wildcard so browser-provided download intents can resolve to XDM.

Shared text and browser handoffs must never fall through to a normal cold-launch experience. Media URLs may open the Media route when stream metadata is detected. Ordinary HTTP(S) URLs must open the Add route with the URL prefilled, preserving user review before the transfer starts.

The ShareSheet intake path must extract URLs from `EXTRA_TEXT`, `EXTRA_SUBJECT`, or the first ClipData text item before rejecting the handoff. Rejections should be visible in Diagnostics, but supported links must navigate to the relevant user workflow.
