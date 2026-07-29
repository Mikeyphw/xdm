# Debug Workbench D6 Runtime Self-Test Suite

D6 adds a read-only runtime self-test suite to Settings > Debug Workbench. It gives support and developers a compact answer to: "Is the local diagnostic path healthy enough to reproduce and explain a problem?"

## Scope

The suite checks:

- manifest and external handoff route coverage, backed by existing contract tests;
- browser scheme readiness notes from the Browser Bridge debugger state;
- completed notification open-file path, including the non-exported trampoline and content URI grant contract;
- shared Phase 47 media sniffer smoke using static snippet input only;
- redaction smoke for URL query secrets, Authorization, Cookie, token, and signature-like values;
- notification intent diagnostics boundary;
- Debug Workbench recorder health;
- support report readiness;
- whether there is current transfer, draft, or media capture state to explain.

## Boundaries

The suite is deliberately read-only. It does not start downloads, launch viewers, inspect files, open browser schemes, run page probes, run network probes, mutate the database, or upload reports. The copy action exports a sanitized text report only.

## UX contract

The panel uses human labels and short fix hints. It does not render raw enum names, raw URLs, JSON payloads, headers, cookies, authorization values, or machine-only diagnostics in normal UI.

## Next

D7 seals the complete Debug Workbench roadmap with support-bundle privacy contracts, docs, and final release gate coverage.
