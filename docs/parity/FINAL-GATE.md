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

## Upstream parity corrections

Overlay 22 closes the original FTP/FTPS, PAC/enterprise proxy, device-profile
and update-channel gaps. FTP/FTPS uses the same durable download lifecycle as
HTTP, and update packages are staged only after HTTPS manifest, size and SHA-256
verification. XDM never executes a staged package automatically.

macOS is outside the maintained Linux/Windows product scope. Adobe HDS is
recorded as a stale upstream claim because the retained upstream parser source
contains no working HDS/F4M implementation to qualify. See
`UPSTREAM-PARITY-CORRECTIONS.md`.

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
