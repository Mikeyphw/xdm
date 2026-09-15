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
verification. Portable self-update can execute only through the external updater
transaction runner; package-manager installs remain owned by the OS package
manager, and rollback stays available until the restarted app survives its
observed health window.

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


## REM18 final release seal

The final XDM Desktop release gate is owned by **REM18, overlay 18 of 18**. It is not a documentation-only checkpoint: Devtool must apply the overlay with validation enabled and the final package step must execute `app/XDM/eng/rem18-final-release-seal.sh` or the Windows PowerShell equivalent.

The REM18 gate proves the whole repaired system rather than one feature area. Required evidence includes the full 258-finding closure ledger, Linux/Windows x64/arm64 release matrix coverage, browser/native-host and shutdown concurrency fault injection contracts, diagnostics bundle privacy boundaries, FFmpeg/process cancellation pressure tests, updater rollback observation-window contracts, and release metadata/signing checks.

A release is blocked unless `docs/remediation/XDM_DESKTOP_FINAL_REMEDIATION_LEDGER.json` audits to 258/258 Closed with severity totals of 67 High, 153 Medium, and 38 Low.


## Single Firefox extension gate

The desktop final gate includes the XFE01 validator. Firefox support is sealed only when desktop packaging consumes the Android-owned canonical Firefox source and the retired desktop Firefox fork remains non-loadable.
