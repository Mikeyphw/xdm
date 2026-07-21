# Phase 10: Optional Root Mode

Phase 10 adds a narrow root foundation for XDM Android's Termux backend. Root is optional, off by default, and restricted to typed operations that are useful for power users without turning the app into a shell launcher.

## Included actions

- Probe root availability through Termux `su`.
- Collect process diagnostics for XDM, Termux, aria2, FFmpeg, FFprobe, and yt-dlp.
- Terminate only a stuck Termux aria2 daemon matching XDM's managed RPC port.
- Repair permissions for XDM/download-scoped outputs.
- Provide guarded foundations for future completed-file move actions.

## Guardrails

- No chroot support.
- No raw shell UI.
- Root mode is `Off` by default.
- Medium-risk actions require root mode plus a successful root probe.
- All root actions append visible audit records and diagnostics markers.
