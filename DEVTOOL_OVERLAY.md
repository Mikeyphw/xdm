# XDM Windows file-lock CI hotfix

Base: merged Avalonia modernization on `master`
Target: `xdm_modern`
Framework: `.NET 10`

## Root cause

`ExecuteDownloadAttemptAsync` called `CompleteFromPartial` while the partial-file
`FileStream` was still alive in an `await using` declaration. Linux allows a rename
of an open file, but Windows rejects the move with a sharing violation.

## Fix

The destination stream now has an explicit nested scope. Flush, expected-length
validation, and byte accounting happen inside that scope. Finalization and the
atomic `.part` move happen only after asynchronous disposal has completed.

## Validation

```bash
dotnet restore app/XDM/XDM.Modern.sln
dotnet build app/XDM/XDM.Modern.sln --configuration Release --no-restore
dotnet test app/XDM/XDM.Modern.sln --configuration Release --no-build
```

The existing downloader tests already exercise normal completion, resume,
range-ignore restart, retry/checkpoint recovery, queue activation, ETag resume,
and collision renaming—the seven Windows CI failures caused by this handle bug.
