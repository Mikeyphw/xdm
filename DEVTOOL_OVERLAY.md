# XDM Android master remediation Overlay 01: foundation gate repair

Target: `xdm_android`.

This schema-v2 devtool artifact declares the target, exact Android Gradle validation tasks, and the post-validation Git commit policy in `.devtool-artifact.json`.

## Manifest-controlled apply defaults

- `target`: `xdm_android`
- `validation.tasks`:
  - `:app:compileDebugKotlin`
  - `:app:testDebugUnitTest`
  - `:app:lintDebug`
- `apply.commit.enabled`: `true`
- `apply.commit.strategy`: `single`
- `apply.commit.message`: `Apply XDM Android foundation gate repair`

CLI flags still override the manifest: `--target`, `--task` / `--test-task`, `--no-validate`, `--commit`, and `--no-commit` all take precedence.

## Patch scope

Restores the missing Debug Workbench UI/package, restores the Debug Center FileProvider class referenced by the manifest, removes the private absolute-path leak from Debug ZIP fallback sharing, adds Add Download IME padding, and updates static release validators/gates to the current 0.21.0 / Room 17 repository state.

## Apply

```bash
devtool apply-overlay xdm_android_foundation_gate_repair_overlay_v2.zip
```

The manifest supplies the target, validation task list, and commit message.
