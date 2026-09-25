# XGO-37 Implementation Report

## Delivered

- Added a canonical FTP/FTPS backend under `engine/backends/ftp` without creating a parallel request model.
- Added canonical `ftp_password` credential references with a persisted non-secret principal and runtime-only password resolution.
- Added passive FTP transport with EPSV-first/PASV fallback, SIZE probing, REST resume, RETR transfer, anonymous login, authenticated login, and typed unsupported-feature errors.
- Added implicit FTPS support for control and protected data channels (`PBSZ 0`, `PROT P`).
- Kept passive data-channel authority on the approved control host rather than trusting a PASV-advertised host.
- Reused canonical checkpoint commits, staging files, central connection/byte resource limits, retry failure taxonomy, checksum verification, ArtifactGeneration creation, and publication preparation.
- Extracted the SQLite attempt lifecycle from `transfer/http` into protocol-neutral `transfer/lifecycle`; HTTP retains a compatibility alias.
- Added real local wire tests for FTP resume and authenticated FTPS, including TLS-protected data transfer.
- Added a cgo/SQLite end-to-end test proving ownership -> durable checkpoints -> `transport_complete` -> checksum verification -> artifact/publication preparation.
- Closed `XGO-CAP-FTP-001` and promoted its fixture to an eight-case detailed contract.

## Validation topology

`xgo_backends#validate` now runs:

1. native Go validate,
2. `backend_contract_audit`,
3. `post_replay_lab`,
4. `ftp_lab`.

`ftp_lab` covers anonymous FTP, authenticated FTP, FTPS mode, resume, wrong size, disconnect/retry taxonomy, and checksum verification. The native Go runner additionally executes real protocol tests and the SQLite/finalization integration test.

## Merge-window decision

After XGO-36 passed, the required merge window was XGO-37..39. XGO-37 remains standalone after source inspection: FTP/FTPS introduces a stateful protocol/auth/resume/disconnect surface, while XGO-38 is XML metadata expansion and XGO-39 is compatibility preflight. Combining them would reduce fault isolation despite their shared `xgo_backends` owner. The next implementation decision must re-evaluate XGO-38..40.

## Audit-loop evidence

The implementation audit loop found and fixed a real FTPS data-channel handshake ordering hang: the TLS handshake is now deferred until after the RETR preliminary reply, matching passive FTPS server behavior. After that fix:

- backend/request/credential/transfer focused tests: pass,
- real FTP/FTPS protocol tests: pass,
- SQLite ownership/checkpoint/finalization integration: pass,
- shared capability-ledger audit: pass,
- shared fixture audit: pass,
- `backend_contract_audit`: pass,
- `post_replay_lab`: pass,
- `ftp_lab`: 7/7 pass,
- scoped `go vet`: pass.

The exact packaged artifact is separately re-applied to a fresh post-XGO-36 baseline before delivery. No Devtool reinstall or refresh hook is included.
