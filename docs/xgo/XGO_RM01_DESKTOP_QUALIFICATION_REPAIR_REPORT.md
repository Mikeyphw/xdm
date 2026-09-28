# XGO-RM01 Desktop Gate-12 Qualification Repair Report

## Trigger

Authoritative `xdm_modern` validation restored successfully, then failed build
in `DesktopGoCompatibilityServices.cs` because the active host still referenced
the compile-quarantined `XDM.DownloadEngine.Queues` contract surface.

The subsequent `dotnet test --no-build` invocation used previously-built test
assemblies because the new build had failed. That stale binary reported an
`ExpectedSchemaVersion = 0` assertion even though the fresh repository source
already requires the XGO-87 canonical value `1`.

## Root cause

XGO-88 intentionally quarantined the historical queue scheduler implementation,
but the neutral `IQueueSchedulerRuntime` interface was colocated in the same
source file. Excluding the donor implementation therefore also removed a
contract still needed by active Desktop compatibility code.

This is an ownership-placement defect, not a reason to revive the retired C#
scheduler authority.

## Repair

The neutral interface now lives in `XDM.Core.Scheduling`. Active Platform/App
surfaces consume that Core contract and no longer import
`XDM.DownloadEngine.Queues`.

The historical donor queue sources remain untouched and compile-quarantined.

A regression test locks:
- Core ownership of `IQueueSchedulerRuntime`;
- XGO-88 queue-source quarantine;
- absence of the donor namespace from active consumers;
- `DesktopGoQueueSchedulerRuntime` as the active compatibility implementation;
- XGO-87 `ExpectedSchemaVersion = 1` source truth.

## Gate position

Before XGO-RM01:
- `xgo_seal`: PASS
- `xdm_android`: PASS
- `xdm_modern`: FAIL
- `xgo_final_wave12`: blocked

After applying XGO-RM01, rerun `xdm_modern` in full. Only a full pass advances
Gate-12 to `xgo_final_wave12`.
