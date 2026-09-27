# GATE-11 cross-host contract

- Target: `xgo_gate_cross_host`.
- Validation is required and non-deferred for the overlay.
- The gate is lean: it validates the boundary being closed and does not recursively invoke historical store/security/transfer/media gates.
- Android evidence is the focused `xgo_android#validate` workflow because the full Android build was sealed by GATE-10.
- Desktop evidence includes both `xgo_desktop#validate` and the native `xdm_modern#validate` runner fallback (`dotnet restore`, Release build, Release tests).
- Android and Desktop must use the same canonical `engine/api/v1` metadata tuple.
- GATE-11 does not install or refresh Devtool and does not bootstrap external toolchains inside the overlay.
