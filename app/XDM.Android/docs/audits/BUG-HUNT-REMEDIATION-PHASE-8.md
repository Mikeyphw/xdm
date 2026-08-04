# Android Bug Hunt Remediation Phase 8

Phase 8 implements the roadmap's download-action and UI-truthfulness gate on top of landed Phase 7 commit `f7f16b6`. It does not change the Room schema.

## Action execution boundary

- `DownloadActionPlanner` now receives `DownloadActionContext`, so availability is derived from the real queue position, current verification evidence, validated partial state, provider capabilities, backend compatibility, and readable post-processing input.
- Queued `Start now` calls the queue coordinator directly. It never routes through pause/resume toggling.
- Verifying and Repairing expose Details and Cancel, not a fake Pause action.
- Rename and delete are implemented by `DownloadArtifactActionManager` on `Dispatchers.IO` using the owning Android provider. SAF documents use `DocumentsContract`; MediaStore uses exact-item operations; raw files are accepted only inside canonical XDM-owned roots.
- A direct file/document URI is not misrepresented as a containing-folder URI. `Open provider location` is hidden until XDM has a real tree or collection identity; `Open file` remains the truthful primary completed action.
- Storage deletion must succeed before graph deletion. Download-entry deletion stops active ownership first and verifies the row is gone before reporting success.
- Refresh URL and Redownload preserve the original selected destination, queue, conflict policy, backend preference and fallback setting, checksum expectations, encrypted request envelope where host policy allows, tags, and the global post-processing rules. For completed downloads, the original destination is recovered from the durable finalization journal rather than reusing the completed item URI.
- Recovery, Locate File, and Restart From Zero route to Recovery with the exact download and requested action selected.

## Truthful list and details model

- Active means Connecting, Downloading, Verifying, Repairing, or Finalizing. Paused has a separate filter and is never labeled “up next.”
- Queue position is computed from queue priority and creation order. Impossible move actions are disabled. Resume copy claims reusable partial bytes only after Room reports a safe recovery artifact, a non-empty durable checkpoint, or active backend ownership with a persisted partial identity.
- Payload-byte progress is separate from verification, repair, and destination-commit progress.
- Completed is labeled Verified only when a passed verification record or matching checksum exists.
- Stale speed is shown only while Downloading.
- Destination surfaces use friendly provider/location labels. The raw Android URI appears only behind an explicit sensitive Copy Android URI action.
- Completed details expose capability-aware Open, Share, Rename, Delete file, Delete entry, Delete file and entry, friendly-location copy, and URI copy actions.
- Source URL and file-information copy actions use persistence-safe or redacted data and sensitive clipboard metadata where applicable.
- Post-processing actions are hidden unless the completed input is currently readable.
- Backend migration is offered only when a compatible alternative supports the source and destination.

## Android API basis

- `DocumentsContract.renameDocument()` and `deleteDocument()` are used only when provider flags expose those operations.
- File sharing uses a canonical content URI and a temporary `FLAG_GRANT_READ_URI_PERMISSION` grant.
- Sensitive clipboard values use `ClipDescription.EXTRA_IS_SENSITIVE` on supported Android versions.
- Provider and storage I/O are performed outside the main thread.

Official references checked during implementation:

- Android DocumentsContract and shared-document operations
- Android secure file sharing with FileProvider and temporary URI permissions
- Android secure clipboard handling and sensitive-clipboard metadata

## Regression coverage

Primary executable model tests cover:

- no Pause during Verifying or Repairing;
- direct Start Now and real queue movement availability;
- completed capability-aware actions;
- provider-denied mutation actions;
- exact recovery action routing;
- verification-backed Completed labels;
- paused filtering and real queue position;
- stale-speed suppression;
- payload versus overall progress.

The Phase 8 source validator is a secondary gate. It verifies production wiring, executes the harmonized historical UI validators, and is included in CI and the final release gate.


## Phase 8 r2 gap closure

- Active history deletion now commits through `deleteDownloadGraphIfTerminal`, a Room transaction that rechecks the terminal state and observed update generation before deleting the full graph. If runtime ownership changes the row between cancel and delete, deletion is refused.
- Redownload now clones explicit per-download post-processing job/rule records with a new immutable job generation linked to the new download, while preserving global rules.
- Legacy file-management copy no longer prints raw source URLs or Android destination URIs; it emits a persistable safe source or redaction plus a friendly saved-location label.
- Details selection and bulk selection reconcile against the currently visible workspace result, so filtered or searched-away items cannot stay open as stale details.
