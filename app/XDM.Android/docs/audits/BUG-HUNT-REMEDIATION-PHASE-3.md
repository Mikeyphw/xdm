# Bug Hunt Remediation Phase 3: Storage, Publication, Verification, And Repair

Phase 3 hardens the path between completed backend bytes and a user-visible completed artifact. The phase treats publication as a journaled transaction instead of a best-effort copy, keeps completed artifact health explicit, and rejects repair paths that cannot prove range identity.

## Implemented contracts

- Destination publication writes a journal before commit and after reconciliation.
- Filesystem publication attempts atomic move, then fsyncs the parent directory where supported.
- Content destinations use collision-resistant staging directories and provider-safe filenames.
- MediaStore lookup uses exact `RELATIVE_PATH = ?` matching instead of prefix `LIKE` matching.
- MediaStore publication requires a nonzero update count, re-queries `IS_PENDING`, and checks the final byte size before reporting completion.
- Completed artifact health can be represented as Present, Missing, PermissionLost, ProviderChanged, SizeMismatch, PendingPublication, or Unknown.
- Capacity planning accounts for remaining transfer bytes plus content-destination publication copy overhead.
- Checksum user input has a strict parser that accepts exactly one bare SHA-256 or SHA-512 hexadecimal digest.
- Selective repair writes into a temporary artifact, requires HTTP 206 for partial ranges, verifies exact `Content-Range` and body length, sends `If-Range`, rejects trailing bytes, and preserves the original artifact on failure.
- Finalization journal records include committed URI, attempt generation, artifact generation, checksum algorithm, expectation id, expected digest, actual digest, and verification timestamp fields.

## Explicit non-goals for this intermediate overlay

This overlay is intended for apply-only chaining until the final validation phase. It does not add a database schema bump, top-level route, all-files permission, or automatic destructive cleanup behavior.

## Failure-prevention lessons carried forward

- Do not include `.devtool-artifact/*` entries in repository inventory.
- Keep repository contract tests aligned with the project architecture rather than string-locking an obsolete call site.
- Preserve existing browser-removal and external-handoff contracts while adding storage safety.
- Include `commit_message` in the JSON artifact manifest so Devtool history and review logs can name the phase cleanly.
