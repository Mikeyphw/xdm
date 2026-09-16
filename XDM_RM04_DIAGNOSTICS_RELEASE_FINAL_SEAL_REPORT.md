# XDM Android RM04 — Diagnostics / Release Final Seal Report

**Roadmap position:** Overlay 4 of 4  
**Status:** Implemented  
**Room schema:** unchanged at 25

## Implemented

- Split runtime diagnostic privacy from final diagnostics ZIP release attestation.
- Renamed the diagnostic security summary to `Runtime diagnostics gate`, eliminating the misleading second generic release-gate label.
- Replaced `Diagnostics are not redacted` for missing release evidence with `Final diagnostics export redaction is not attested`.
- Install/update readiness now depends on the runtime privacy contract instead of release-only final-ZIP attestation.
- Support bundle reports `Privacy redaction boundary` and `Final diagnostics export attestation` as independent checks.
- Developer validation truth uses explicit final-ZIP attestation terminology.
- Added same-run `rm04-validation-seal.json` generation after canonical non-device validation succeeds.
- XAR16 verification now requires the RM04 seal and publication bundles copy it alongside device/journey/artifact evidence.
- Added RM04 static validator, Gradle verification task, contract tests, documentation and canonical final-gate wiring.

## Validation design

RM04 static validation proves the terminology/evidence wiring and rejects the old conflated wording. Core-model/app tests cover the release/support semantics. The final target-environment seal remains XAR16's signed release flow because only that environment can supply real signing, payload, APK-set and device evidence.

## Roadmap closure

RM01 Transfer Core / Recovery Integrity — implemented  
RM02 Persistence + Downloads UX — implemented  
RM03 Embedded Runtimes + HLS Networking — implemented  
RM04 Diagnostics / Release Final Seal — implemented

The four-overlay remediation roadmap is complete after RM04 is applied and final validation passes.

## Validation completed during implementation

- RM04, RM03, RM02 and RM01 remediation static seals pass.
- XAR16 release-evidence seal, Phase61 validator harmony, Phase63 support-bundle seal, Media Parity01 runtime truth, Phase7 post-processing/Termux, UIX R6, Phase13, and Add/Media UX retained validators pass after compatibility updates for the superseding contracts.
- `tools/run-bug-hunt-phase11-validation-matrix.sh --static-only --ci` passes the complete static bug-hunt matrix.
- The canonical `tools/run-final-release-gate.sh --ci` progressed through RM01-RM04, XAR01-XAR16, UIX/UX, runtime-foundation, media, remediation and retained static gates and entered the executable test section without a reported contract failure before the local execution window expired.
- A direct Gradle test attempt could not start because Gradle 9.7.1 is not cached in this isolated build environment and `services.gradle.org` is unreachable. The final Devtool apply must therefore run the configured Gradle validation in the target Termux environment; this report does not claim those Gradle/device/signing checks passed here.

## Final validation contract

RM04 intentionally does not fabricate release evidence. The same-run RM04 validation seal is generated only after canonical non-device validation succeeds, while signed APK/APK-set, native payload and real-device evidence remain owned by XAR16. A publishable release is complete only when those target-environment checks pass and the resulting evidence is hash-bound into the publication bundle.
