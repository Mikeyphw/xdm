# XAR14 — Settings, destination rules, and diagnostics evidence truth

Roadmap position: Android overlay **14 of 17** / merged overlay **5 of 8**.

XAR14 closes the S14 diagnostics/settings cluster while preserving the XFE01 Firefox-extension invariant: the Android production Firefox extension remains the canonical implementation and this overlay does not change its capture logic.

## Canonical finding closure

- S14-01 — Proxy profile now stays as a redacted settings surface and is not presented as a verified production network route without a real consumer.
- S14-02 — Safe local checks use a cancelled non-schedulable repository probe rather than creating queued downloads.
- S14-03 — Validation-oriented actions remain local Diagnostics tests and do not create transfer execution commands.
- S14-04 — Diagnostic schema reports now agree on Room schema 25.
- S14-05 — Debug Workbench health uses runtime facts instead of hard-coded healthy booleans.
- S14-06 — Runtime self-tests now include concrete timeout and export scanner contracts rather than unconditional assertions.
- S14-07 — Portable settings snapshots omit device-bound SAF/content/file grants and proxy credential aliases.
- S14-08 — Invalid settings imports return a visible rejected result and the pasted text is not erased automatically.
- S14-09 — Diagnostic ZIP manifests bind the exact exported entry inventory with SHA-256 hashes and the final scanner version.
- S14-10 — Diagnostic export ZIPs are pruned to the retained run limit.
- S14-11 — Rolling debug recorder rotation checks failure and falls back to copy/truncate or quarantine.
- S14-12 — Every Debug Center test is bounded by a per-test execution deadline.
- S14-13 — The validator estate now includes XAR14 and is wired into Gradle and the final shell gate.
- S14-14 — Private-storage probe files are deleted in a finally block.
- DS7-S14-01 — Historical diagnostic exports no longer mix another run's live support report/incidents.
- DS7-S14-02 — Redaction/scanning now handles path-embedded credential segments and long token-like URL path segments.
- DS7-S14-03 — Debug ZIP sharing and support-report copy/share paths run a final redaction/scanner boundary.
- DS7-S14-04 — Debug run history writes atomically and corrupt run files are quarantined instead of silently dropped.
- DS7-S14-05 — Settings import validates/plans the snapshot first, ignores external IDs, and commits organization settings in one transaction.
- RERUN67-S14-01 — Exact host destination rules match only the exact host; wildcard rules require `*.`.
- RERUN7R-S14-01 — Settings export/import escapes the `|` list-field delimiter.

## Validation

Run `verifyXar14SettingsDiagnosticsTruth` or the final static gate. The overlay is still an intermediate Android overlay, so Devtool application should use `--no-validate`; the final Android gates remain XAR16 and XAR17.
