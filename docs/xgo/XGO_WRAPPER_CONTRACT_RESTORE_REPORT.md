# XGO Wrapper Contract Restore

## Finding

The repository contains the generated-style `devtoolw` launcher,
`tools/xgo/validate-devtool-wrapper.py`, and `docs/xgo/DEVTOOL_WRAPPER.md`,
but the live `.devtool.toml` lost both `[targets.xgo_wrapper]` and the
`[wrapper]` command map.

That leaves the launcher half-installed because it delegates entirely to
`devtool wrapper exec`.

## Final-gate semantics

`xgo_final_wave12` is intentionally only the final Wave-12 evidence verifier.
It is a one-job leaf target and is expected to complete quickly.

`xgo_gate_final` is the authoritative Gate-12 workflow. It executes, in order:

1. `xgo_seal#validate`
2. `xdm_android#validate`
3. `xdm_modern#validate`
4. `xgo_final_wave12#validate`

The restored wrapper exposes these distinctly:

- `./devtoolw final-wave12` -> leaf verifier only;
- `./devtoolw gate-final` (aliases `final`, `gate`) -> authoritative full gate.

The existing wrapper documentation and validator already encode this
distinction; this repair restores the missing `.devtool.toml` authority.

## Scope

No build, Android, Desktop, XGO seal, or Gate-12 implementation logic is
changed. This is configuration convergence only.
