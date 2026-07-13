# XDM Overlay — Organization and duplicate prevention

This overlay applies on top of commit `595bc5d`.

## Organization

- adds normalized per-download tags that persist in history and portable download lists;
- adds saved searches/smart collections with built-in Active, Needs attention, Duplicates, Missing files, and Archive collections;
- adds query operators for `status:`, `tag:`, `site:`, `queue:`, `category:`, `archived:`, `missing:`, `duplicate:`, and byte-size comparisons such as `size:>1GB`;
- hides archived items by default while allowing explicit archive searches;
- protects archived history entries from age and count pruning;
- adds a manual library refresh for moved/missing files.

## Duplicate prevention

- adds configurable duplicate-URL behavior: focus the existing item, reject the new request, or allow both;
- normalizes URL identity across host casing, default ports, and fragments;
- makes duplicate admission atomic for concurrent URL additions;
- computes optional SHA-256 content identities after completion;
- links later downloads to matching completed content;
- reuses a manual SHA-256 verification result instead of hashing the file twice;
- preserves URL-duplicate relationships when equal URLs later produce different content.

## Destination and filename handling

- adds ordered destination rules matching host suffix and/or file extension;
- rules can select a destination, category, and tags;
- shows a pre-add destination and filename-conflict preview;
- keeps the existing overwrite, auto-rename, resume, and skip behavior as the execution policy;
- routes only through saved/active rules so unsaved Settings edits never misrepresent actual behavior.

## Archive and relink workflows

- adds Archive/Restore for terminal downloads;
- adds missing-file indicators;
- safely relinks a history item to an existing file;
- rejects relinking while a transfer is active or when another download owns the destination;
- refreshes content identity after relinking and clears stale identity metadata first.

## Migration and quality

- upgrades application settings to schema version 8;
- upgrades portable download lists to schema version 3 while retaining version 1 and 2 compatibility;
- adds deterministic tests for rule matching, search parsing, tag normalization, URL identity, URL/content duplicate handling, relinking, archive retention, list round-trips, migration, and UI exposure;
- updates every `IDownloadManager` test double for the organization API and keeps analyzer diagnostics at zero;
- makes `DownloadManager.Dispose()` quiescent by cancelling and draining active transfers, checkpoint writes, history persistence, and adopted aria2 finalization before releasing gates;
- prevents shutdown races from recreating partial/checkpoint files while callers remove or relocate the destination directory;
- preserves all existing accessibility labels and automation identifiers;
- does not modify `docs/parity/features.json`.

## Validation

Devtool must restore, build, and test only `app/XDM/XDM.Modern.sln`. The build must have zero warnings and zero errors.
