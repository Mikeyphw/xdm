# XGO donor baseline

This document freezes the behavioral donor inputs for the XDM unified Go-engine migration.

## Repository roles

- Migration workspace: `~/Code/xgo-unified-go-engine`
- Untouched donor/reference checkout: `~/Code/xdm`
- Donor-root default used by validation: `../xdm`

The donor checkout is never modified by XGO overlays. XGO validation reads it only to verify the selected behavioral fingerprint and to report Git identity.

## Identity strategy

The source archive used to author XGO-00/01 does not contain `.git`, so this overlay does **not** invent a Git commit hash.

Donor identity is established by two complementary pieces of evidence:

1. `engine/docs/donor-map.yaml` pins 80 primary implementation/test files by repository-relative path, byte size, and SHA-256.
2. The Devtool `donor_audit` target-local job runs against the real `~/Code/xdm` checkout, requires it to be a Git worktree with no tracked modifications, verifies every pinned file, and records the actual Git HEAD and branch in `.devtool/reports/xgo/foundation/donor-audit.json`.

Selected donor aggregate SHA-256:

```text
e1170e288c4ddbcd1c9e13c2a7a05416fbf0b4d08752c6657168c25a7176e6dc
```

The aggregate is computed from sorted `(donor_id, path, sha256)` tuples. It is deliberately a behavioral-source fingerprint rather than a hash of generated files, build outputs, caches, or the entire repository.

## Donor hierarchy

There is no universal desktop-wins or Android-wins rule.

```text
Go behavior =
    strongest Android behavior
  ∪ strongest Desktop behavior
  ∪ deliberate architectural corrections
```

Android is the principal donor for durable attempt/backend ownership, native HTTP safety/integrity, checkpoint integrity, recovery, publication, capture identity, logical media graph, durable HLS execution, and privacy/security boundaries.

Desktop is the principal or major donor for POST/body replay, FTP/FTPS, Metalink, generic aria2 RPC mechanics, dependency scheduling, proxy/PAC semantics, live HLS, and DASH execution.

The capability ledger records the donor strategy separately for every capability.

## Validation rule

XGO-00/01 originally bootstrapped `xgo_foundation` as a minimal command target. That historical bootstrap is no longer the current execution shape: XGO-04 promoted `xgo_foundation` to the native Go runner, and later waves extended the same target-owned validation DAG instead of replacing it with a custom XGO validation framework.

The donor-freeze evidence remains authoritative through the current `xgo_foundation#validate` workflow. The Wave 0 specification nodes are still present and ordered inside that Devtool-native DAG:

1. `foundation_tests`
2. `donor_audit`
3. `capability_ledger_audit`
4. `fixture_lint`
5. `fixture_secret_scan`
6. `devtool_topology_audit`

`xgo_gate_spec#validate` composes `target:xgo_foundation#validate`, so Wave 0 can still be revalidated as one EXO-visible graph after later roadmap waves. EXO remains the authoritative execution evidence; domain reports under `.devtool/reports/xgo/foundation/` are supporting artifacts only.
