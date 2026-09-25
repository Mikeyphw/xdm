# XGO language-neutral fixture contract

Schema version 1 fixtures live below `engine/testdata/<category>/`.

Required fields:

- `schema_version`
- `fixture_id`
- `category`
- `capability_ids`
- `donor_ids`
- `derivation`
- `input`
- `expected.invariants`

The initial corpus intentionally describes behavior rather than Kotlin/C# object serialization. Stable fixture IDs are bound one-to-one to the initial capability ledger. Later overlays may enrich an individual fixture with protocol packets, files, timelines, or state-machine inputs, but must preserve its ID and capability linkage.

High-risk categories have at least one detailed concrete scenario at XGO-02. Literal credentials are forbidden; placeholders such as `<secret-ref>` are used instead.
