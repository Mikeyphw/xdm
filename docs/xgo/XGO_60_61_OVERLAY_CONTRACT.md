# XGO-60+61 Overlay Contract

## Target

`xgo_media`

## Validation nodes added

- `ffmpeg_plan_goldens`
- `ffmpeg_execution_lab`

## Source changes

- Adds typed FFmpeg planning and external media-tool execution state to `engine/media`.
- Extends `xgo-media-audit` with FFmpeg planning and execution audit modes.
- Extends `xgo_media#validate` so FFmpeg validation follows DASH execution.
- Marks XGO FFmpeg capabilities implemented in the capability ledger.

## Safety constraints

- No shell interpolation is introduced.
- External tool requests are represented as structured `binary + argv` values.
- Hostile filenames remain literal argv entries.
- Control characters are rejected in binary/argument material.
- Diagnostics are bounded before persistence.
- Publication only follows FFprobe-style verification.
