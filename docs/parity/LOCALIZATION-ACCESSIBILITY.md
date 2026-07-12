# Localization and accessibility parity

Overlay 20 migrates the retained XDM language packs into the active Avalonia
application and completes the planned keyboard, screen-reader, contrast,
scaling, right-to-left, and locale-formatting work.

## Resource model

The modern shell owns stable keys in `Localization/strings.en.json`. At runtime,
`LocalizationService` loads that embedded English catalog and the bounded legacy
packs copied from `app/XDM/Lang`. Exact English values are mapped back to legacy
keys, allowing existing translations to cover matching modern labels while new
or changed text falls back safely to English.

Language files are treated only as data. The parser caps each file at 1 MiB,
reads at most 4,096 entries, limits key/value lengths, strips file-system paths
from the index, and never evaluates markup or executable content.

## Runtime behavior

The persisted settings schema stores either a selected language or the operating
system language preference. Saving settings updates thread culture, translated
bindings, status labels, download value formatting, navigation labels, and the
window flow direction without restarting XDM. Arabic and Farsi/Persian use RTL
flow based on culture metadata.

## Accessibility

The main shell provides:

- predictable tab navigation and direct focus for the URL and search fields;
- Ctrl+1 through Ctrl+8 section navigation;
- Ctrl+N, Ctrl+F, Ctrl+P, Ctrl+R, and Escape workflow shortcuts;
- accessible names for primary navigation and input surfaces;
- a configurable polite live region for operation status;
- a high-contrast class with explicit foreground/background/border treatment;
- persisted 75–175% UI scaling through an Avalonia layout transform.

## Deterministic coverage

Tests validate legacy fallback and RTL metadata, culture-aware sizes/rates and
durations, scale clamping, stable resource keys, automation properties,
keyboard wiring, live-region metadata, and settings migration.

## Validation scope

Only `app/XDM/XDM.Modern.sln` is restored, built, and tested. Legacy WPF, GTK,
WinForms, and MSIX projects remain inactive.
