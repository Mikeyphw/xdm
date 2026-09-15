# XDM Desktop REM18 Final Release Seal

REM18 is **overlay 18 of 18** in the desktop remediation roadmap. It closes the final cross-boundary audit items:

- `S13-12` — real OS/process semantics need executable coverage.
- `S14-10` — diagnostic support bundles need end-to-end privacy proof.
- `S16-07` — final tests must exercise shutdown/concurrency invariants.

## Execution model

Devtool must apply the REM18 overlay with validation enabled. The artifact manifest declares the runner-native validation task sequence:

1. `restore`
2. `build`
3. `test`
4. `package`

The repository `.devtool.toml` maps the package step to `app/XDM/eng/rem18-final-release-seal.sh --devtool-package-step`, so the final package/evidence phase runs after Devtool has completed the normal .NET restore/build/test pipeline.

## Evidence generated

The final seal writes evidence under `artifacts/rem18-final-seal/`:

- `ledger-audit.json`
- `release-matrix-audit.json`
- package and smoke logs for the runnable platform
- `final-seal-summary.json` or `final-seal-summary.windows.json`

## Release blocking rules

Release remains blocked unless:

- The closure ledger contains exactly 258 unique findings.
- Severity totals remain 67 High, 153 Medium, and 38 Low.
- Every finding is `Closed` with regression evidence.
- Linux/Windows `x64` and `arm64` RIDs remain declared in workflow, script, and package contracts.
- Stable Windows releases require signing secrets and successful signature verification.
- Diagnostic/support-bundle tests prove tokens, cookies, URI userinfo, and local private paths are redacted.
- Shutdown, browser, aria2, FFmpeg, media workspace, and updater rollback fault-injection scenarios are represented in the REM18 matrix.


## XFE01 convergence requirement

In the unified roadmap, REM18 is merged overlay 2 of 8 and runs after XFE01. The final seal therefore also validates the single Firefox extension contract:

- `app/XDM.Android/browser-extension/src/main/extension/xdm-firefox` remains the canonical Firefox source.
- Desktop packages `XDM-Firefox.xpi` from that source through `package-canonical-firefox-extension.py`.
- `validate-xfe01-single-firefox-extension.py` passes before the REM18 ledger and release-matrix audits.
- Desktop integration adapts around the Android extension's `xdmdownload://add?v=1` and `xdmdownload://capture?v=3` handoff contracts.
