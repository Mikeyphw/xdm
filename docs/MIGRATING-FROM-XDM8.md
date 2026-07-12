# Migrating from legacy XDM 8

## Before importing

1. Exit every legacy XDM process.
2. Back up the old configuration, download database, and unfinished download files.
3. Keep the old installer/package until the modern preview is verified.
4. Do not point both applications at the same writable state directory concurrently.

## Import supported settings

Open **Settings → Import, export, and XDM 8 migration**. Choose either a settings
file or a legacy settings directory, then select **Import or migrate**.

The modern importer accepts:

- modern XDM JSON exports;
- legacy JSON settings;
- Java-style `.properties` settings;
- XML settings with key/name entries.

Directory import searches for `settings.json`, `config.json`,
`config.properties`, `settings.properties`, `xdm.properties`, `config.xml`, or
`settings.xml`. The importer migrates download folders, concurrency and speed
limits, retry and timeout policy, segmented-transfer thresholds, proxy settings,
clipboard behavior, duplicate-file behavior, categories, and queues when those
values are present. Missing categories or queues are preserved from the current
modern configuration.

Review imported proxy credentials and host credentials before saving or
exporting. Modern exports redact passwords unless **Include proxy and server
passwords in export** is explicitly enabled.

## State locations

The modern application uses an `xdm-modern` application-data directory and
keeps settings, download history, browser authentication state, diagnostics,
and recovery markers separate from legacy XDM where the operating system permits.

## First launch checks

1. Confirm the download destination and network settings.
2. Review categories, queues, and schedules.
3. Restart XDM after changing proxy, timeout, retry, or segmented-transfer policy.
4. Open Browser Integration and install or repair the native host.
5. Test with a small resumable download before importing important work.

## Partial downloads

Do not rename or move `.part`, validator, segment, or finalization marker files
while XDM is running. A partial file can only be resumed safely when its source
URL and HTTP validators still match. If a server ignores ranges or changes
content, XDM restarts safely instead of appending incompatible bytes.

## Rollback

1. Exit modern XDM.
2. Preserve its application-data directory for diagnostics.
3. Restore the legacy configuration backup.
4. Reinstall or launch the legacy package.

Modern and legacy state formats are not mutually writable.
