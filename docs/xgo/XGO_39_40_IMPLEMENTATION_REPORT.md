# XGO-39..40 Implementation Report

## Merge-window decision

The required XGO-39 + XGO-40 + XGO-41 window was inspected against the post-XGO-38 tree and donor implementations. XGO-39 and XGO-40 are merged because exact compatibility and deterministic selection are one routing authority, use the same `xgo_backends` target, and share one failure/evidence boundary. XGO-41 remains separate because generic aria2 JSON-RPC introduces request correlation, RPC timeouts, malformed replies, daemon/session commands and transport-specific failure modes.

## XGO-39 delivered

- Added `engine/backends/router` without introducing a second request model. `Operation` embeds canonical `NetworkIntent` and carries only non-identity execution requirements: destination, resolved proxy mode, media shape, resume and selective repair.
- Added the common `Backend` contract with `CanExecute(Operation) CompatibilityResult` and exact `Preflight(Operation)` enforcement.
- Added typed hard-reject reasons for protocol, method/body, destination, credential mode, proxy, media shape, resume and mirror semantics.
- Added soft preference hints that are strictly subordinate to compatibility.
- Added exact native and aria2 request-shape inspection. The current aria2 surface is intentionally limited to capability already owned before XGO-41: bodyless HTTP/HTTPS/FTP GET, staging destination, direct network semantics, no captured credential semantics. Native owns HTTP GET/POST, FTP/FTPS and the currently implemented native-only execution shapes.
- Added compatibility/preflight property coverage so an accepted canonical shape cannot later fail preflight as a known unsupported capability, and a rejected shape returns the same typed first reason.
- Closed `XGO-CAP-BACKEND-001` and promoted `xgo-cap-backend-001` to a detailed nine-case fixture.

## XGO-40 delivered

- Added one deterministic `Select` policy over compatibility, user preference/fallback policy, backend health, runtime availability, operation shape and migration cost.
- Selection returns the chosen backend plus a stable reason/explanation and all candidate compatibility/rejection evidence; no frontend heuristic is consulted.
- Explicit preference wins only for a compatible, available backend. Fallback remains governed by the canonical request flag.
- Health and runtime unavailability participate as explicit rejection/score inputs. Mirror, FTP, large-transfer, POST/FTPS/credential/platform-stream and direct-media shapes are represented as deterministic hints rather than hidden UI rules.
- Added a hard fence for started attempts: alternatives are rejected as `migration_required`; selection never silently changes an active attempt's backend.
- Added attempt-scoped persistence using the existing SQLite `diagnostic_events` table. The complete safe decision is recorded only while the attempt is `reserved`, is idempotent for the same event, and never mutates `BackendKind`.
- Closed `XGO-CAP-BACKEND-002` and promoted `xgo-cap-backend-002` to a detailed ten-case fixture.

## Donor convergence

Desktop's backend advisor supplied the key aria2 boundary: aria2 accepts only a bodyless GET over HTTP/HTTPS/FTP in its existing advisor path, with explicit fallback behavior and preference toward mirrors/FTP/large transfers. Android supplied capability inspection, backend availability/health-style policy inputs, and the invariant that fallback is a pre-start decision while migration is separate. XGO centralizes those behaviors into typed Go contracts instead of reproducing either host's frontend policy code.

## Validation topology

`xgo_backends#validate` now runs, in order:

1. native Go validation,
2. `backend_contract_audit`,
3. `post_replay_lab`,
4. `ftp_lab`,
5. `metalink_corpus`,
6. `compatibility_matrix`,
7. `selection_matrix`.

The compatibility matrix contains nine cases. The selection matrix contains ten cases, including repeated deterministic output, compatibility-bounded preference, unavailable/degraded behavior, direct HTTP, mirrors/FTP, media/external shapes, migration cost, the started-attempt fence and SQLite attempt-scoped persistence.

## Deferred work

XGO-41 still owns generic aria2 JSON-RPC mechanics and may broaden the aria2 compatibility contract only as execution support is proven. XGO-42 owns durable aria2 task/runtime identity. XGO-43 owns aria2 reconciliation and explicit backend migration. GATE-05 remains open.
