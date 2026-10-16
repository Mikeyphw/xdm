# Devtool orchestration — xgo-unified-go-engine

## Native local development

`./devtoolw ci-preflight`: source-only contract, zero package downloads and no release qualification. `./devtoolw ci-local`: multi-target dependency graph executed through Devtool EXO. `devtool -r "$PWD" workflows --json` shows target-local jobs and workflows. Devtool records execution evidence. Build, signing, and device qualification are independent.

## GitHub Actions

After a scoped commit and push, execute `devtool -r "$PWD" actions plan devtool-xgo-engine.yml --ref "$(git branch --show-current)" --json` followed by `actions run ... --yes --json`. Use `actions runs/view/watch/artifacts` to examine remote source-bound result and candidate archives. The installer never dispatches or pushes.

## Project notes

XGO already has Android, desktop, signed-release, EXO, and multi-target gates; this overlay only adds hosted Go engine smoke candidate and a local Go-foundation DAG. Do not use hosted CI as production release authority.
