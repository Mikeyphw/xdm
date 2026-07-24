# Reference Study: 1DM and SuperX

This study is a product and architecture reference ledger only.

## 1DM / 1DM+

Use 1DM as a topology reference:

- A downloader surface and a browser surface are both first-class.
- Browser and downloader are linked by explicit Add/Download prompts.
- Clipboard/import/browser-download flows are visible user actions.
- The browser is not a buried WebView behind a media inbox.

Do not copy proprietary 1DM implementation details, resources, strings, layouts, icons, or code.

## SuperX

Use SuperX as an open-source media-capture reference:

- Browser-led media discovery.
- HLS/DASH/MP4/audio capture review.
- Found-media decision sheet before download.
- Clear split between browsing, capture review, active downloads, and completed library.

Do not merge proxy chains, adblock, encrypted DNS, or unrelated power-user systems into the browser foundation phases. Those are separate feature branches.

## XDM interpretation

XDM already has a WebView-backed browser implementation, but it is currently buried inside Media and has user-reported blank/white-screen behavior. The roadmap therefore separates two concerns:

1. **Topography**: make Browser a first-class app surface.
2. **Reliability**: make Browser load pages, show start/error states, and fail gracefully.

Phase 37A seals the roadmap. Phase 37B starts the topology split. Phase 38 fixes browser reliability.
