# Phase 42: Browser Media Capture Cockpit

Phase 42 turns the Browser's quiet media tray into a visible capture cockpit. It follows the open-source SuperX product lesson: media discovered while browsing should become an obvious review surface, not a hidden inbox counter.

## Scope

- Show a prominent `Media found` surface inside Browser.
- Group captures by source kind: HLS, DASH, progressive, direct file, audio, video, and unknown.
- Show a selected capture card with title, type, variant count, MIME/container/codecs, and diagnostic guidance.
- Provide explicit actions: `Download selected`, `Resolve variants`, `Review media`, and `Add page URL`.
- Preserve Phase 41's direct-file download bridge for WebView download events.
- Keep live, expiring, unknown, and protected-media hints review-first.

## Safety posture

Browser media capture stays user-visible and review-first. Phase 42 does not silently queue media, does not bypass protected media, and does not persist raw cookies, tokens, authorization headers, or bearer credentials.

## Deferred

- Full history/bookmarks/library redesign.
- Page resource screen.
- Per-site media permission model.
- Browser extension/IronFox integration module.
- Transfer engine changes.
- Room migration or schema changes.

## Validation

The Phase 42 validator requires the Browser media cockpit, capture grouping, variant summary cards, explicit selected-download action, resolve/review actions, protected/live diagnostic copy, Phase 41 direct download bridge preservation, and no new top-level routes.
