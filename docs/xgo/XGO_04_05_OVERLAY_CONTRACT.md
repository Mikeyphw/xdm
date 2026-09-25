# XGO-04 + XGO-05 overlay contract

## Covered roadmap items

- XGO-04 — Go workspace, toolchain and deterministic test infrastructure
- XGO-05 — canonical typed identities and immutable value primitives

These adjacent foundation items are combined because XGO-05 is the first
direct consumer of the workspace created by XGO-04 and both share one
`xgo_foundation` validation boundary.

## Devtool ownership

Artifact target: `xgo_foundation`

`xgo_foundation` is promoted from the bootstrap command runner to Devtool's
native Go runner. The repository root contains `go.work`; the actual module
remains `engine/go.mod`. Existing Python donor/fixture/topology jobs stay
target-local and repository-relative.

Validation DAG begins with:

```text
runner:go#validate
  -> gofmt_check
  -> foundation_tests
  -> donor_audit
  -> capability_ledger_audit
  -> fixture_lint
  -> fixture_secret_scan
  -> devtool_topology_audit
```

`runner:go#validate` owns restore, real executable build, `go test -json`,
and `go vet`. The only new command validator is `gofmt_check`, because
formatting is not a native Go-runner validation phase.

## Authoritative outcomes

1. XGO has a real Go workspace that builds independently of Gradle/.NET.
2. Wall time and monotonic elapsed time are separately injectable.
3. Identifier entropy is injected; tests use deterministic generators.
4. Entity IDs are distinct named types with canonical prefixed encoding.
5. Attempt/artifact generations and row revisions are independent positive
   counters and fail on overflow.
6. Canonical Resource Identity is opaque and does not retain raw locator
   material.
7. SensitiveText formats and JSON-serializes redacted by default.
8. Foundation/domain import boundaries are covered by Go tests.

## Intentional deferrals

- No filesystem abstraction is introduced yet because no current foundation
  behavior needs filesystem fault injection; XGO-11+ introduces storage
  seams where they are actually required.
- Generated protobuf API types do not exist until XGO-10. XGO-05 freezes
  canonical scalar representations and JSON validation now; XGO-10 maps
  these scalars into the versioned protobuf envelope without making domain
  packages import protobuf.
- Full fuzz execution and race campaigns remain later gate work. Seed fuzz
  functions are introduced now and execute their seed corpus under normal
  `go test`.

## Exit criteria

- Devtool native Go validation passes on `xgo_foundation`.
- xgo-smoke is discovered and built by Devtool.
- all Go tests and vet pass.
- gofmt audit is empty.
- existing donor/capability/fixture/topology jobs still pass.
- four foundation capability fixtures resolve and are marked IMPLEMENTED.
