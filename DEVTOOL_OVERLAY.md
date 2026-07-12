# XDM Overlay 19 — history and file management

Base: confirmed successful commit `5d512e2`

Target: `xdm_modern`

Active solution: `app/XDM/XDM.Modern.sln`

## Included

- Optional age/count history retention that preserves active work and all files.
- True persisted last-update timestamps for meaningful retention decisions.
- History-only removal, partial-data deletion, and explicit completed-file deletion.
- Safe completed-file move/rename with cross-volume temporary-copy fallback.
- GET re-download with auto-rename and preserved workflow metadata.
- Expired HTTP(S) URL refresh without discarding partial data.
- Persisted source-page metadata and open file/folder/URL/source-page actions.
- Versioned credential-free JSON download-list export and plain URL-list import.
- 8 MiB and 50,000-entry import limits with HTTP(S)-only normalization.
- Bulk startup restoration and indexed application-state updates for large histories.
- Avalonia history-management and retention controls.
- Deterministic history, file-action, transfer, and 10,000-entry performance tests.
- Executable parity ledger and documentation updates.

## Safety

Retention never removes downloaded files. Destructive file deletion is a
separate explicit action. Move/rename updates history only after successful
publication, preserves the source on failures, and requires explicit overwrite
permission. Import/export excludes credentials, cookies, arbitrary headers,
authorization metadata, and request bodies. Platform URL actions allow only
absolute HTTP and HTTPS URLs and do not execute command lines.

## Validation scope

Only the modern solution is allowed:

```bash
dotnet restore app/XDM/XDM.Modern.sln
dotnet build app/XDM/XDM.Modern.sln --configuration Release --no-restore
dotnet test app/XDM/XDM.Modern.sln --configuration Release --no-build
```
