# XGO-23..25 Implementation Report

## Delivered

### XGO-23 — DNS-to-dial binding

- Added `engine/security/dial`.
- Resolver returns a target-bound candidate set.
- Route policy evaluates that exact set before dialing.
- Connector receives literal IP:port endpoints only.
- Candidate retry stays inside the approved set.
- Original hostname is retained for Host/SNI/certificate identity.
- Rebinding/private-route candidates cannot reach the dial callback without approval.

### XGO-24 — Redirect security

- Added `engine/security/redirect`.
- Same-origin/cross-origin credential forwarding is recomputed per hop.
- Public-to-private redirects require a fresh exact scoped approval.
- 301/302/303 POST behavior converts to GET without a body.
- 307/308 body preservation requires replayable body material.
- Loop/count bounds are explicit.
- HTTPS-to-HTTP and sensitive cleartext hops re-enter platform cleartext policy.
- Diagnostic redirect URLs redact query strings/fragments.

### XGO-25 — TLS/cleartext/proxy policy

- Added `engine/security/transportpolicy`.
- Added typed platform-broker requests for cleartext and system/PAC proxy resolution.
- Missing platform replies fail closed.
- Cleartext credentials and credential-bearing query parameters require exact-target approval.
- Proxy model covers direct, HTTP, SOCKS, system and PAC-resolved decisions.
- Proxy credentials are separated from origin credentials.
- TLS plan preserves original server name, enforces TLS >= 1.2 and explicit root strategy.
- Added typed TLS failure categories for hostname/certificate/handshake failures.

## Capability closure

The following capability ledger entries are now `IMPLEMENTED` with detailed language-neutral fixtures:

- `XGO-CAP-SECURITY-004`
- `XGO-CAP-SECURITY-005`
- `XGO-CAP-SECURITY-006`
- `XGO-CAP-PROXY-001`

The shared ledger remains 96 capabilities / 96 fixtures.

## Validation evidence

Local source validation completed successfully:

- full `go test ./engine/...`
- full `go vet ./engine/...`
- security contract/fixture audit
- Devtool `xgo_security#validate` under a simulation-only `desktop-linux` execution override

Verified `xgo_security` result:

- 12 stages passed
- 35 tests passed, 0 failed, 0 skipped
- 9 Go packages passed
- 1 Go executable verified
- 0 warnings, 0 errors

The composed `xgo_gate_security` plan expands successfully to 34 DAG nodes and contains the four new security validation nodes. The authoritative Gate-03 execution is intentionally left to the native-Termux artifact apply because the repository targets are pinned to `native-termux`; the desktop sandbox does not impersonate that execution environment for the final claim.

## Gate-03 v2 transaction-worktree hotfix

The first native-Termux Gate-03 apply reached the inherited `xgo_foundation.donor_audit` after all new security tests passed, then failed because the bootstrap job passed `--donor-root ../xdm`. Devtool validation runs inside an isolated transaction worktree, so that relative path incorrectly resolved under `~/.local/share/devtool/transactions/...` instead of the authoritative donor checkout.

The v2 artifact fixes donor location authority without changing any XGO-23..25 security behavior:

- the foundation job no longer passes a transaction-relative donor path;
- `XGO_DONOR_ROOT` may explicitly override donor location;
- otherwise `engine/docs/donor-map.yaml:donor_root_default` is authoritative and defaults to `~/Code/xdm`;
- foundation tests include a fake Devtool transaction CWD regression proving donor resolution is CWD-independent.

The failed v1 run therefore does not invalidate the security implementation; it exposed a bootstrap donor-path defect in the cumulative gate.

