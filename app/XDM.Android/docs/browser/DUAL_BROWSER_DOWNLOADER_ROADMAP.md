# Dual Browser + Downloader Roadmap

Phase 37A records the product split before runtime work begins.

## Product shape

XDM will become a dual-surface Android app:

- **XDM Downloader**: downloads, queues, scheduler, recovery, storage finalization, diagnostics, and Add Download.
- **XDM Browser**: a first-class browser surface for browsing, capture review, direct file download prompts, and media discovery.

The Browser must not remain hidden behind the Media screen. The Downloader must remain a focused transfer cockpit.

## Sequencing

- **Phase 37B**: dual launcher and navigation split.
- **Phase 38**: browser reliability foundation that fixes white-screen loading, start page, loading progress, error states, SSL/network diagnostics, URL/search normalization, and external-open fallback.
- **Phase 39**: browser chrome, tabs, history/bookmark basics, and reachable controls.
- **Phase 40**: browser privacy/data model, private tabs, cookie profiles, clear data, and redacted handoff rules.
- **Phase 41**: browser download bridge for direct files with Add Download prompt and no silent auto-queue by default.
- **Phase 42**: SuperX-style media capture cockpit with found-media review and variant selection.
- **Phase 43**: browser library surfaces: history, bookmarks, page resources, captured downloads, import links.
- **Phase 44**: browser settings and power-user controls.
- **Phase 45**: visual polish for Browser and Downloader surfaces.
- **Phase 46**: optional Firefox/IronFox integration module spec.

## Safety rules

- No copied proprietary 1DM code, assets, layouts, strings, or resources.
- SuperX may guide open architecture patterns, but XDM implementation remains original.
- No silent auto-queue by default.
- No DRM bypass.
- No raw shell exposure.
- No durable raw Cookie, Authorization, Bearer, token, or session persistence.
- Browser handoff into downloader stays user-visible and redacted.
- Every runtime phase must keep Android lint clean.

## Overlay discipline

Phase 37A is docs/contracts only. Runtime work starts in Phase 37B so failures stay small and inspectable.
