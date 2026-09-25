# XGO-38 Implementation Report

## Delivered

- Added `engine/transfer/metalink` as a metadata parser/expander rather than a parallel downloader.
- Parses Metalink v4 direct file/source/hash layout and the legacy `files/resources/verification` wrapper used by older Metalink documents.
- Parses file name, identity, description, version, language/OS metadata, expected size, whole-file hashes, mirror URLs, location hints, priority and legacy preference hints.
- Reuses `transfer/checksum.NormalizeExpected` so Metalink accepts exactly the canonical verifier algorithms (`md5`, `sha1`, `sha256`, `sha512`) and canonical lowercase digests.
- Fails closed on malformed XML, unsupported hash algorithms, conflicting same-algorithm hashes, conflicting size evidence, duplicate file names and oversized documents.
- Rejects unsafe Metalink output-name traversal/absolute/Windows-drive forms while preserving legal relative subdirectory names.
- Expands each file to the existing `domain/request.NetworkIntent`: bodyless GET, primary source, normalized/deduplicated mirrors, expected length and checksum metadata.
- Preserves source order when no hint exists; lower Metalink v4 priority wins, and higher legacy preference wins when used.
- Multi-file documents derive deterministic distinct child resource identities instead of reusing one logical resource for multiple artifacts.
- Closed `XGO-CAP-METALINK-001` and promoted the Metalink fixture to a six-case detailed contract.

## Validation topology

`xgo_backends#validate` now runs:

1. native Go validate,
2. `backend_contract_audit`,
3. `post_replay_lab`,
4. `ftp_lab`,
5. `metalink_corpus`.

`metalink_corpus` covers valid Metalink expansion, malformed XML, unsupported hashes, duplicate source URLs, conflicting size/hash evidence and a generated canonical-intent fixture. Native Go tests add legacy wrapper support, multi-file resource identity, document-size bounds and path-traversal rejection.

## Merge-window decision

After XGO-37 passed, the required merge window was XGO-38..40. XGO-38 remains standalone because it is an XML/integrity-metadata normalization surface, while XGO-39/40 jointly define backend capability inspection and deterministic routing. Combining parser failure modes with routing policy would worsen fault isolation. After XGO-38 passes, the next required merge window is XGO-39..41; XGO-39+40 are expected to be strong merge candidates, but the decision must be re-evaluated against the then-current tree.

## Audit-loop evidence

The audit loop found and fixed two gaps before packaging:

- legacy Metalink `files/file` documents were not initially recognized even though legacy `resources`/`verification` children were supported;
- Metalink file names needed explicit traversal/absolute-path rejection because RFC-style names may later influence host publication paths.

After those fixes:

- focused Metalink package tests: pass,
- `metalink_corpus`: 6/6 pass,
- shared capability-ledger audit: pass,
- shared fixture audit: pass,
- `backend_contract_audit`: pass,
- all `./engine/...` tests: pass,
- scoped `go vet`: pass.

The exact packaged artifact is separately re-applied to a clean post-XGO-37 baseline before delivery. No Devtool reinstall or refresh hook is included.
