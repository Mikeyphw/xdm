# XDM Desktop REM14 — Localization, Accessibility, Navigation, Mini-window, and Shell Parity

**Overlay:** REM14, overlay 14 of 18  
**Artifact:** `xdm_desktop_rem14_localization_accessibility_shell_parity_v1.tar.gz`  
**Target:** `xdm_modern`  
**Validation mode:** intermediate overlay; apply with `--no-validate`  
**Commit message:** `fix(desktop): implement REM14 localization accessibility and shell parity`

## Objective

Close the modern-shell accessibility, localization, navigation, mini-window, and Settings-choice defects without changing transfer engine semantics.

## Findings closed

- S01-08 — conversion navigation used localized display text as an identity key.
- S11-09 — legacy translation bridge covered only a small fraction of modern UI resources.
- S11-10 — visible XAML strings bypassed localization.
- S11-11 — MiniWindow did not participate in the main localization/accessibility path.
- S11-12 — system-language fallback ignored script/region and selected the first neutral-language match.
- S00-08 — accessibility test matrix was stale at eight page fixtures.
- S01-11 — numeric navigation/help stopped at eight sections despite a nine-section shell.
- S01-12 — mini-window transfer controls diverged from main-window action state.
- S01-13 — safe-mode behavior and safe-mode status copy disagreed about aria2.
- S11-14 — Hindi and Malagasy language packs were shipped/mapped but absent from the index.
- S11-15 — Settings choice controls exposed raw enum identifiers.

## Implementation summary

- Added complete localized keys for shell, page, mini-window, diagnostics, recovery, queue, scheduler, browser, conversion, and Settings-choice labels.
- Replaced localized display-text navigation identity with stable section-id navigation for conversion entry.
- Added `LocalizedChoice<T>` and localized choice collections for duplicate, proxy, aria2, and update-channel settings.
- Bound Settings and Add Download duplicate behavior controls to localized choice view-model objects instead of raw enum names.
- Made MiniWindow title, labels, help text, list metadata, and transfer controls localizable and accessible.
- Bound MiniWindow pause/resume/cancel enablement to the same selected-transfer state used by the main window.
- Added nine-section keyboard navigation coverage including Ctrl+9 and numpad 9.
- Localized safe-mode diagnostic/status copy and aligned the wording with browser, scheduler, and aria2 startup skipping.
- Added modern-resource catalog loading and exact/parent-culture fallback before the legacy translation bridge.
- Added script/region-aware legacy fallback for Chinese and Serbian variants.
- Added Hindi and Malagasy to `Lang/index.txt` so shipped packs are exposed by the catalog.
- Expanded accessibility/localization contract tests to include MiniWindow and all page fixtures.

## Validation performed in this packaging environment

- `strings.en.json` parses successfully and contains 609 keys.
- All shell/page/MiniWindow XAML fixtures parse as XML.
- All `Localization[...]` XAML bindings resolve to English catalog keys.
- No hard-coded visible XAML text candidates remain in the checked shell/page fixtures.
- Automation IDs are unique across shell/page fixtures.
- REM14 semantic sentinels passed for Ctrl+9, numpad 9, MiniWindow action bindings, safe-mode localization, modern catalog fallback, script/region fallback, localized Settings choices, and Recovery fixture coverage.
- C# string literal scan found no newline-in-normal-string syntax hazards in changed C# files.
- Overlay manifest JSON parses successfully.
- Tar extraction check confirms the manifest and changed files are present.

## Validation not performed here

The packaging container does not have the .NET SDK installed, so `dotnet test` could not be executed here. Run the explicit Devtool/dotnet validation gate later in the roadmap or at REM18 final seal.

## Changed files

- `app/XDM/Lang/index.txt`
- `app/XDM/src/XDM.App/App.axaml.cs`
- `app/XDM/src/XDM.App/Localization/strings.en.json`
- `app/XDM/src/XDM.App/MainWindow.axaml`
- `app/XDM/src/XDM.App/MainWindow.axaml.cs`
- `app/XDM/src/XDM.App/MiniWindow.axaml`
- `app/XDM/src/XDM.App/Services/LocalizationService.cs`
- `app/XDM/src/XDM.App/ViewModels/LocalizedChoice.cs`
- `app/XDM/src/XDM.App/ViewModels/MainWindowViewModel.Aria2.cs`
- `app/XDM/src/XDM.App/ViewModels/MainWindowViewModel.LocalizationParity.cs`
- `app/XDM/src/XDM.App/ViewModels/MainWindowViewModel.Organization.cs`
- `app/XDM/src/XDM.App/ViewModels/MainWindowViewModel.cs`
- `app/XDM/src/XDM.App/Views/BrowserIntegrationView.axaml`
- `app/XDM/src/XDM.App/Views/ConversionView.axaml`
- `app/XDM/src/XDM.App/Views/DiagnosticsView.axaml`
- `app/XDM/src/XDM.App/Views/DownloadsView.axaml`
- `app/XDM/src/XDM.App/Views/QueuesView.axaml`
- `app/XDM/src/XDM.App/Views/RecoveryView.axaml`
- `app/XDM/src/XDM.App/Views/SchedulerView.axaml`
- `app/XDM/src/XDM.App/Views/SettingsView.axaml`
- `app/XDM/src/XDM.Core/Localization/LegacyTranslationCatalog.cs`
- `app/XDM/src/XDM.Core.Tests/AccessibilitySurfaceTests.cs`
- `app/XDM/src/XDM.Core.Tests/AccessibilityUiArchitectureTests.cs`
- `app/XDM/src/XDM.Core.Tests/LegacyTranslationCatalogTests.cs`
- `app/XDM/src/XDM.Core.Tests/XDM.Core.Tests.csproj`


## Redo artifact

This v2 package reissues REM14 as a fresh commit-capable Devtool overlay after the user requested a redo. The implementation scope remains REM14 / overlay 14 of 18 and preserves the repository-relative tar layout, clean-worktree requirement, and single-commit artifact manifest.
