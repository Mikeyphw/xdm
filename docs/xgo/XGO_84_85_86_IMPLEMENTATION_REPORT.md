# XGO-84..86 Implementation Report

## Summary

This overlay extends the Wave-12 `xgo_seal` campaign after XGO-81/82/83 with three ordered jobs: full adversarial behavior qualification, parser fuzz hardening, and portable performance/resource budgets. It deliberately does not include XGO-87.

## XGO-84 — adversarial matrix

The seal reuses the executable subsystem auditors rather than duplicating their decision logic. Twenty-one explicit scenarios cover the roadmap network/storage/media matrix plus legacy-import fault behavior. Each scenario is assigned a typed expected outcome and an evidence source. Focused HTTP/checkpoint tests additionally cover malformed ranges, changed representation handling, truncated staging, preallocation failure, and short segment bodies.

The aggregate seal fails when a required sub-audit/check disappears, when publication ambiguity stops resolving to receipt inspection, when duplicate publication replies stop being idempotent, or when planted secret strings appear in collected evidence.

## XGO-85 — parser fuzz campaign

Ten bounded Go fuzz targets now cover all nine required parser categories. The committed policy requires at least one second per target. Harnesses truncate untrusted inputs to explicit maxima before parsing and verify only parser postconditions that are valid for successful parses.

During local qualification the campaign exposed one harness-only false positive for whitespace-only `Content-Disposition`; the parser correctly treats whitespace as an absent header. The harness was corrected and the generated corpus entry was removed because it represented neither a product crash/hang nor a behavioral regression. The complete campaign then passed.

## XGO-86 — performance/resource budget

`TestPerformanceScaleCampaign` measures eight deterministic scale workloads against committed portable ceilings. The local reconstruction passed all eight workloads. The performance seal also executes the existing Android JNI/scheduler/legacy-authority and Desktop engine-host/e2e/legacy-authority probes so host integration/resource contracts cannot silently regress while engine scale remains green.

The report explicitly records that true Android app idle CPU/wakeup/RSS and Desktop GUI-process startup/RSS are physical-host measurements and therefore must be requalified by GATE-12 on those real hosts. They are not invented from a Termux/shared process.

## Local qualification performed while authoring

- `python3 -m py_compile tools/xgo/audit_seal.py`
- TOML parse of `.devtool.toml`
- `audit_topology.py target-ready --target xgo_seal` → pass, seven validation nodes
- parser package test set → pass
- `TestPerformanceScaleCampaign` → pass
- `audit_seal.py adversarial` → pass, 21 scenarios
- `audit_seal.py fuzz-campaign --duration-seconds 1` → pass, 10 targets / 9 categories
- `audit_seal.py performance-budget` → pass, 8 workloads and 6 host-contract proxies

## Result

After application, `xgo_seal#validate` is the authoritative validation entry point. Devtool should execute the existing XGO-81/82/83 nodes first and then the new XGO-84/85/86 chain. Validation failure must pause the transaction; no task-exclusive validation plan is declared.
