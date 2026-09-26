# XGO-70 + XGO-71 Overlay Contract

Target: `xgo_android`

Validation is required and may not be deferred. Do not apply this milestone with `--no-validate`.

The target validates real Android network/platform dispatch wiring, the production WorkManager/FGS/UIDT/boot scheduler paths, and an executable Go runtime round trip for `android.scheduler_wake`.

No Devtool reinstall, refresh, bootstrap, or source-install hook is permitted in this overlay.

XGO-72 is intentionally excluded.
