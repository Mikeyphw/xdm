# XDM Desktop REM06 — Native HTTP/FTP transfer integrity, resume identity, retry, and disk safety

## Scope

Closes the REM06 ownership set from the frozen S00–S16 desktop audit: S03-01 through S03-15.

**15 findings: 7 High / 7 Medium / 1 Low.**

## Implementation

- HTTP resume now requires a strong ETag or Last-Modified identity. Existing partial bytes without a trustworthy validator are preserved as stale recovery artifacts and the transfer restarts from byte zero rather than blindly appending.
- FTP resume now requires a previously persisted MDTM value and validates it against the server before issuing REST. Known SIZE/expected length is checked both before and after transfer, so a truncated data stream cannot be promoted as complete.
- Segmented downloads require a stable validator before segmentation is allowed. Segment directories now carry an atomic identity-generation sidecar; unidentified or mismatched old parts are discarded before reuse, preventing cross-generation byte mixtures.
- ExpectedLength remains authoritative even when an HTTP response is chunked/unknown-length, and completed byte counts are validated before finalization.
- Finalization journals now persist whether overwrite was actually admitted. Promotion and recovery re-check a late-created destination immediately before replacement and refuse to overwrite it when the request was non-overwrite.
- HTTP response-header, response-stream, and body-read operations are bounded by the committed RequestTimeout setting. FTP DNS/connect/TLS/control/data reads use committed connect/request timeout policy as well.
- Automatic HTTP content decompression is disabled for the native transfer client so byte-range coordinates, Content-Length, checkpoint offsets, and on-disk bytes describe the same representation.
- Retry classification now retries transport failures, HTTP 408/429, and 5xx responses but not permanent 4xx failures.
- Retry and segmented-download executors are rebuilt from committed settings when settings change instead of remaining frozen at DownloadManager construction time.
- Unix free-space lookup resolves the filesystem containing the destination path rather than assuming `/` represents the destination mount.
- FTP output is asynchronously flushed and then flushed to durable storage before completion/finalization is reported.
- Transfer byte and disk-capacity arithmetic is checked; overflow is converted into the normal integrity/failure path.

## Regression coverage

- Validator-less HTTP partials restart from zero and preserve the untrusted partial.
- Validated resume still exercises ignored-range restart behavior.
- Range-satisfied checksum finalization carries validator identity.
- Unknown-length HTTP bodies still enforce request ExpectedLength.
- Segmented resume requires a matching persisted generation and discards mismatched generations.
- Validator-less range probes fall back to a single stream instead of mixing segment generations.
- FTP resume test supplies the expected MDTM identity.
- Permanent HTTP statuses are non-transient while throttling/server failures remain retryable.
- Finalization refuses a destination created after admission unless overwrite was explicitly authorized.

## Validation policy

REM06 is an intermediate remediation overlay. Apply with `--no-validate` under the remediation campaign policy. The final remediation seal owns the full build/xUnit/integration gate; this overlay nevertheless adds focused regression contracts for its integrity semantics.
