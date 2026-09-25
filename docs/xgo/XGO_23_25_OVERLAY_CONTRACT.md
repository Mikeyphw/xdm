# XGO-23..25 Overlay Contract

## Scope

This overlay closes Wave 3 network security by implementing:

- XGO-23 DNS validation bound to the actual dial path.
- XGO-24 redirect security state machine.
- XGO-25 platform TLS/cleartext and proxy contract.
- GATE-03 network-security qualification through `xgo_gate_security`.

## Authority rules

1. DNS is resolved once for a target and only approved literal IP endpoints may reach the connector.
2. Original hostname identity remains authoritative for HTTP Host, TLS SNI and certificate verification.
3. Every redirect re-enters URL, route, credential, body replay and cleartext policy.
4. Redirect chains are bounded and diagnostic URLs never include query/fragment secrets.
5. Cleartext requires an explicit platform decision; sensitive cleartext additionally requires exact-target approval.
6. System/PAC proxy execution may remain host-side, but Go consumes an explicit direct/HTTP/SOCKS decision.
7. Proxy credentials are never origin credentials.
8. Missing platform policy/proxy replies fail closed.

## Devtool contract

The artifact selects `xgo_gate_security` with validation required and `failure_action = pause`.
`validation.tasks` is absent. `xgo_security#validate` contains the specialized nodes:

- `dns_dial_lab`
- `redirect_lab`
- `tls_proxy_lab`
- `network_security_gate`

`xgo_gate_security` remains a pure cross-target composition of foundation, store and security workflows.

## Gate acceptance

GATE-03 requires the cumulative foundation/store/security graph plus adversarial DNS/redirect/private-route cases and planted-secret diagnostic redaction. No Android/Desktop host integration is claimed yet; platform broker semantics are defined and exercised in Go, while concrete host adapters arrive in later host waves.

## Transaction-safe donor prerequisite

Cumulative gate validation must not locate the untouched donor relative to the transaction worktree. The foundation donor audit resolves donor location from `XGO_DONOR_ROOT` when set, otherwise from `engine/docs/donor-map.yaml:donor_root_default` (`~/Code/xdm`). A transaction-relative `../xdm` override is intentionally absent from the Devtool job.

