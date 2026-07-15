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
