# XDM Overlay — Browser extension security and diagnostics

This overlay applies on top of commit `aee5671`.

## Least-privilege browser helpers

- moves cookies, webRequest, and HTTP/HTTPS host access to optional permissions;
- keeps URL-only acknowledged takeover functional without enhanced metadata access;
- exposes explicit grant/remove controls in the popup;
- keeps private/incognito capture disabled by default;
- excludes sensitive authentication, payment, password-manager, and loopback hosts by default.

## Per-site capture policy

- adds Always, Ask each time, and Never modes;
- defaults unconfigured sites to Ask each time;
- queues pending automatic captures without cancelling the browser download;
- allows users to confirm takeover or keep the download in the browser;
- preserves explicit context-menu capture.

## Native-host hardening

- upgrades the extension handshake to report identity, manifest version, optional permissions, and origins;
- enforces extension version compatibility;
- verifies the browser-provided native-host origin/add-on identity when available;
- validates native-host manifest allow-lists rather than accepting any non-empty list;
- reports authenticated extension health to the XDM loopback service.

## Desktop diagnostics

- adds extension version, manifest, compatibility, heartbeat, permissions, and capability summaries;
- adds a Refresh diagnostics action;
- includes extension health in diagnostic support bundles;
- adds deterministic tests for manifests, site policies, pending confirmation, protocol compatibility, origin validation, and health reporting.

`docs/parity/features.json` is intentionally unchanged because this is a modern security enhancement rather than a legacy-parity claim.

## Validation repair

- accepts both fixed-length and chunked extension-health reports while enforcing the same 32 KiB streaming limit;
- uses culture-invariant manifest-version formatting;
- removes the reported single-character and repeated-array analyzer warnings.
