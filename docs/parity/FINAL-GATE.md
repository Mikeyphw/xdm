# Overlay 21 — final parity gate

Overlay 21 converts the parity ledger from an inventory into a release gate.
The modern solution is the only active application target.

## Required outcomes

The gate fails unless:

- every critical and high-priority contract is complete, intentionally replaced,
  or not applicable;
- every completed or intentionally replaced contract references repository-local
  implementation evidence and an existing automated test class or method;
- `XDM.Modern.sln` contains only the approved modern projects;
- known WPF, GTK, WinForms, legacy CoreFx, compatibility, mock-server and legacy
  test application paths are absent;
- recorded migration fixtures cover settings XML/JSON, history, scheduler state,
  and credential-redacted export;
- Linux and Windows CI build, test, bootstrap, and smoke a self-contained package;
- diagnostics remain at zero warnings and zero errors.

## Remaining legacy product decisions

FTP/FTPS transport is intentionally replaced. The modern download engine accepts
HTTP and HTTPS and returns an actionable rejection for FTP schemes. Users who
need file-transfer protocols should use an HTTPS source or a dedicated transfer
client rather than routing credentials through the browser-download pipeline.

In-process binary self-update is intentionally replaced by externally installed,
checksum-qualified packages from the HTTPS release channel. This avoids modifying
running binaries and keeps package-manager ownership intact.

## Unknown-length responses

A range probe that cannot establish a total length falls back to the normal
single-stream path. Completion records the observed final byte count, does not
create segmented checkpoints, and remains compatible with chunked responses.

## Local qualification

Linux:

```bash
app/XDM/eng/final-gate.sh
```

Windows PowerShell:

```powershell
./app/XDM/eng/final-gate.ps1
```

Devtool continues to validate only `app/XDM/XDM.Modern.sln`.
