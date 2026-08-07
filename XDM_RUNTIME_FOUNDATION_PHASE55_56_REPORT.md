# XDM Runtime Foundation Phase 55–56 r2 Promise-Closure Report

Authoritative baseline: `xdm-20260807-110547.tar.gz`

Baseline SHA-256: `d4ef875cd6ae14b4ea122206d3b837a11b1f305505f5c0853a6d53eb01afddb2`

This cumulative r2 supersedes the first Phase 55–56 overlay and is intended to apply directly to the authoritative baseline.

## Promise audit

The first artifact left four roadmap commitments incomplete:

1. aria2 diagnostics did not explicitly distinguish malformed RPC responses, invalid config, occupied port, and native/linker failures;
2. aria2 smoke testing verified authentication but not the promised transfer lifecycle;
3. the promised Storage Doctor was absent;
4. custom ordinary shared-storage paths were not exposed.

All four are closed in r2.

## Phase 55 delivered

- Loopback-only RPC with private secret and deliberate unauthenticated-rejection verification.
- Failure categories for connection, unauthorized, HTTP, malformed response, RPC, invalid configuration, occupied port, binary/linker load, process exit, timeout, launch, cleanup, and authentication boundary.
- Actionable failure precedence across startup polling.
- Managed-state process ID, endpoint, secret generation, startup timestamp, exit code where available, and bounded redacted log tail.
- Repair operation: stop managed daemon, remove stale transient launch configs, rotate secret, restart, reverify authentication.
- Deep local smoke: `addUri -> tellStatus -> pause/unpause -> completion -> saveSession -> removeDownloadResult -> shutdown`, with actual output-byte verification.

## Phase 56 delivered

- `MANAGE_EXTERNAL_STORAGE` for the personal sideloaded build.
- Built-in direct `Download/XDM` destination plus a guarded custom absolute shared-storage folder.
- Custom path restrictions: shared-storage root only; no relative path; no other-app `Android/data` / `Android/obb`.
- App-specific All Files Access settings intent plus general fallback.
- Settings and Add Download grant/use flows.
- Storage Doctor targets the selected direct folder when applicable: permission, mkdir, create, write+fsync, rename, read-back, delete.
- Native destination probe exercises the same prepared-destination staging/promotion path used by the native backend and verifies the published bytes.
- Embedded aria2 must write a loopback probe into the same direct folder.
- Termux path probe requires yt-dlp and FFmpeg to execute and write temporary probe output in the same direct folder.
- MediaStore and SAF remain available.
- DocumentsProvider `content://` URIs remain on `ContentResolver`; no `File(uri.path)` coercion.
- Room schema remains 17.

## Validation boundary

Focused Kotlin/source/behavior validation is performed during artifact construction and again after clean application. Full Android Gradle/Compose/test/lint/APK validation remains Devtool's authoritative target-machine gate because Gradle 9.4.1 is not locally cached in this construction sandbox.


### App-process restart ownership closure

Phase 55 now persists an app-private aria2 runtime ownership lease containing only the loopback endpoint, secret generation, and start timestamp, never the secret itself. On a later XDM process start, a matching-generation daemon must answer authenticated RPC before XDM treats it as its own. Proven XDM-owned orphan daemons are session-saved and shut down over authenticated RPC before a fresh runtime is launched; stale or unreachable markers are cleared without killing an unproven process.
