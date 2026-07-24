# Phase 39: Browser Chrome + Navigation

Phase 39 builds on the Phase 38 reliability foundation. Phase 38 made the Browser visible and recoverable; Phase 39 makes it feel like a browser instead of a reliability test harness.

## Scope

Runtime UI changes are intentionally narrow:

- promote browser chrome state into a `BrowserChromeState` model
- expose current page title and location above the address/search field
- gate Back and Forward controls from WebView navigation state
- add Home, Reload, Stop, and Add URL controls
- intercept system back inside Browser when WebView can go back
- keep the existing tab and history groundwork, without adding bookmark/history redesign yet

## Product posture

XDM now has two first-class app surfaces:

- **XDM Downloader**: transfers, queues, scheduler, recovery, diagnostics, Add Download
- **XDM Browser**: address/search, page navigation, visible reliability states, capture tray, Add URL handoff

Phase 39 does not turn Browser into a full bookmark/history manager. It focuses on the basic chrome controls users expect before we add deeper browser library surfaces.

## Browser controls

The Browser top card now owns the core chrome:

- Back
- Forward
- Home
- Stop while loading
- Reload when idle
- Add URL for the current page
- current title
- current URL/location
- address or search input

The Browser keeps Phase 38 recovery states: loading progress, visible WebView errors, HTTP errors, SSL blocking, blank-page detection, Retry, Open externally, and Add URL recovery.

## Navigation state

`BrowserNavigator` remains the WebView boundary, but now exposes `snapshot()` and `stopLoading()`. The snapshot records:

- current URL
- current title
- canGoBack
- canGoForward
- loading state
- progress

`EmbeddedBrowser` emits this snapshot through `onNavigationChanged` during progress, title, page-start, page-finish, and error callbacks.

## System back behavior

When Browser has WebView history, system back goes to the prior browser page instead of immediately jumping to Downloads. When there is no WebView back stack, the app-level shell behavior still applies.

## Deferred

Phase 39 intentionally defers:

- bookmark redesign
- history library redesign
- page resources list
- file-type interception rules
- full SuperX-style media capture cockpit
- extension integration

Those remain later phases so chrome/navigation can land cleanly.

## Safety invariants

- no new top-level route
- no Room migration
- no version bump
- no transfer engine changes
- no media execution changes
- no silent auto-queue
- no raw cookie/header/token persistence
