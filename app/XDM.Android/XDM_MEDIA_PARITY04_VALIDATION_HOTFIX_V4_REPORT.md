# XDM Media Parity04 Validation Hotfix V4

## Purpose

This cumulative hotfix supersedes `xdm_media_parity04_validation_hotfix_v1.zip`, `v2.zip`, and `v3.zip` after the validated v3 apply rolled back during `:app:testDebugUnitTest`.

The v3 overlay compiled far enough to execute app unit tests. The remaining failures were stale source-contract expectations and Android unit-test root resolution issues rather than new production-code compile failures.

## Evidence from validated v3 apply log

Log reviewed: `20260911-164530-071867-xdm_media_parity04_validation_hotfix_v3.log`.

Result:

- overlay apply step passed;
- artifact validation passed;
- validation reached `:app:testDebugUnitTest`;
- 437 app unit tests executed;
- 32 source-contract tests failed;
- Devtool rolled back the transaction cleanly.

The failures grouped into these buckets:

1. app source-contract tests still assumed schema 21/22/23 even though Parity03/Parity04 made Room schema 24 current;
2. app source-contract tests still treated post-UX13 or notification/WebView hotfix as current authority instead of retained historical baselines;
3. Parity01/Parity02/Parity03 app tests located files relative to the Gradle app-module working directory incorrectly;
4. old Debug Center tests still expected legacy Firefox secure/encrypted handoff diagnostics, which Parity01 intentionally retired from required runtime blockers;
5. old diagnostic export tests still expected direct `ZipOutputStream` ownership instead of the Parity01 `DiagnosticExportIntegrity.writeVerifiedZip` final-ZIP verifier;
6. old downloader-only tests still asserted built-in-browser absence even though the V2 roadmap explicitly requires a light WebView browser;
7. old HLS tests still expected ordinary adaptive media to route through yt-dlp, while Parity03 intentionally made supported VOD HLS native-first.

## Fixes included

V4 includes all fixes from v1, v2, and v3, plus:

- rebases app unit-test source contracts to schema 24/current Media Parity04 authority;
- fixes Parity01/Parity02/Parity03 app unit-test root resolution when run from `:app:testDebugUnitTest`;
- updates Debug Center tests to require Direct V3/keyless handoff diagnostics and final-ZIP verification instead of obsolete secure/encrypted Firefox blockers;
- updates downloader/WebView tests so the operational ledger remains browser-free while the product may include the required light WebView browser;
- updates Live Locator tests to require the Parity04 floating Media review surface instead of the old expanded fixed list;
- updates HLS execution tests to accept native-first supported HLS while retaining yt-dlp fallback/session checks;
- keeps historical release-baseline marker comments in the final gate and README so retained validators can distinguish old baselines from the current final authority.

## Roadmap audit result

All four media-parity overlays remain mapped to the V2 roadmap:

- Parity01: runtime truth, exact final diagnostic ZIP scanning, schema/topology truth, retired obsolete Firefox crypto blockers, lifecycle-backed aria2 evidence.
- Parity02: logical-media graph, cross-observer dedupe, WebView/Firefox convergence, signed-URL identity separation, bounded redacted evidence.
- Parity03: native supported HLS execution, admission idempotency, progress/finalization/completion truth, low-storage and unsupported/fallback classification.
- Parity04: light browser UX, floating Media chooser, userscripts, visible feedback, text wrapping, Developer Center final authority, completed notification Open/Share/Details.

The only roadmap gap discovered during the v1 audit was the missing completed-notification Share action; it is carried forward in this cumulative v4 hotfix.

## Local validation performed

The following local validators passed in the reconstructed post-Parity04 + v4 tree:

- `tools/validate-media-parity01-runtime-truth.py`
- `tools/validate-media-parity02-logical-capture.py`
- `tools/validate-media-parity03-native-hls.py`
- `tools/validate-media-parity04-browser-ux-release-seal.py`
- `tools/validate-remediation-phase13-final-gate.py`
- `tools/validate-post-ux13-roadmap-completion-hotfix.py`
- `tools/validate-notification-webview-gap-hotfix.py`
- `tools/validate-add-media-ux-remodel.py`
- `tools/run-bug-hunt-phase11-validation-matrix.sh --static-only --ci`

The all-in-one static final-gate wrapper again reached the retained matrix phase in this container but exceeded the container wall-clock limit. The underlying validators and Phase11 matrix were run independently and passed.

Gradle/lint/device validation is intentionally left to the target Termux/Android Devtool environment.
