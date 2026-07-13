# Reliable resume integrity and crash recovery

This phase hardens XDM transfers against process crashes, power loss, stale partial files, changed remote content, failed finalization, and corrupted completed files.

## Transactional artifacts

Every in-progress destination uses explicit XDM-owned artifacts:

- `<file>.xdm.part` — the transactional partial file;
- `<file>.xdm.resume.json` — an atomically replaced resume checkpoint;
- `<file>.xdm.finalizing` — a durable finalization marker;
- `<file>.segments/` — resumable segmented-transfer parts.

Legacy `.part` and `.finalizing` artifacts are migrated automatically. Checkpoints and finalization markers are written through temporary files and flushed to stable storage before replacement. The final move from `.xdm.part` to the destination remains on the same filesystem so the rename is atomic.

## Resume validation

A checkpoint records the active source, mirrors, downloaded and total bytes, connection count, ETag, Last-Modified timestamp, checksum expectation, segment lengths, and update time.

Before appending data, XDM validates:

- Content-Range starts at the local partial length;
- total length has not changed;
- an existing ETag is still present and unchanged;
- an existing Last-Modified value is still present and unchanged;
- a `416 Range Not Satisfiable` completion path still passes checksum verification.

A newly queued download adopts an orphaned single-stream partial only when its durable checkpoint matches the requested source, destination, expected size, and checksum metadata. Unmatched artifacts are preserved with `.stale-*` names and the new transfer starts cleanly. Legacy segmented-part directories remain migratable for compatibility: XDM probes the server, validates the range contract, bounds every segment against the new plan, and immediately writes the modern durable checkpoint before continuing.

## Integrity verification and repair

Downloads may carry an expected SHA-256 or SHA-512 checksum. XDM verifies it before final rename and leaves a mismatching `.xdm.part` intact for diagnosis or repair. Completed downloads can also be verified manually; when no expected checksum exists, Verify records a SHA-256 fingerprint.

Repair preserves an existing suspect final file as `.corrupt-*`, clears resumable transfer artifacts and validators, and queues a clean transfer using the original source/mirror set. Expected length and checksum metadata remain enforced.

## Crash recovery

On startup XDM reconciles history, checkpoints, actual partial length, segmented parts, and finalization markers. Interrupted active transfers return as paused recoverable downloads. A valid completed partial can finish finalization without being downloaded again. Final completion is serialized with checkpoint persistence, preventing a late checkpoint writer from recreating stale recovery state after the final rename. Missing, oversized, malformed, or mismatched state is surfaced for recovery review instead of being silently trusted.

## Mirrors and Metalink

A download may define ordered HTTP, HTTPS, FTP, or FTPS mirrors. After normal jittered retries are exhausted, XDM switches source and restarts from zero to avoid mixing bytes from different servers. The active mirror is persisted in checkpoints so a crash during failover resumes consistently.

Metalink v4 imports support ordered URLs, declared size, SHA-256, and SHA-512. XML DTDs and external entities are prohibited, document size and source counts are bounded, and unsupported entries are ignored.

## Persistence and portability

History snapshots and XDM download-list exports retain expected checksums, expected lengths, mirrors, actual checksum, verification time, recovery state, and integrity status. Existing JSON remains compatible because all added fields are optional.

## Deterministic HTTP fault laboratory

The download-engine test project includes a loopback HTTP/1.1 fault server that exercises the real `SocketsHttpHandler` path instead of only mocked `HttpMessageHandler` responses. Tests can deterministically vary status codes, headers, declared body length, bytes actually written, entity validators, and behavior by request attempt.

The initial reliability matrix covers:

- interrupted responses followed by a validated range resume;
- changed entity tags on a `416 Range Not Satisfiable` completion candidate;
- contradictory `Content-Range` and `Content-Length` values;
- real socket behavior for premature response termination.

The same fixture can model ignored ranges, redirects, expiring URLs, authentication challenges, rate limiting, incorrect lengths, and mirror failures without calling public internet services. TLS, proxy, HLS, DASH, and very-large logical-file scenarios remain separate extensions because they require protocol-specific listeners or fixtures.
