# XGO-20..22 overlay contract

## Owned roadmap IDs

- XGO-20 canonical network intent model
- XGO-21 header, cookie and credential admission policy
- XGO-22 route classification and scoped private-network approval

## Required invariants

1. Persisted request state contains references, never resolved secret/body bytes.
2. Logical resource identity does not change when the transport URL changes.
3. GET/POST body semantics fail with typed reasons when invalid.
4. CR/LF and transport-owned header manipulation are rejected pre-transport.
5. Sensitive values use credential references rather than persisted header values.
6. Origin credentials never cross origin implicitly; cookies also respect path scope.
7. Proxy authorization remains separate from origin credentials.
8. All representative IPv4/IPv6 special classes are classified before dialing.
9. Non-public route approval is scoped to request, logical resource, exact target, origin and class.
10. Mirror/redirect targets require fresh route evaluation.
11. XGO-22 does not claim DNS-to-dial binding; that remains XGO-23.

## Artifact validation

The manifest must select `xgo_security`, require validation, use `failure_action = pause`, forbid deferred validation, and omit `validation.tasks` so `.devtool.toml` remains authoritative.
