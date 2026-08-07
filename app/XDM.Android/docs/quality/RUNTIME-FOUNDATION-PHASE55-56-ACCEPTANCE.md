# Runtime Foundation Phase 55–56 Acceptance

Baseline: `xdm-20260807-110547.tar.gz`.

This is the Phase 55–56 promise-closure acceptance contract. The r2 overlay is cumulative and supersedes the first Phase 55–56 artifact.

## Phase 55: Embedded aria2 runtime repair

- [x] Loopback RPC remains secret-authenticated.
- [x] Connection refused, unauthorized RPC, HTTP failure, malformed response, generic RPC failure, invalid configuration, occupied RPC port, binary/linker load failure, process exit, timeout, launch failure, config-cleanup failure, and authentication-boundary failure are distinguishable.
- [x] Temporary secret-bearing launch configurations are removed after startup.
- [x] Runtime log tail is bounded and redacted, including credential query values.
- [x] Runtime state exposes process ID, endpoint, RPC-secret generation, and startup timestamp.
- [x] Authenticated `aria2.getVersion` is followed by a deliberate unauthenticated probe that must be rejected.
- [x] Smoke test performs a loopback transfer and exercises `addUri`, `tellStatus`, pause/unpause, completion, `saveSession`, `removeDownloadResult`, and shutdown.
- [x] Repair stops the managed runtime, clears stale launch configs, rotates the app-private secret, and starts a fresh daemon.
- [x] An app-private runtime ownership lease survives an XDM process restart; XDM only reclaims an orphan daemon after authenticated RPC proves it still owns that daemon, and stale/unreachable markers never authorize killing an unproven process.
- [x] Developer UI exposes both smoke test and repair.

## Phase 56: Personal direct storage + SAF compatibility

- [x] Personal sideload build declares `MANAGE_EXTERNAL_STORAGE`.
- [x] Direct `Download/XDM` destination is first-class when Android grants all-files access.
- [x] Settings can grant/use direct storage.
- [x] Add Download can grant/use direct storage.
- [x] Settings exposes a guarded custom absolute direct path inside primary shared storage.
- [x] Relative/outside-shared-storage paths and other apps' `Android/data` / `Android/obb` trees are rejected.
- [x] Storage Doctor verifies permission, mkdir, create, write+fsync, rename, read, and delete.
- [x] Storage Doctor targets the currently selected direct folder when applicable.
- [x] Storage Doctor exercises the native backend destination staging/promotion contract and verifies the published bytes.
- [x] Storage Doctor makes embedded aria2 write a loopback probe into the same direct folder.
- [x] Storage Doctor runs a Termux path probe that requires both yt-dlp and FFmpeg to execute and write probe output in the same directory.
- [x] MediaStore Public Downloads remains available without broad access.
- [x] SAF `OpenDocumentTree` remains available for document-provider destinations.
- [x] `content://` destinations are not converted with `File(uri.path)`.
- [x] Room schema remains 17.

## Local validation

- Runtime Foundation promise-closure source validator: passed.
- Previous Phase 58 runtime-recovery guard validator: passed.
- Previous Phase 65 diagnostic/action validator: passed.
- Aria2 production manager/model sources: `kotlinc -Werror` focused compile passed with dependency stubs.
- Aria2 behavior runner: authenticated startup, unauthenticated rejection, loopback add/tell/pause/unpause/completion/save/remove-result/shutdown, storage-path write, repair/secret rotation, diagnostic precedence, and log redaction passed.
- Direct-storage production sources: `kotlinc -Werror` focused compile passed with Android API stubs.
- Direct-storage behavior runner: permission, mkdir, create, write+fsync, rename, read, delete, and cleanup passed.
- Termux storage probe source contract requires quoted target paths, yt-dlp, FFmpeg, writable output, cleanup, and an explicit pass marker.
- Full Gradle/Android validation is deferred to Devtool because Gradle 9.4.1 is not cached in this sandbox and network download is unavailable.


### App-process restart ownership closure

Phase 55 now persists an app-private aria2 runtime ownership lease containing only the loopback endpoint, secret generation, and start timestamp, never the secret itself. On a later XDM process start, a matching-generation daemon must answer authenticated RPC before XDM treats it as its own. Proven XDM-owned orphan daemons are session-saved and shut down over authenticated RPC before a fresh runtime is launched; stale or unreachable markers are cleared without killing an unproven process.
