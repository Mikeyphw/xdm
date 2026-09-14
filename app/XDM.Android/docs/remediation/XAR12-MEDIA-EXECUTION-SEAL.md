# XAR12 — Native HLS, Embedded FFmpeg & Termux Execution Seal

Overlay **12 of 17** closes the S12 media execution and post-processing section of the 313-finding XDM Android remediation ledger.

## Canonical roots closed

`S12-01`, `S12-02`, `S12-03`, `S12-04`, `S12-05`, `S12-06`, `S12-07`, `S12-08`, `S12-09`, `S12-10`, `S12-11`, `S12-12`, `S12-13`, `S12-14`, `S12-15`, `DS6-S12-01`, `DS6-S12-02`, `DS6-S12-03`, `DS6-S12-04`, `DS6-S12-05`, `DS6-S12-06`, `DS6-S12-07`, `RERUN56-S12-01`.

## Native HLS execution changes

Native HLS now treats `EXT-X-GAP` as a durable skipped part, includes `EXT-X-DISCONTINUITY-SEQUENCE` in durable part identity, and tracks implicit `EXT-X-BYTERANGE` offsets per resource URL. `EXT-X-MAP` bytes are written only when the active initialization-map identity changes instead of being prepended to every segment. Segment URIs without a preceding `EXTINF` are rejected instead of receiving an invented 1 ms duration.

AES-128 handling is fail-closed: a KEY tag without a URI blocks native execution, explicit IVs must be exactly 16 bytes encoded as 32 hex characters, and malformed IVs are never padded or truncated.

Remote HLS fetches are bounded by manifest/init-map/segment/key byte caps. OkHttp calls remain associated with body streaming so pause/cancel can abort I/O after response headers arrive. Byte-range responses must return HTTP 206 with a Content-Range that exactly matches the requested range and valid total semantics. Sensitive headers are scoped by full origin, including scheme and port.

## Publication and cancellation ownership

Native HLS cancellation and interruption first reconcile the XAR06 publication journal. If the destination was already committed, the job is completed from committed evidence instead of deleting staging artifacts or downgrading the row to Cancelled/Failed.

## Embedded FFmpeg and Termux boundary

Embedded FFmpeg operations run through a pure media execution security policy before invocation. FFmpeg command compilation now applies a wall-clock timeout derived from the expected duration, with safe minimum and maximum bounds. FFprobe verification fails when a media attempt has an expected duration but the probe cannot report duration.

Termux fallback is limited to public HTTPS, headerless, non-private-network media sessions. Termux control verifies PID start ticks plus wrapper/process-group ownership instead of looking for the old `stdin-fifo` marker in command lines. Transient Termux bridge artifacts are represented as app-private paths and documented as never staged in shared Downloads.

## Validation

`tools/validate-xar12-media-execution-seal.py` verifies every S12 canonical root, the Gradle task `verifyXar12MediaExecutionSeal`, final-gate wiring, manifest coverage, native HLS semantics, FFmpeg/Termux execution boundaries, and the documentation contract.

## XFE01 Firefox-extension invariant

The Android Firefox extension is the canonical working implementation. XAR12 does not alter production extension capture/handoff logic; desktop integration remains responsible for adapting around the Android-owned extension contract.
