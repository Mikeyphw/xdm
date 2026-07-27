# Phase 41 — Browser Bridge Settings, Diagnostics, and Hardening

Phase 41 completes the product-facing browser bridge before the final release gate. It does not add a browser runtime, a WebView, a top-level route, a Room migration, or a new download engine.

## Settings truth contract

`SettingsPanel.BrowserExtension` now reports:

- custom-scheme registration for the active build variant;
- extension and bridge contract versions;
- retained SAF folder access;
- last verified XPI document, filename, size, theme, target, variant, and SHA-256;
- current or stale state with explicit reasons;
- generation/regeneration, open-file, folder-reselection, status-refresh, and setup-copy actions;
- bounded redacted diagnostics for accepted/rejected links and XPI generation.

The status surface must never claim that an XPI is current merely because metadata exists. The persisted document is reopened, its size is checked, and its SHA-256 is compared with the recorded verified hash.

## Result-bearing deep-link parser

`XdmBrowserDeepLinkParser.parseDetailed()` distinguishes:

- `NotApplicable` for ordinary HTTP/HTTPS/FTP and unrelated intents;
- `Accepted` with the same sanitized Phase 37 payload;
- `Rejected` with a bounded reason code and user-safe explanation.

Rejected XDM-scheme links stop before generic `ACTION_VIEW` intake. This prevents an invalid `xdmdownload` envelope from being reinterpreted as an ordinary download URL.

The parser records no raw URI in diagnostics. Accepted diagnostics contain only action, media kind/MIME, and a query-free endpoint. Rejected diagnostics contain only the reason code and static explanation.

## Compatibility and recovery

The integration inspector covers:

- missing, wrong, or correct Android scheme handler;
- absent export folder;
- revoked persisted SAF permission;
- missing or unreadable exported document;
- size or checksum mismatch;
- app variant, scheme, extension version, contract version, target, theme, and app-version mismatch;
- interrupted generation left in the `exporting` state after process death.

An interrupted or failed generation never invalidates the previous verified XPI. Phase 39 staging, validation, backup, and restore behavior remains authoritative.

## IronFox guidance

The settings panel produces instructions for the exact generated scheme, including release and debug variants. The guide keeps `network.protocol-handler.expose.<scheme>` enabled and uses a real in-page extension anchor.

## Privacy contract

Persistent browser-bridge diagnostics are bounded and pass through `BrowserBridgeDiagnosticsRedactor`. They must not contain:

- cookies or `Set-Cookie` values;
- authorization or proxy-authorization values;
- bearer tokens;
- token, signature, session, password, credential, or secret query values;
- full signed media URLs.

Signed media query values remain available only in the live handoff payload needed by the download workflow. They are stripped from diagnostics and support text.

## Validation

Phase 41 adds:

- detailed parser-result tests;
- diagnostics redaction tests;
- Settings and integration architecture contracts;
- `tools/validate-phase-41-browser-bridge-integration.py`;
- CI and Devtool overlay validation wiring.

The final Phase 42 gate remains responsible for the full Gradle matrix, lint, generated XPI qualification, and IronFox device acceptance.

## Package-version consistency

Gradle package names, the generated WebExtension manifest, runtime export metadata, and Settings diagnostics all use extension version `1.1.0`. Phase 41 removes the inherited `1.0.0` filename mismatch so an exported artifact cannot look stale before installation.
