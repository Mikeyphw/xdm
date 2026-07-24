# XDM Android Phase 37B Dual Launcher and Navigation Split Overlay

This overlay starts the runtime Browser + Downloader split after the Phase 37A roadmap seal.

## Scope

- Adds Browser as a first-class top-level route.
- Adds XDM Browser as a separate launcher activity.
- Labels MainActivity as XDM Downloader.
- Keeps Add to XDM external receiver from Phase 36.
- Promotes Browser into compact primary navigation.
- Removes the hidden Browser chip from the Media screen.
- Adds Phase 37B docs, validator, release-gate wiring, CI wiring, and ArchitectureContractTest coverage.

## Deferred to Phase 38+

- White-screen WebView reliability fix.
- URL/search startup loading for BrowserActivity VIEW links.
- Browser error pages, start page, loading diagnostics, SSL/network failure UI.
- Full chrome/tabs/bookmarks/history/resource list.

## Non-goals

- No Room migration.
- No version bump.
- No transfer-engine changes.
- No media execution changes.
- No copied proprietary 1DM code/assets/resources.
