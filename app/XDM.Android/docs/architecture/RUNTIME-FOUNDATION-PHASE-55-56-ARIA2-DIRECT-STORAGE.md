# Runtime Foundation Phase 55–56: aria2 Runtime Repair and Personal Direct Storage

## Scope

This cumulative promise-closure implementation starts the 2026 runtime/storage/media reliability roadmap without changing the media-capture protocol yet. It supersedes the first Phase 55–56 overlay and applies directly to `xdm-20260807-110547.tar.gz`.

## Phase 55 — embedded aria2 runtime repair

- Keeps aria2 RPC bound to loopback and authenticated with a private random secret.
- Classifies connection-refused, unauthorized, HTTP, malformed-RPC, generic RPC, invalid-configuration, occupied-port, binary/linker, timeout, process-exit, launch, cleanup, and authentication-boundary failures separately.
- Preserves the most actionable startup failure rather than replacing it with a later polling timeout.
- Tracks the managed process ID, endpoint, secret generation, startup timestamp, exit code where available, and a bounded redacted runtime-log tail.
- Verifies authenticated `aria2.getVersion` succeeds and an unauthenticated `aria2.getVersion` is rejected.
- Runs a real local lifecycle smoke test through `addUri -> tellStatus -> pause/unpause -> completion -> saveSession -> removeDownloadResult -> shutdown`, using a loopback payload instead of the public network.
- Adds an explicit repair action that stops the XDM-owned daemon, clears stale `launch-*.conf` files, rotates the RPC secret, and starts a fresh managed daemon.
- Never exposes the secret, Cookie, Authorization, bearer values, or credential query values in UI diagnostics.

## Phase 56 — personal direct storage plus SAF compatibility

This repository is being used as a personal sideloaded build. It therefore declares `MANAGE_EXTERNAL_STORAGE` and exposes direct shared-storage destinations as a first-class option.

- Built-in Direct Downloads maps to the normal shared `Download/XDM` filesystem directory.
- A custom direct folder can be entered as an absolute path inside primary shared storage. Relative paths and paths outside shared storage are rejected. Other apps' `Android/data` and `Android/obb` trees are explicitly rejected.
- Android 11+ users are sent to the app-specific All Files Access settings screen before a direct destination is selected.
- If broad access is not granted, MediaStore and SAF remain available.
- SAF destinations continue to use persisted URI grants and `ContentResolver`/`DocumentsContract`.
- A `content://` DocumentsProvider URI is never converted to a fake `File(uri.path)` path.
- The Storage Doctor verifies permission, mkdir, create, write+fsync, rename, read-back, and delete on the currently selected direct folder (falling back to `Download/XDM` when a non-direct destination is selected); it then exercises the native backend's prepared-destination staging/promotion contract, makes embedded aria2 perform a loopback transfer into the same directory, and asks the existing Termux bridge to prove yt-dlp and FFmpeg can execute while writing probe output there.
- The Termux portion is intentionally diagnostic: because Termux is a separate Android UID, XDM's All Files Access grant does not grant Termux its own shared-storage permission. A failure therefore reports the real cross-app storage boundary rather than pretending success.
- No Room migration is introduced; schema remains 17.

## Deferred to the next runtime overlay

- Engine-wide destination/finalization rewrite for every backend.
- User-visible non-silent media intake states.
- Firefox capture-session protocol and secure request-context handoff.
- HLS/DASH execution changes.


### App-process restart ownership closure

Phase 55 now persists an app-private aria2 runtime ownership lease containing only the loopback endpoint, secret generation, and start timestamp, never the secret itself. On a later XDM process start, a matching-generation daemon must answer authenticated RPC before XDM treats it as its own. Proven XDM-owned orphan daemons are session-saved and shut down over authenticated RPC before a fresh runtime is launched; stale or unreachable markers are cleared without killing an unproven process.
