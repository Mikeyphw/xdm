# XGO-37 Overlay Contract

- Target: `xgo_backends`
- Validation required: yes
- Failure action: `pause`
- Deferred validation: no
- `validation.tasks`: absent; `xgo_backends#validate` remains authoritative.
- Devtool installation/refresh: forbidden.

## Authority boundaries

- Canonical `NetworkIntent` remains the only persisted request-semantics model.
- FTP/FTPS accepts bodyless GET semantics only.
- FTP passwords are opaque runtime secrets; the persisted principal is non-secret identity metadata.
- `ftps` is implicit TLS and requires protected data-channel mode.
- Resume is REST-based and starts only from the canonical committed offset.
- FTP writes use the same checkpoint/staging authority and central limiter contract as native HTTP transfer.
- Attempt state transitions use the shared protocol-neutral SQLite lifecycle.
- FTP transport completion is not artifact completion; checksum verification and publication remain canonical shared stages.
- Unsupported server features are typed adapter failures rather than string-matched frontend decisions.

## Deferred work

Metalink expansion remains XGO-38. Exact backend compatibility and deterministic selection remain XGO-39/40. aria2 work remains XGO-41..43. GATE-05 remains open.
