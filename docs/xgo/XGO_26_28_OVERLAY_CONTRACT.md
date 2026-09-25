# XGO-26..28 Overlay Contract

- Target: `xgo_transfer`
- Validation required: yes
- Failure action: `pause`
- Deferred validation: no
- `validation.tasks`: absent; the target workflow remains authoritative.
- Execution environment: repository target remains `native-termux`.

## Authority boundaries

- Probe metadata is descriptive and cannot itself authorize byte reuse.
- Representation compatibility is decided before persisted bytes are reused.
- Weak validators fail closed unless an explicit weaker policy applies, and weak ETag never qualifies for byte identity.
- Transfer bytes become resumable only through the durable checkpoint committer.
- Pause is acknowledged only after the current durable checkpoint boundary.
- Fresh transfers reject unsolicited partial responses; resumed transfers require exact Content-Range compatibility.
- Durable attempt transitions use the current attempt generation/revision and preserve the canonical state machine.

## Deferred work

Random-access segmented staging, full resume/If-Range repair, retry arbitration, bandwidth sharing, checksums, selective repair and finalization remain XGO-29..35.
