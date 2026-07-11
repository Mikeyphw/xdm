# Migrating from legacy XDM 8

## Before installing

1. Exit every XDM process.
2. Back up the old configuration, download database, and unfinished download files.
3. Keep the old installer/package available until the preview is verified.
4. Do not point both applications at the same writable state directory concurrently.

## State locations

The modern application uses an `xdm-modern` application-data directory and
keeps settings, download history, browser authentication state, diagnostics,
and recovery markers separate from legacy XDM where the operating system permits.

## First launch

1. Start the modern application normally.
2. Confirm the download destination and network settings.
3. Recreate or verify categories, queues, schedules, proxies, and credentials.
4. Open Browser Integration and install/repair the native host.
5. Test with a small resumable download before importing important work.

## Partial downloads

Do not rename or move `.part`, validator, or finalization marker files while XDM
is running. A partial file can only be resumed safely when its source URL and
HTTP validators still match. If a server ignores ranges or changes content, XDM
restarts safely instead of appending incompatible bytes.

## Rollback

1. Exit modern XDM.
2. Preserve its application-data directory for diagnostics.
3. Restore the legacy configuration backup.
4. Reinstall or launch the legacy package.

Modern and legacy state formats are not guaranteed to be mutually writable.
