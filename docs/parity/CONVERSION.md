# Overlay 16 — conversion parity

Overlay 16 adds a modern FFmpeg conversion workflow without restoring any
legacy WPF, GTK, WinForms or MSIX target.

## Supported workflows

- Lossless MP4 remux for compatible H.264, HEVC, AV1 or MPEG-4 video and
  compatible MP4 audio codecs.
- H.264/AAC MP4 transcoding with balanced and compact capability presets.
- MP3 extraction at constant 192 kbps or high-quality V0 variable bitrate.
- A sequential conversion queue with structured progress, cancellation,
  failure isolation and removable terminal history.
- Sending an existing completed download to the conversion page.
- Optional automatic conversion after the streaming-media workflow finishes.

## Validation and safety

Requests contain only a fixed preset ID, source path, destination path and
bounded behavioral flags. Preset IDs resolve to application-owned FFmpeg
argument arrays; arbitrary command fragments are never accepted.

FFprobe inspects the source before FFmpeg starts. XDM validates required audio
or video streams, destination extension, source/destination separation and
stream-copy compatibility. FFmpeg is started with `UseShellExecute = false`
and `ProcessStartInfo.ArgumentList`; no shell command is assembled.

Conversion writes to a private temporary file in the destination directory.
The previous destination is preserved if FFmpeg fails or cancellation occurs.
Only a non-empty successful output is atomically moved into place. Cancellation
terminates the FFmpeg process tree and never modifies the source file.

## Progress

FFmpeg receives `-progress pipe:1 -nostats`. XDM parses key/value progress
records, including processed duration, total output size and speed. A source
duration from FFprobe produces a bounded progress fraction; unknown-duration
inputs remain indeterminate until finalization.

## Validation scope

Only the modern solution is valid for this overlay:

```bash
dotnet restore app/XDM/XDM.Modern.sln
dotnet build app/XDM/XDM.Modern.sln --configuration Release --no-restore
dotnet test app/XDM/XDM.Modern.sln --configuration Release --no-build
```
