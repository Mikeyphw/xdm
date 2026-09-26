# GATE-08 Media Execution Overlay Contract

## Target

`xgo_gate_media_exec`

## Validation changes

- `xgo_gate_media_exec#validate` now includes the full upstream chain through `xgo_backends` and `xgo_scheduler` before `xgo_media`.
- `xgo_media#validate` now includes explicit GATE-08 nodes for:
  - `hls_dash_simulated_servers`
  - `media_security_scope_suite`

## Source changes

- `.devtool.toml`
- `engine/cmd/xgo-media-audit/main.go`
- `docs/xgo/GATE_08_MEDIA_EXECUTION_AUDIT_CLOSURE_REPORT.md`
- `docs/xgo/GATE_08_MEDIA_EXECUTION_OVERLAY_CONTRACT.md`

## Safety constraints

- No Devtool installation or refresh hook is introduced.
- No shell-string FFmpeg execution is introduced.
- HLS/DASH credential forwarding remains reference-based and policy-gated.
- Signed transport tokens remain excluded from stable media identity unless explicitly configured otherwise.
