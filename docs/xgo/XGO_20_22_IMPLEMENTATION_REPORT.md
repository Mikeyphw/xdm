# XGO-20..22 implementation report

## Scope

This overlay implements the first half of Wave 3 network security:

- XGO-20 canonical network intent;
- XGO-21 header/cookie/credential admission policy;
- XGO-22 route classification and scoped private-network approval.

It deliberately does not implement XGO-23 DNS-to-dial binding, XGO-24 redirect state machines, or XGO-25 TLS/cleartext/proxy integration.

## XGO-20

`engine/domain/request.NetworkIntent` is the safe persisted request form. It owns transport URL, stable logical resource identity, GET/POST method, safe headers, replayable/one-shot body references, credential references, mirrors, expected length, validators, checksums, capture metadata, backend preference and network approval references. Resolved body and credential bytes live in `RuntimeMaterial`, whose JSON serialization is rejected.

Transport and logical identity are explicitly distinct. GET-with-body, POST-without-body, unsupported methods, malformed URLs, URL user-info and fragments produce typed request validation failures.

## XGO-21

`engine/security/header` rejects CR/LF injection and transport-owned fields such as Host, Content-Length, Connection and Transfer-Encoding. Trusted engine requests retain arbitrary syntactically safe custom headers; external/browser handoffs retain the Android donor's conservative allowlist. Sensitive header names cannot store their values directly.

`engine/security/credentials` separates origin credentials from proxy authorization and recomputes forwarding for each destination. Origin, optional logical resource and cookie path must match; cross-origin credentials are stripped by construction. Diagnostics expose only structural redaction.

## XGO-22

`engine/security/route` classifies representative IPv4 and IPv6 address space, including IPv4-mapped IPv6. Public, private, loopback, link-local, reserved, multicast and unspecified are distinct classes.

A non-public route approval is content-addressed and binds request ID, logical resource, exact target URL scope, origin and address class. Mixed public/private DNS results are rejected unless the private candidate has a matching approval. An approval for a primary target cannot authorize another path, mirror, redirect, request, resource or address class.

## Devtool

`xgo_security` is promoted from the bootstrap command target to the native Go runner. Its authoritative validation DAG is:

1. `runner:go#validate`
2. `security_contract_audit`
3. `request_fixture_diff`
4. `header_security_matrix`
5. `route_matrix`

The shared capability/fixture contract remains 96 capabilities / 96 fixtures. XGO-CAP-REQUEST-001 and XGO-CAP-SECURITY-001..003 are now IMPLEMENTED.

## Validation

Devtool validation of `xgo_security` (with only the execution environment overridden from checked-in `native-termux` to `desktop-linux` for the container simulation) passed 8 stages, 20 Go tests, 6 tested packages, one built audit executable, zero warnings and zero errors. Full `go test ./engine/...`, full `go vet ./engine/...`, shared capability/fixture audit, and topology audit also pass.
