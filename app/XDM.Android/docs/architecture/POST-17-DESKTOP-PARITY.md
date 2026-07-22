# Post-17 Desktop Parity

This slice ports the remaining desktop-parity surfaces into the existing Android routes after the Phase 17 public gate.

## Scope

- Settings import/export uses a portable text snapshot from the Settings route.
- History/file management uses existing download history records and never deletes completed files from storage when clearing app history.
- Proxy and credentials UI stores only redacted proxy profile metadata; passwords are intentionally not exported.
- Conversion/post-processing exposes a policy surface and named hook choices without enabling silent command execution.
- Protocol expansion polish summarizes Native and aria2 protocol coverage, including FTP, SFTP, Magnet, Metalink, HLS, and DASH routing guidance.
- Release/non-debug packaging is handled by `tools/build-release-artifacts.sh`, which builds beta or release APKs and writes SHA-256 checksum files.

## Topography

No new top-level route is added. Downloads owns history actions, Settings owns import/export/proxy/conversion/protocol/package surfaces, and Diagnostics reports parity readiness.

## Persistence

Room remains locked at schema v14. Preferences live in DataStore and settings export deliberately omits secrets.
