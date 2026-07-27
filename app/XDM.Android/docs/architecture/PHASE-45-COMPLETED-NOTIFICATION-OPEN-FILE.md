# Phase 45 — Completed notification open-file intent

Phase 45 makes completed download notifications behave like a finished-download affordance instead of a generic app launcher.

## Behavior

- Completed notification tap opens a non-exported trampoline with only the download id in the `PendingIntent`.
- The trampoline looks up the download at tap time and only continues when the record is still `Completed`.
- Completed notification action opens XDM/download details instead of launching a file viewer.
- Failed, paused, and recovery notifications continue to open XDM details by default.
- Mute/Retry/Resume actions stay routed through the existing broadcast receiver.

## Safety contract

The notification never stores a raw file URI. `OpenDownloadedFileActivity` resolves the completed file after the tap, converts file destinations to a temporary `FileProvider` content URI, grants read permission only to the external viewer intent, and falls back to XDM when the file is missing, no viewer exists, or a URI permission fails.

## Persistence contract

When the backend reports a concrete completed URI, the runtime stores that URI back into the completed download record. This preserves MediaStore/content destinations for later notification taps and avoids trying to reopen virtual destination roots such as `xdm://mediastore/downloads`.
