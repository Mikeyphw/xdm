# XAR15 — Privacy, security, performance, and scoped-storage lane

Merged roadmap overlay 6 of 8 / Android XAR overlay 15 of 17.

XAR15 closes the S15 cross-cutting cluster without changing the production Android Firefox extension.
The Firefox extension remains the canonical implementation from XFE01; this overlay only hardens app-side intake, runtime, UI projection, artwork loading, settings copy, registry retention, and release storage policy.

## Closed roots

- S15-01: artwork remote loading now passes through the existing external URL/network-target policy and refuses non-public targets.
- S15-02: the exported launcher MainActivity no longer trusts forgeable internal direct-capture extras; a one-use in-process approval token must be minted by the exported review boundary.
- S15-03: expired encrypted request-envelope cleanup moved off the Application.onCreate critical path.
- S15-04: browser capture session registry now prunes durable session index files to a bounded retention set.
- S15-05: aggregate artwork network and decode work is capped by semaphores.
- S15-06: external share review reads only bounded literal ClipData URI/text/html fields and does not call coerceToText on attacker-controlled items.
- S15-07: transfer runtime rethrows coroutine cancellation instead of recording it as a backend failure.
- S15-08: live progress summary projection is throttled instead of recomputing the O(n) active-transfer projection on every backend tick.
- S15-09: portable settings snapshot copy uses sensitive clipboard metadata and delayed clearing.
- S15-10: artwork cache publication uses unique temporary files, fsync, stale-temp cleanup, and decode-after-publish verification.
- S15-11: the S15 validator and contract test exercise the risky paths directly.
- DS8-S15-04: release manifest overlay removes all-files access for publishable release variants, creating a scoped-storage release lane.
- DS8-S15-05: legacy external-storage runtime permission prompting is no longer automatic on app launch.

## Validation

- `tools/validate-xar15-privacy-security-performance.py`
- `Xar15PrivacySecurityPerformanceContractTest`

Full Android build/device validation remains reserved for XAR16/XAR17.
