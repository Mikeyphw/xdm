# XGO-80 overlay contract

- Roadmap item: XGO-80
- Target: `xgo_desktop`
- Specialized validation node: `desktop_legacy_authority_scan`
- Manifest validation: required
- Failure action: pause
- Maximum warnings: 0
- Devtool bootstrap/install hooks: none
- Merge policy: standalone; GATE-11, XGO-81, and XGO-82 are not bundled

## Required invariants

1. Desktop legacy state is imported at most once into a durable Go authority snapshot.
2. Duplicate identical import is idempotent; changed legacy state cannot overwrite established Go authority.
3. An interrupted/failed authority commit does not mark the cutover complete and may be retried.
4. Unreadable download history cannot silently become an empty committed cutover; readable atomic backup is used when available.
5. Go owns canonical desktop download IDs and backend task references.
6. Normal App DI/startup cannot instantiate legacy DownloadManager, C# recovery authority, C# queue scheduler authority, stateful aria2 owner, or old media download service.
7. The host download broker performs only bounded physical I/O/stateless RPC and contains no queue/retry/media-planning policy engine.
8. Android and desktop identify their host platform explicitly through the same C ABI create contract.
9. Donor engine sources may remain test-only until XGO-81 differential classification, but no normal production execution path may reach them as authority.
