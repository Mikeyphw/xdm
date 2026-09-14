# XDM Android XAR14 settings/diagnostics truth report

Overlay: `xdm_android_xar14_settings_diagnostics_truth_v1.tar.gz`
Roadmap position: Android XAR14 / 14 of 17, merged overlay 5 of 8.

This overlay closes the 21-root S14 cluster: S14-01, S14-02, S14-03, S14-04, S14-05, S14-06, S14-07, S14-08, S14-09, S14-10, S14-11, S14-12, S14-13, S14-14, DS7-S14-01, DS7-S14-02, DS7-S14-03, DS7-S14-04, DS7-S14-05, RERUN67-S14-01, and RERUN7R-S14-01.

## Implementation summary

- Settings export now creates a portable copy that strips device-bound destination grants and proxy credential aliases.
- Settings import uses `SettingsExchangeImportResult`, reports rejected/accepted state, does not erase pasted text on failure, ignores external IDs, and saves organization data through a single repository transaction.
- Destination host matching now distinguishes exact hosts from wildcard subdomains.
- Debug Center run storage writes atomically and quarantines corrupt run files.
- Debug test execution has a per-test timeout and safe checks no longer create schedulable queued downloads.
- Diagnostic ZIP exports are bounded, manifest-bound, final-scanned and share-gated.
- Redaction covers query credentials, structured credentials, authorization headers, path-embedded credential segments and long token-like path segments.
- Room schema reporting is aligned to 25 across diagnostics surfaces.

## Firefox extension invariant

The production Android Firefox extension remains the canonical working implementation from XFE01. XAR14 does not change the extension capture/media logic.
