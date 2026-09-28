# XGO-RM01 Overlay Contract - Desktop Gate-12 Qualification Repair

## Ownership

XGO-RM01 is a Gate-12 remediation item discovered during authoritative
`xdm_modern` host qualification after XGO-89. It does not reopen or redefine
the Wave-12 capability ledger.

## Findings owned

1. XGO-88 correctly compile-quarantined `Queues/QueueSchedulerRuntime.cs` and
   `Queues/QueueDependencyEvaluator.cs`, but the neutral
   `IQueueSchedulerRuntime` host/UI contract remained declared only inside the
   quarantined scheduler source.
2. Active `XDM.Platform`/`XDM.App` code still imported
   `XDM.DownloadEngine.Queues`, so the full Desktop build could no longer
   resolve the interface after donor authority was excluded.
3. The failed `dotnet test --no-build` run reported a schema-0 assertion from a
   stale previously-built test assembly. The fresh repository source already
   contains the correct XGO-87 assertion `ExpectedSchemaVersion = 1`; RM01 does
   not mutate that file.

## Resolution

- add the neutral `IQueueSchedulerRuntime` contract under `XDM.Core.Scheduling`;
- remove the stale quarantined queue namespace import from active Platform/App
  consumers, which already import `XDM.Core.Scheduling`;
- leave the historical donor queue implementation source and its XGO-88 compile
  quarantine intact;
- add a regression contract proving Core ownership, donor quarantine, active
  namespace hygiene and schema-1 source truth.

## Preserved invariants

- no legacy queue scheduler authority is re-enabled;
- `QueueSchedulerRuntime.cs` remains compile-quarantined;
- `QueueDependencyEvaluator.cs` remains compile-quarantined;
- `DesktopGoQueueSchedulerRuntime` remains the active compatibility implementation;
- Go remains the canonical scheduler/transfer authority;
- XGO-87 schema version `1` remains authoritative;
- Gate-12 remains a separate qualification boundary.

## Qualification

Apply this implementation artifact with `--no-validate`, then rerun the full
authoritative `xdm_modern` validation. A successful build will regenerate test
assemblies before `dotnet test --no-build`, eliminating the stale binary that
produced the misleading schema-0 failure.
