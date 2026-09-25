# XGO-41 overlay contract

- Roadmap item: **XGO-41 — Generic aria2 JSON-RPC client**.
- Owning target: `xgo_backends`.
- Validation is required, non-deferred, warning-free, and pauses on failure.
- `validation.tasks` is intentionally absent.
- Adds `aria2_rpc_lab` after the existing XGO-40 selection matrix.
- The production RPC package must not launch or restart aria2 processes.
- RPC request IDs must correlate exactly; malformed replies, HTTP failures,
  timeouts, and remote JSON-RPC errors remain distinct.
- Token material is runtime-only and must not appear in safe returned errors.
- No Devtool reinstall or refresh command/hook is permitted.
