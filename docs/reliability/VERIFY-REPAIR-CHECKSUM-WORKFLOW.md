# Verify, selective repair, and checksum workflow

## Goals

XDM keeps verification independent from downloading and treats destructive restart as a separate operation.
A transfer may be verified repeatedly without network traffic. When repair is requested, XDM validates the
remote object, preserves known-good byte ranges, replaces only missing or mismatching ranges, verifies all
configured expected checksums, and finalizes a partial file only after verification succeeds.

## Checksum state

Each destination has an atomic sidecar named `<destination>.xdm.checksums.json`. It persists:

- expected SHA-256 and SHA-512 values;
- calculated SHA-256 and SHA-512 values;
- the last verification timestamp and match result;
- whether the value is only a locally generated integrity record;
- separate verification progress counters.

Legacy single-algorithm checksum fields and Metalink hashes are migrated into the dedicated SHA fields.
Download-list schema version 4 exports and imports both values.

When no independent expected checksum is available, XDM calculates SHA-256 as a local integrity record.
The UI labels this as a local record rather than presenting it as proof that the remote payload was correct.

## Selective repair

A verified file also receives a block manifest named `<local-file>.xdm.repair.json`. The manifest contains
SHA-256 hashes for 4 MiB blocks together with the remote ETag and Last-Modified validator when available.

Repair follows these rules:

1. The transfer must be inactive and use HTTP or HTTPS GET.
2. XDM validates range support, total length, ETag, and Last-Modified before writing.
3. A missing `.xdm.part` may be reconstructed from persisted segment files at their planned offsets.
4. Blocks whose current local hashes still match the verified manifest are not requested again.
5. Suspicious, truncated, or missing blocks are downloaded with bounded byte-range requests.
6. A range is written only when its remote hash differs from the local block.
7. The checkpoint is rebuilt after a repaired partial file reaches the expected length.
8. All configured expected checksums are calculated in one pass.
9. A partial file is promoted atomically only when every configured checksum matches.

Without an expected checksum, selective repair requires a strong ETag or Last-Modified validator. Weak ETags
do not qualify as safe range identity validators.

## User actions

The download details panel exposes:

- separate expected and calculated SHA-256/SHA-512 values;
- apply, clipboard-import, checksum-file import, and calculated-checksum copy actions;
- verification progress independent from download progress;
- Verify, Verify and repair, and Restart from zero as distinct commands.

`Verify` performs no network request. `Verify and repair` preserves matching bytes. `Restart from zero` remains
the explicit destructive fallback and preserves a suspect completed file before resetting transfer artifacts.

## Finalization continuity

When a verified `.xdm.part` file is atomically promoted, its trusted block manifest is promoted to the final destination as well. This preserves known-good block hashes for the first later selective repair. A stale destination manifest is removed when no matching partial manifest exists.
