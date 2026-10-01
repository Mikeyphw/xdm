# Gate-12 temporary Devtool artifact-registry bypass

## Purpose

This is a narrow, opt-in continuation lane for a confirmed Devtool Android packaging inconsistency observed after XGO RM08 v10. Devtool's Android package runner completed Gradle successfully, discovered the expected app and androidTest APKs, and copied them, but its subsequent artifact registry reported `0 valid, 0 invalid, 0 total` and raised `ARTIFACT_MISSING`.

The workaround does **not** change the canonical `xgo_gate_final_continue#validate` workflow and does not disable artifact verification globally. It adds a separate `registry_bypass` workflow that must be invoked explicitly.

## Safety boundary

The bypass skips only the failing `xgo_rm08_aria2_android_package#validate` registry/publication boundary. Before any downstream Gate-12 work proceeds, the retained APKs are independently checked for ZIP integrity and expected native members; repository-owned aria2 and FFmpeg/FFprobe APK verifiers are rerun; an explicit JSON bypass receipt captures APK sizes and SHA-256 digests; and the canonical `xgo_rm08_aria2_attest#validate` exact packaged-byte receipt still runs.

After that evidence exists, the workflow resumes the existing Android artifact checkpoint, RM05 residual precheck, lint, required-device instrumentation, desktop host validation, and final Wave-12 verifier.

## Invocation

```sh
devtool --yes --copy \
  -r "$HOME/Code/xgo-unified-go-engine" \
  --target xgo_gate_final_continue \
  invoke registry_bypass
```

## Removal condition

Remove this workflow, its receipt job/script, and its contract target after Devtool repairs Android package artifact publication such that a successful discovered/copied APK is durably registered instead of producing `ARTIFACT_MISSING` / an empty registry.
