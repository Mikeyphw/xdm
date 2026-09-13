# XDM Desktop REM13 — FFmpeg Conversion, Publication, and Cleanup

## Status

Implemented as an intermediate Devtool overlay for target `xdm_modern`.

REM13 continues from REM12 and closes the conversion/post-processing layer before the remaining shell, diagnostics, updater, and final seal overlays.

## Scope delivered

- Added bounded concurrent stdout/stderr retention for FFmpeg and external tools so verbose output cannot deadlock pipes or grow unbounded in memory.
- Added timeout/cancellation cleanup that kills the FFmpeg/tool process tree and then awaits process exit plus pipe drainage before returning control.
- Isolated FFmpeg progress reporting so UI/subscriber exceptions cannot interrupt pipe drainage or the conversion worker.
- Added encoder capability validation before conversion presets run, including missing encoder diagnostics for H.264/H.265/AV1/AAC/MP3/Opus families.
- Moved conversion publication metadata/timestamp handling before the final atomic move and rejected missing/empty output before publication.
- Added proportional FFmpeg mux timeout calculation and output validation after stream-copy muxing.
- Added pre-mux MP4 compatibility checks for incompatible source containers/codecs and guidance to choose MKV or a transcode preset.
- Added finalization `.xdm-finalizing` staging for media downloads and subtitle publication so existing destination files are not replaced until the new artifact validates.
- Added old temporary/finalization artifact scavenging for abandoned `.xdm-converting` and `.xdm-finalizing` files.
- Bounded conversion terminal history at 200 completed/failed/cancelled jobs.
- Added per-subscriber queue event isolation and disposal paths that request cancellation and wait for the worker to settle.
- Preserved valid Unix paths by removing UI `.Trim()` on manual conversion enqueue.
- Preserved user-entered conversion destinations instead of replacing them every time preset/source suggestions refresh.
- Separated post-download conversion queue failures from successful media downloads in the UI status path.
- Exposed parsed FFmpeg progress details (`processed`, `speed`, output bytes) in queue snapshots and the conversion view.
- Expanded device presets so device-family profiles have distinct FFmpeg argument recipes instead of cosmetic family labels.

## Key files changed

```text
app/XDM/src/XDM.App/ViewModels/ConversionJobViewModel.cs
app/XDM/src/XDM.App/ViewModels/MainWindowViewModel.MediaWorkflow.cs
app/XDM/src/XDM.App/ViewModels/MainWindowViewModel.cs
app/XDM/src/XDM.App/Views/ConversionView.axaml
app/XDM/src/XDM.BrowserMedia.Tests/ConversionPresetTests.cs
app/XDM/src/XDM.BrowserMedia.Tests/ConversionQueueServiceTests.cs
app/XDM/src/XDM.BrowserMedia.Tests/ConversionServiceTests.cs
app/XDM/src/XDM.BrowserMedia.Tests/FfmpegCapabilitiesTests.cs
app/XDM/src/XDM.BrowserMedia.Tests/MediaDownloadServiceTests.cs
app/XDM/src/XDM.Media/BoundedTextCapture.cs
app/XDM/src/XDM.Media/ConversionJobSnapshot.cs
app/XDM/src/XDM.Media/ConversionQueueService.cs
app/XDM/src/XDM.Media/ConversionService.cs
app/XDM/src/XDM.Media/DeviceProfileCatalog.cs
app/XDM/src/XDM.Media/ExternalToolRunner.cs
app/XDM/src/XDM.Media/FfmpegConversionProcessRunner.cs
app/XDM/src/XDM.Media/FfmpegService.cs
app/XDM/src/XDM.Media/MediaDownloadService.cs
```

## Validation performed in this workspace

```text
sentinel_checks_ok
cs_structure_scan_ok
git_diff_check_ok
manifest_json_ok
tar_extract_manifest_ok
```

`dotnet test` was not run in this container because the .NET SDK is not installed here.

## Devtool apply mode

The overlay manifest keeps REM13 as an intermediate, commit-capable overlay:

- `target`: `xdm_modern`
- `validation.required`: `false`
- `apply.commit.enabled`: `true`
- `apply.commit.strategy`: `single`
- `apply.requires_clean_worktree`: `true`

Recommended apply command uses `--no-validate`; reserve full validation for the later gate/seal overlay unless you intentionally want to run selected tests after apply.
