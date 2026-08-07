# Runtime Foundation Phase 55–56 Promise-Closure Audit

## Result

The first Phase 55–56 artifact delivered the core aria2 repair and All Files Access path, but five roadmap promises were incomplete. The r2 cumulative overlay closes all five.

## Gap 1 — aria2 startup diagnostics were still too coarse

The first implementation separated connection, unauthorized, HTTP/RPC, process-exit, timeout, launch, cleanup, and authentication-boundary failures, but did not explicitly preserve malformed RPC responses, invalid aria2 configuration, occupied listen-port failures, or Android linker/native-load failures.

**Closure:** dedicated `MalformedResponse`, `ConfigurationInvalid`, `PortUnavailable`, and `BinaryLoadFailure` categories plus bounded-log classification and protocol exceptions. Unauthorized and other actionable failures retain precedence over a later polling timeout.

## Gap 2 — the aria2 smoke test stopped at getVersion

The roadmap acceptance gate promised a real RPC/download lifecycle rather than authentication alone.

**Closure:** the smoke test now hosts a local loopback payload and exercises authenticated `addUri`, `tellStatus`, pause/unpause, completion, output-byte verification, `saveSession`, `removeDownloadResult`, and managed shutdown. No public network is required.

## Gap 3 — Storage Doctor was missing

The roadmap explicitly promised a destination doctor covering permission and filesystem durability plus backend visibility.

**Closure:** the doctor now targets the selected direct folder when one is active and verifies permission, mkdir, create, write+fsync, rename, read-back, and delete; it exercises the native backend's exact destination staging/promotion contract, embedded aria2 must write an actual loopback payload there, and the existing Termux command bridge then requires yt-dlp and FFmpeg to execute and write probe output there. A Termux failure is surfaced rather than hidden because Termux runs under a different Android UID.

## Gap 4 — custom ordinary direct paths were not exposed

The first artifact only surfaced fixed `Download/XDM`.

**Closure:** Settings now accepts an absolute custom path inside primary shared storage, rejects relative/outside-root paths, and explicitly rejects other apps' `Android/data` and `Android/obb` trees. File-URI directory destinations append the requested download filename rather than treating the directory URI as a file.

## Gap 5 — app-process restart could leave an owned aria2 daemon orphaned

The earlier implementation tracked only the `java.lang.Process` object in memory. If Android killed/restarted the XDM app process while its packaged aria2 child remained alive, the new app process had no durable proof tying that loopback RPC endpoint to XDM.

**Closure:** `Aria2SessionStore` now persists an owner-only runtime lease containing the loopback port, RPC-secret generation, and startup timestamp, but never the secret itself. On the next XDM process start, a same-generation daemon must answer authenticated RPC before it is classified as an XDM-owned orphan. Only then does XDM save the session and request shutdown. Stale-generation or unreachable markers are cleared without killing an unproven process; a daemon that authenticates but refuses shutdown is reported as a distinct orphan-recovery failure.

## Non-regressions retained

- MediaStore fallback remains available.
- SAF `OpenDocumentTree` remains available.
- `content://` destinations remain on `ContentResolver`; `File(uri.path)` remains forbidden.
- Room schema remains 17.
- The media-capture protocol remains deferred to later roadmap phases.


### App-process restart ownership closure

Phase 55 now persists an app-private aria2 runtime ownership lease containing only the loopback endpoint, secret generation, and start timestamp, never the secret itself. On a later XDM process start, a matching-generation daemon must answer authenticated RPC before XDM treats it as its own. Proven XDM-owned orphan daemons are session-saved and shut down over authenticated RPC before a fresh runtime is launched; stale or unreachable markers are cleared without killing an unproven process.
