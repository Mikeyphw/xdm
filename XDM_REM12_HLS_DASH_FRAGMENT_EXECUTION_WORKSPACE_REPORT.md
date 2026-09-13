# XDM Desktop REM12 — HLS/DASH Fragment Execution, Checkpoint Identity, and Workspace Isolation

## Overlay

- Artifact: `xdm_desktop_rem12_hls_dash_fragment_execution_workspace_v1.tar.gz`
- Target: `xdm_modern`
- Roadmap item: REM12
- Commit message: `fix(desktop): implement REM12 fragment execution workspace`
- Validation mode: intermediate overlay; apply with `--no-validate`.

## Scope delivered

REM12 hardens segmented media execution and publication around HLS, DASH, live refresh, external-provider fragments, checkpoints, workspace ownership, subtitle publication, and bounded transfer safety.

## Implementation summary

### Fragment identity and checkpoint ownership

- Added `FragmentIdentity` for URI/range/content-generation hashes and collision-resistant format workspace directories.
- Added `FragmentPlanEntry` and `FragmentResumeState` so resume checks validate fragment id, content identity, file existence, exact byte length, relative safe path, and ordering.
- Expanded `FragmentCheckpoint` to version 2 with explicit checkpoint entries, plan id, initialization ownership, and cumulative live elapsed seconds.
- Changed resume byte accounting to sum only currently valid checkpoint-owned entries, preventing old or missing fragments from contributing stale `DownloadedBytes`.
- Added malformed checkpoint quarantine in `FragmentCheckpointStore`; invalid JSON is moved to `checkpoint.json.corrupt-*` and a clean state is rebuilt.

### Fragment assembly and temp cleanup

- Added `FragmentAssembler` so HLS/DASH/external assembly receives only checkpoint-owned paths in checkpoint order.
- Removed broad `.part` directory enumeration from fragmented assembly paths.
- Cleans `.assembling` output if assembly fails or is cancelled.
- `MediaHttp.DownloadToFileAsync` and HLS encrypted/init-map write paths now clean `.downloading` temps on failure.

### HLS execution

- Enforces same-resource implicit `#EXT-X-BYTERANGE` offset carryover.
- Validates returned `Content-Range` start/end coordinates for ranged segment and map requests.
- Tracks HLS initialization maps transactionally in the fragment checkpoint instead of sidecar marker files.
- Recomputes a segment plan after stale checkpoint invalidation so a replacement segment carries the required init map when needed.
- Removes AES-128 no-padding fallback; PKCS#7 failure now fails the fragment instead of silently accepting ambiguous bytes.
- Keeps encrypted/init-map segment memory bounded to 64 MiB while streaming clear segments directly to disk.
- Enforces cumulative live capture duration across resume and current execution.

### DASH execution

- Uses stable URI-hash identities for `SegmentList` media segments, including duplicate URI occurrence suffixes.
- Expands open-ended dynamic `SegmentTimeline` entries across the live time-shift window and advances both `$Time$` and `$Number$` when old live-window segments are skipped.
- Enforces per-segment and request byte limits during the streaming copy instead of after a full oversized file has already been written.
- Uses checkpoint-owned assembly and live elapsed accounting.

### External provider execution

- Uses stable URI-hash fragment ids from yt-dlp fragment URIs instead of positional ids.
- Applies bounded per-fragment copy and checkpoint-owned assembly.

### Workspace and subtitle publication

- Builds media workspaces from source, destination, selected format identities, and selected subtitles.
- Adds an exclusive `.workspace.lock` file so two captures cannot share the same workspace concurrently.
- Uses collision-resistant per-format directories to prevent sanitized-name collisions from sharing fragment/checkpoint state.
- Downloads subtitle tracks into the workspace first; subtitle files are published only after main media finalization succeeds.
- Preserves subtitle extension by container/source path (`.vtt`, `.srt`, `.ttml`, `.ass`) instead of materializing everything as `.vtt`.
- Ensures multiple selected subtitles with the same language receive stable duplicate names instead of overwriting each other.

## Findings mapped

- S09-01: workspace identity and exclusive lock added.
- S09-02: checkpoint-owned assembly replaces broad `.part` enumeration.
- S09-03: fragment identity includes URI/range/content-generation data.
- S09-04: HLS init-map ownership moved into checkpoint entries.
- S09-05: byte-range `Content-Range` coordinates validated.
- S09-06: DASH `SegmentList` ids are stable URI hashes.
- S09-07: open dynamic DASH timeline advances within the live window.
- S09-08/S09-09: byte accounting derives from valid live entries only.
- S09-10: live duration is cumulative across resume and current run.
- S09-11: implicit HLS BYTERANGE offset resets on new resource.
- S09-12: AES-128 PKCS#7 failures are no longer suppressed.
- S09-13: HLS encrypted/init-map buffering is bounded; clear segments stream to disk.
- S09-14: DASH oversized parts fail during copy and temp files are deleted.
- S09-15: external provider fragment ids are URI-stable.
- S09-16/S09-17/S09-18: subtitle extension, duplicate naming, and publication atomicity repaired.
- S09-19: permanent HTTP status failures are no longer retried as transient.
- S09-20: per-format directories include stable hash suffixes.
- S09-21/S09-22: `.downloading` and `.assembling` cleanup paths added.
- S09-23: malformed checkpoint JSON is quarantined before rebuilding state.

## Contract tests added or extended

- HLS BYTERANGE same-resource behavior.
- HLS checkpoint-owned assembly excluding orphan `.part` files.
- HLS range coordinate rejection.
- HLS permanent HTTP failure retry policy.
- HLS malformed checkpoint quarantine.
- DASH sliding `SegmentList` stable ids.
- DASH dynamic open timeline expansion.
- DASH segment limit enforcement before keeping oversized parts.
- yt-dlp fragment id stability across provider refresh.
- Subtitle duplicate naming and extension preservation.
- Subtitle non-publication when main mux fails.

## Local validation available in this container

- Static sentinel scan for removed unsafe patterns.
- Changed-file inventory generated from REM11 base comparison.
- Manifest JSON validation.
- Overlay tar extraction validation.

The container does not include the .NET SDK, so `dotnet test` could not be executed here. Devtool-side validation should remain deferred because REM12 is an intermediate overlay.
