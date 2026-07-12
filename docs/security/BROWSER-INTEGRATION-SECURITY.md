# Browser integration security and diagnostics

This phase converts the browser helper to a least-privilege capture model while preserving acknowledged takeover through XDM's native host.

## Permission model

- URL takeover, context-menu capture, and native messaging use only the required baseline permissions.
- Cookies, request bodies, request headers, and broad HTTP/HTTPS host access are optional.
- The popup explains the extra metadata access and lets the user grant or remove it explicitly.
- Private/incognito capture remains disabled unless the user enables it.
- Sensitive authentication, payment, password-manager, loopback, and local-administration hosts are excluded by default.

## Site policy

Every site resolves to one of three modes:

- **Always capture**: eligible browser downloads are handed to XDM automatically after acknowledgement.
- **Ask each time**: the browser download continues while the popup shows a pending takeover request.
- **Never capture**: automatic takeover is suppressed.

Manual context-menu actions remain explicit and bypass content/site filters, but never bypass the private-mode policy.

## Native-host trust boundary

- The extension and native host negotiate protocol and minimum extension versions.
- The hello message includes the extension identity, manifest version, incognito state, and granted optional origins.
- When the browser supplies a launch origin/add-on ID, the native host verifies it against the extension identity.
- Native-host manifests are considered compatible only when their allow-list contains valid expected extension identities.
- Session authentication, strict JSON schemas, bounded messages, and the authenticated loopback token remain mandatory.

## Diagnostics

The native host reports an authenticated extension-health snapshot to XDM, including:

- browser and extension versions;
- extension ID and manifest version;
- protocol compatibility;
- optional permission state and granted origin count;
- advertised capabilities;
- last heartbeat time.

The Browser Integration page surfaces this information and includes it in exported diagnostic bundles without exposing the loopback token.
