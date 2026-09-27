# GATE-11 desktop compile hotfix

## Scope

This hotfix repairs the three desktop build blockers first exposed when GATE-11 ran the real `XDM.Modern.sln` Release build after XGO-80. It does not change the Gate 11 orchestration, Go authority boundaries, or `.devtool.toml`.

## Repairs

1. `DesktopGoDownloadExecutionBroker` now calls the actual low-level `Aria2RpcClient.AddUriAsync` API and forwards the normalized desktop aria2 split count and minimum split size. This preserves XGO-80's narrow stateless RPC mechanism and does not restore `Aria2Service` as an authority.
2. `DesktopLegacyStateImporter` now caches a dedicated `JsonSerializerDefaults.Web` options instance for legacy `downloads.json` deserialization. It intentionally remains separate from the snake_case serializer used for the Go import package.
3. `DesktopGoPublicationBroker` now implements `IDisposable` and disposes its owned `SemaphoreSlim`, satisfying the analyzer ownership contract without changing publication semantics.

## Gate relationship

GATE-11 itself did not modify any of the three failing source files. After applying this hotfix, rerun the existing `xgo_gate11_cross_host_full_seal_v1` overlay so the full Release build and tests can continue beyond the point that previously failed.
