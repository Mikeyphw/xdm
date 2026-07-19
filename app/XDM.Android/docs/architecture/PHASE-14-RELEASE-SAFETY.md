# Phase 14: Release Safety and Privacy Hardening

Phase 14 prepares XDM Android for beta-style validation without changing the Room schema or adding a new top-level route.

## Goals

- Keep all release-safety controls inside Diagnostics and existing validation tooling.
- Produce a privacy-safe diagnostic summary that can be copied without raw URLs, cookies, bearer tokens, API keys, sessions, or signatures.
- Add a deterministic release gate script that checks project metadata, build configuration, schema stability, validators, and source-level privacy contracts.
- Keep Room at schema v13. Phase 14 is a hardening slice, not a persistence migration.

## UI contract

Diagnostics owns the release-safety surface. It shows the current build profile, release gate summary, and a copy action for a redacted health summary. No product UI mentions phases, milestones, or placeholders.

## Privacy contract

Diagnostic output must be safe to paste into bug reports. The redactor must:

- replace sensitive request headers with `<redacted>`;
- redact bearer/basic credentials;
- redact query values for token, key, signature, password, session, cookie, and auth fields;
- preserve host/status/source context so the report remains useful;
- avoid raw header or raw URL leakage from browser/share/Tasker handoffs.

## Release gate contract

`tools/validate-phase-14.py` verifies:

- `PROJECT_MANIFEST.json` reports version `0.14.0-alpha01` and implemented phase 14;
- database version remains 13;
- release hardening is documented in the manifest;
- app build metadata matches version code 15 and version name `0.14.0-alpha01`;
- beta and release signing configuration are still present;
- release safety model/tests exist;
- Diagnostics exposes a real redacted summary copy action;
- no unsupported Room schema bump was introduced.

## Validation

Recommended medium gate:

```bash
./gradlew :core-model:test :app:compileDebugKotlin :app:testDebugUnitTest --no-daemon --build-cache
python3 tools/validate-phase-14.py
```

Full validation remains reserved for the final release gate.
