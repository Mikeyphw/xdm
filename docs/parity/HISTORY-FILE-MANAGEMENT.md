# Overlay 19 — history and file management

Overlay 19 completes the modern history/file-management workflow without
reintroducing legacy UI targets.

## Retention

History retention is opt-in. The policy combines an age limit with a maximum
entry count and applies only to terminal entries (`Completed`, `Failed`, and
`Cancelled`). Queued, paused, connecting, downloading, and finalizing work is
never removed by retention. Automatic and manual pruning delete history entries
only; downloaded files and partial data remain untouched.

The download manager preserves each entry's real last-update timestamp instead
of refreshing every record whenever the history file is saved. Startup applies
retention before restoring sessions and publishes the restored history to the
application state in one batch.

## Removal and local-file actions

The UI presents distinct operations:

- remove a history entry while keeping all local data;
- remove history plus partial/finalization/segment data;
- delete the completed file and its history entry.

Completed downloads can be moved or renamed. Publication is transactional from
the user's perspective: the source remains in place if the copy/move fails, a
private temporary target is used for cross-volume copies, and history changes
only after the target is durable. Existing targets require an explicit
overwrite choice.

The desktop platform service can open a completed file, reveal its destination,
open its HTTP(S) download URL, or open its persisted HTTP(S) source page. It does
not accept command lines or non-web URL schemes.

## Re-download and expired URLs

A GET history entry can be queued again with its filename, category, queue,
connection count, priority, request metadata still available in memory, and
source page. Auto-rename remains the default so an existing completed file is
not overwritten.

Failed, cancelled, or paused entries can replace an expired HTTP(S) URL while
keeping partial data. Refresh clears stale ETag/Last-Modified validators, records
the optional source page, and leaves the entry paused for an explicit resume.
Captured POST bodies remain non-replayable after restart.

## Download-list transfer

The versioned JSON format exports only safe workflow metadata:

- source URL and optional source page;
- filename and destination directory;
- category, queue, connection count, and priority.

Credentials, passwords, cookies, authorization headers, arbitrary headers, and
POST bodies are never exported. Import accepts version-1 JSON or a plain text
URL list, limits input to 8 MiB and 50,000 entries, permits only absolute HTTP
and HTTPS URLs, normalizes filenames/identifiers, and ignores unsafe entries.
Exports use a temporary file and atomic replacement.

## Large histories

Application state now maintains an ID-to-index map for updates/removals and
restores persisted history through one `ReplaceDownloads` publication. The
Avalonia list continues to use `VirtualizingStackPanel`, while deterministic
10,000-entry tests cover replacement, aggregation, and repeated indexed updates.

## Validation scope

Only `app/XDM/XDM.Modern.sln` is restored, built, and tested. WPF, GTK,
WinForms, and MSIX remain inactive reference sources.
