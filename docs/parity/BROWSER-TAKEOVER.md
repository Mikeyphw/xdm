# Browser takeover and extension parity — Overlay 14 + XFE01

Overlay 14 replaces the legacy unversioned browser bridge with protocol 2.0 and
an explicit acknowledgement boundary. The extension never cancels a browser
download merely because the native process accepted a message: cancellation and
erasure happen only after the modern app has validated the request and queued a
download successfully.

## Supported extension paths

- Firefox has exactly one implementation: the Android-owned canonical source at
  `app/XDM.Android/browser-extension/src/main/extension/xdm-firefox`. Its fixed ID is
  `xdm-android-media-bridge@mikeyphw`. Desktop packaging renders that same source
  into `XDM-Firefox.xpi`; it does not carry a desktop Firefox fork.
- Firefox keeps its proven `xdmdownload://add` v1 and `xdmdownload://capture` v3
  handoff logic. Desktop adapts to those custom URL schemes and forwards a launch
  to the already-running XDM instance when necessary.
- The Chromium Manifest V3 extension remains desktop-specific and supports
  Chrome/Chromium and compatible Edge, Brave, Vivaldi and Opera builds. Because
  unpacked/store extension IDs differ, paste one or more 32-character IDs into
  **Browser integration → Install / repair**.
- **Install / repair** registers the Firefox custom URL schemes per user and
  native-host manifests for the Chromium family. It also removes the obsolete
  Firefox native-messaging manifest from pre-XFE01 desktop installs.

## Protocol and trust boundary

Firefox does not use the desktop native-messaging protocol. The canonical extension
builds bounded custom-scheme handoffs; desktop accepts only release/debug XDM schemes,
add v1 or capture v3, HTTP(S) media URLs, bounded candidate payloads, and final-sent
request-header evidence. Proposed headers remain diagnostic evidence and never become
runtime request authority. The custom-scheme payload is forwarded over the authenticated
single-instance loopback token when a second process is launched.

Chromium native messages use a four-byte little-endian length prefix and strict JSON
schemas. Protocol 2.0 accepts only `hello`, `health`, `capture` and
`capture-batch`. Messages are capped at 256 KiB, batches at 100 captures, capture
payloads at 128 KiB, headers at 64 entries and request bodies at 16 KiB. A random
per-process session returned by `hello` authenticates every later message. The
native manifest allow-list is the outer browser identity boundary; the session
prevents stale/cross-connection messages. There is no shell, process-launch or
arbitrary command message.

The native host forwards an authenticated request to the loopback service. The
loopback token remains local and is never exposed to the extension. Status and
diagnostics omit URL query strings/fragments, cookies, authorization values and
POST bodies. Recorded test fixtures intentionally contain secrets to verify that
the protocol can carry required request state without adding it to logs or UI
status.

## Captured metadata

The extensions forward only bounded request state needed to reproduce a safe
HTTP download: URL, filename, MIME type, expected size, cookies, referer, user
agent, selected safe headers, and GET/POST metadata. Hop-by-hop and routing
headers such as `Host`, `Content-Length`, `Connection`, proxy authorization and
transfer encoding are rejected. POST bodies are bounded to 16 KiB and execute as
a single connection with no automatic retry. They are deliberately not persisted;
a restarted app marks an unfinished POST capture as non-replayable rather than
sending an empty or stale request.

## Capture rules

Automatic interception can be controlled by:

- master enable and temporary disable;
- minimum size;
- allowed/blocked MIME patterns;
- allowed/blocked filename extensions;
- included and excluded sites; and
- an explicit incognito policy.

Manual **Download with XDM**, **Download media with XDM** and **Download all
links with XDM** commands bypass content filters but never bypass the incognito
policy. Download-all sends bounded batches and the native host revalidates every
item.

## Deterministic validation

`XDM.BrowserMedia.Tests` copies recorded native-message fixtures into its test
output and validates negotiation, metadata, POST data, batches, unknown commands,
strict schemas, limits, framing, capture rules, host manifests and the delayed
loopback acknowledgement. Download-engine tests validate safe POST execution and
non-replay after restart.
