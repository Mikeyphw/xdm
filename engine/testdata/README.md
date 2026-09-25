# XGO language-neutral fixtures

Every capability in `engine/docs/capability-ledger.yaml` owns exactly one initial fixture ID.

These fixtures are behavioral specifications, not serialized Kotlin/C# objects. Later implementation overlays may enrich the input/expected shape while keeping the stable fixture ID.

`fixture-registry.json` is the canonical fixture index. `tools/xgo/audit_fixtures.py` validates ledger/donor cross-links and rejects probable literal secrets.
