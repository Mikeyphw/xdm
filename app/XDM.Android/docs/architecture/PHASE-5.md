# Phase 5 — Android storage and destination system

Phase 5 replaces the previous app-owned-file boundary with a destination-neutral staging and promotion contract.

## Implemented providers

- MediaStore: Downloads, Movies, Music, Pictures, and Documents.
- Storage Access Framework tree URIs, including removable SD-card providers.
- Direct document URIs where supplied by an integration.
- App-private and regular file destinations for tests and internal workflows.

## Write lifecycle

1. Resolve and health-check the destination.
2. Reserve a backend-neutral destination identity that includes the filename.
3. Write native transfer bytes to app-private `.xdm.part` staging.
4. Flush and checkpoint staging data durably.
5. Create or open the destination according to the conflict policy.
6. Copy to content providers, flush, and call `FileDescriptor.sync()`.
7. Clear MediaStore `IS_PENDING` or commit the SAF document.
8. Remove private staging artifacts only after successful commit.

Regular files attempt an atomic rename and fall back to replacement move where the filesystem does not support it.

## Permissions and recovery

Selected SAF trees are persisted with read/write grants and recorded in Room. Destination health distinguishes healthy, missing permission, unavailable, read-only, low-space, and unknown states. A revoked grant produces an actionable storage error instead of silently redirecting the download.

## Conflict handling

The request persists one of: overwrite, resume, rename, skip, or compare. Rename is the default. Policies that require user comparison or cannot be completed safely stop before destination commit.
