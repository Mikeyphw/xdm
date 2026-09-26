# XGO-62..64 overlay contract

Target: `xgo_ops`

Validation nodes:

1. native Go validation for `engine/ops` and `engine/cmd/xgo-ops-audit`
2. `settings_audit`
3. `secret_scan`
4. `diagnostics_stress`

The overlay must not reinstall or refresh Devtool. It must require validation and pause on validation failure.
