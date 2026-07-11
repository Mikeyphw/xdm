# Avalonia essential workflows

Phase 4 keeps `XDM.Modern.sln` as the only active solution and does not restore or build WPF, GTK, WinForms, or MSIX projects.

## Added

- Multiline batch URL parsing with duplicate URL removal.
- Custom filename for single-URL jobs.
- Arbitrary request headers, Basic authentication, cookies, referer, and user-agent fields.
- Auto-rename, overwrite, and skip collision policies.
- Atomic `settings.json` writes with one known-good backup and corrupt-file quarantine.
- Persisted download directory, clipboard options, concurrency, speed limit, categories, queues, and scheduler configuration.
- Clipboard polling through Avalonia 12 `TryGetTextAsync`.
- Startup concurrency gate and live default/per-download speed throttling.
- Queue/category selectors for newly created downloads.
- Tests for batch parsing, headers, authentication, collision renaming, and settings persistence.

## Transitional limitations

Queue and category IDs are carried by new download requests but are not yet stored in download history. Queue scheduler execution will be connected during the next engine-management overlay. Credentials and raw headers are deliberately memory-only.
