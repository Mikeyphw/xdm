# RM04 — Diagnostics / Release Final Seal

**Roadmap:** Overlay 4 of 4  
**Depends on:** RM01–RM03  
**Validation:** final campaign seal; no remaining remediation overlay follows RM04

## Objective

RM04 closes the audit mismatch between runtime diagnostic privacy, final exported-ZIP attestation, support-bundle wording, and the signed-release evidence chain.

The observed debug report could simultaneously say that release checks were clean, that diagnostics were not redacted, and that the runtime redaction contract passed while final export verification was merely not attested. Those are different facts and must not be collapsed into one status.

## Truth model

Three independent facts are now represented explicitly:

1. **Runtime diagnostics privacy contract** — in-app redaction/self-test status. A failure here means the runtime privacy boundary is actually unsafe.
2. **Final diagnostics ZIP privacy/integrity attestation** — evidence that the exact exported ZIP path, manifest, hashes, JSONL structure and secret scanner passed in the release validation run. Missing evidence means *not attested*, not *known to be unredacted*.
3. **Final release gate** — publication readiness after static/full validation, payload, signing, real-device, APK-set, journey and publication evidence.

Install/update readiness depends on the runtime privacy contract. Final public release additionally requires final-ZIP attestation.

## Support bundle semantics

The support seal now has separate rows for:

- `Privacy redaction boundary`; and
- `Final diagnostics export attestation`.

A debug build can therefore truthfully report that runtime values are redacted while the release-only final ZIP attestation is still pending.

## Same-run RM04 evidence

`tools/build-xar16-release-artifacts.sh` already runs the canonical non-device final validation before building release artifacts. After that command succeeds, RM04 writes `rm04-validation-seal.json` into the XAR16 evidence directory. The evidence records the run ID, hash of the canonical validation log, and hashes of the authoritative RM04 source/validator files.

`tools/verify-xar16-release-evidence.py` requires and validates that RM04 seal, and the publication generator copies it into the publication bundle. A release can therefore no longer claim publication readiness while silently omitting the RM04 diagnostics/static/full-validation attestation.

## Regression coverage

- runtime diagnostics summary no longer uses the generic `Release gate` label;
- install/update readiness consumes runtime privacy rather than release-only final-ZIP attestation;
- final release uses `diagnostics.export-attestation` with explicit *not attested* wording;
- support bundle independently reports runtime privacy and final export attestation;
- XAR16 same-run evidence requires `rm04-validation-seal.json`;
- publication bundle carries the RM04 seal;
- RM04 is wired into Gradle and the canonical final static gate.

## Release boundary

RM04 completes implementation of the four-overlay remediation roadmap. It does not fabricate device/signing evidence. The signed-release/device/APK-set/publication gate remains authoritative and must run in the real target environment with signing material, native runtime payloads, bundletool and a connected device.
