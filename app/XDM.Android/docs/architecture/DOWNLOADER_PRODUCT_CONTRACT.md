# XDM Android Downloader Product Contract

This contract is authoritative for product identity, Android intent ownership, persistence, and release qualification.

## Product identity

XDM Android is a download manager that integrates with external browsers and applications through explicit handoff, sharing, typed download intents, and review-first download intake.

XDM Android is not a general web browser. It must not embed a browsing engine, expose a browsing destination, register a browser launcher, maintain tabs or browsing history, or claim ordinary HTTP or HTTPS navigation intents.

## Stable application surface

The stable top-level destinations are Downloads, Add, Media, Library, Activity, and Settings. Add remains globally reachable. Media resolves direct media, HLS, DASH, and page-level yt-dlp probes. Library owns completed media and playback. Activity owns queues, schedules, recovery, diagnostics, and external-handoff history.

## External intake boundary

XDM may receive content through:

- `ACTION_SEND` and `ACTION_SEND_MULTIPLE`;
- typed or file-extension-specific `ACTION_VIEW` download intents;
- Android browser download-manager actions;
- XDM typed actions such as `ADD_URL` and `CAPTURE_MEDIA`;
- explicit Tasker, extension, or companion integration.

All intake remains review-first. Receiving a URL must not silently start or queue a transfer.

An ordinary `ACTION_VIEW` intent for a normal HTTP or HTTPS page, with no download MIME type or file-extension contract, must not resolve to XDM. Users can share that page to XDM when they want the media resolver to inspect it.

## Browser absence contract

Production code and the merged application manifest must remain free of:

- `BrowserActivity` and `BrowserScreen`;
- `AppRoute.Browser`;
- Android WebKit, `WebView`, `WebViewClient`, and `WebChromeClient`;
- browser launch aliases and generic browsing intent filters;
- tabs, bookmarks, browsing-history, private-session, site-permission, and browser-profile persistence.

The module named `browser-integration` is intentionally retained. It is a WebKit-free adapter for links and download actions supplied by external browsers. Its name does not grant ownership of a browsing surface.

## Downloader preservation contract

Browser absence must never remove or weaken:

- shared-text, subject, ClipData, URI, and MIME intake;
- direct-file, direct-media, HLS, DASH, torrent, and page-probe classification;
- request-header sanitization and redacted diagnostics;
- native direct, embedded aria2, Termux yt-dlp, scheduler, worker, retry, pause, resume, cancel, recovery, and verification paths;
- media variant, audio, and subtitle selection;
- offline library, missing-file detection, Media3 playback, and player diagnostics.

## Persistence contract

Room remains schema 14 until a separate feature requires a migration. The schema and DataStore preferences must contain downloader, media, queue, recovery, automation, ownership, and settings state only. Old browser-era values such as a stored `Browser` route may be read only for safe fallback to Downloads; they must not reactivate browser state.

## Release qualification

A release candidate is qualified only after:

1. all static phase validators pass;
2. Android lint passes with warnings treated as errors;
3. JVM and Android test sources compile;
4. downloader module tests pass;
5. debug and beta APKs assemble;
6. the packaged aria2 payload passes release checks;
7. manual clean-install and upgrade scenarios confirm external handoff, media resolution, execution, recovery, library, and playback;
8. XDM is not offered for ordinary web navigation.

The permanent final contract is enforced by `tools/validate-browser-removal-phase-7.py`, `BrowserRemovalPhase7ContractTest`, and `BrowserRemovalFinalManifestTest`.
## Review-first intake and Downloads dashboard

Manual URL entry, clipboard extraction, external shares, typed download intents, and automation handoff must converge on browser-neutral review models. Classification and recommendation code may normalize and describe a request, but it must not persist a download or start execution. HLS/DASH and page-like URLs should offer explicit Media inspection while retaining an intentional direct-download choice.

Downloads is the transfer control center. It groups records into Needs attention, Active, Queued, Completed, and History; provides stable smart ordering; and translates failures into actionable, secret-safe guidance. Grouping must not replace the existing queue, scheduler, recovery, backend migration, verification, or diagnostics machinery.

