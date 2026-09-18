# Post-roadmap runtime hotfix

This hotfix addresses device-observed defects after RM01-RM04: HLS byte totals that mirrored downloaded bytes, missing ongoing download notifications on Android 16, Activity rows that could not be removed when they had an action, and aria2 exit-code-1 diagnostics that hid the first useful runtime-log line.

HLS totals are now shown only when every countable segment has an independently known size. XDM probes ordinary unencrypted segment metadata with a one-byte range request and uses Content-Range/Content-Length when exposed by the CDN. If any segment cannot be sized, byte total remains unknown and part-count progress remains authoritative. Completed segments no longer overwrite expected size with downloaded size.

User-visible transfers use the dataSync foreground-service path, which owns a stable ongoing drawer notification and falls back to WorkManager when Android rejects the FGS start.

Activity actionable rows now retain their primary action and also expose Remove. The Activity screen exposes Clear activity, which clears only the presentation ledger/decision history and does not delete downloads, files, or recovery source-of-truth records.

aria2 ProcessExited diagnostics now surface the first sanitized runtime-log line alongside exit code so the next device smoke identifies the concrete startup failure instead of only reporting code 1.
