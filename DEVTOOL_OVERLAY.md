# XDM Overlay 20 — localization and accessibility

Base: confirmed successful commit `86633eb`

Target: `xdm_modern`

Active solution: `app/XDM/XDM.Modern.sln`

## Included

- Stable embedded English resources plus bounded migration of retained XDM language packs.
- Persisted language selection and operating-system language preference.
- Runtime translated binding refresh and English fallback.
- Right-to-left flow for RTL cultures.
- Locale-aware sizes, rates, durations, aggregate speed, and status labels.
- Persisted high-contrast mode, 75–175% UI scaling, and screen-reader announcements.
- Accessible names, live operation status, tab navigation, and keyboard shortcuts.
- Legacy language/accessibility settings migration.
- Deterministic localization, formatting, settings, and accessibility-surface tests.
- Executable parity ledger and documentation updates.

## Safety

Language packs are data-only and bounded by file size, entry count, key length,
and value length. Index paths are reduced to file names. No markup, scripts, or
commands are evaluated from translations. Unknown or missing translations fall
back to embedded English resources.

## Validation scope

Only the modern solution is allowed:

```bash
dotnet restore app/XDM/XDM.Modern.sln
dotnet build app/XDM/XDM.Modern.sln --configuration Release --no-restore
dotnet test app/XDM/XDM.Modern.sln --configuration Release --no-build
```
