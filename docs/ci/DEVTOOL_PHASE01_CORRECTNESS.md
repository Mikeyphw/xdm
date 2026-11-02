# XDM / Devtool Phase 0–1 correctness milestone

Source baseline: `Mikeyphw/xdm` branch `xgo-unified-go-engine`, commit
`d0ccbcd8ff512d52447bf86e4f6492b0d68145ad`.

This milestone is intentionally scoped to the **existing** local/hosted source
contract. It does not start a Gradle build, dispatch GitHub Actions, reinstall
an Android package, mutate a real device, sign a release or retire Gate-12.

## Changes

1. Update the existing wrapper verifier to Devtool wrapper schema 2 and the
   existing generated `devtoolw` PATH-fallback spelling. Cover existing RM07,
   RM08 and CI aliases instead of misclassifying them as unexpected commands.
2. Fix all target-root-relative input/output declarations under
   `app/XDM.Android` while allowing parent-relative shared inputs.
3. Fix `xgo_ci` job input scopes (the `engine` job root is repository-relative).
4. Use pytest to collect both unittest cases and standalone pytest tests in the
   complete `tools/xgo/tests` directory and repair the JNI test's repository-root resolution. The previous unittest discovery did
   not execute standalone test functions.
5. Preserve the exit code of the REM18 Firefox validator without a POSIX shell
   pipeline masking its failure.
6. Extend topology and source contract audits, plus add unit regressions and an
   evidence-only baseline inventory.

## Local read-only checks

```sh
cd ~/Code/xdm
python3 scripts/devtool_phase01_baseline.py --output .devtool/reports/xgo/phase01-baseline.json
python3 scripts/devtool_actions_contract.py
python3 tools/xgo/validate-devtool-wrapper.py
python3 tools/xgo/audit_topology.py audit
python3 -m pytest -q tools/xgo/tests
python3 -m unittest tools.xgo.tests.test_phase01_devtool_contract
```

The `pytest` command requires an installed pytest runtime; no dependencies are
silently installed. Optional runtime-level checks with the locally installed
Devtool follow and may identify additional platform-specific behavior:

```sh
./devtoolw wrapper-check
# Inspect available plan/graph commands before running workflows.
devtool -r "$PWD" workflows --json
```

## Acceptance and limitations

- **Source-level complete:** wrapper verifier, ordered source contract, topology
  input/output scope audit and all Python suite collection must pass.
- **Execution not claimed:** no full Devtool-native workflow was executed by
  installation. Test success, Android build, artifact registration, real device,
  release-signing and hosted acceptance require separate evidence.
- **Not Phase 2:** the 33 `root = "."` targets and affected-target optimization
  are deliberately deferred to source-ownership redesign. No parallelism is
  enabled here. Android and .NET CI adoption is also deferred.
- **No claims of CI release trust:** source JSON/sha hashes and a green audit do
  not prove artifact provenance or independent signing.
