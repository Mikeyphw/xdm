#!/usr/bin/env python3
"""RM04 static seal: diagnostics truth, support attestation separation, and same-run release evidence."""
from pathlib import Path
import json

ROOT = Path(__file__).resolve().parents[1]

def text(rel: str) -> str:
    path = ROOT / rel
    if not path.is_file():
        raise AssertionError(f'missing RM04 source: {rel}')
    return path.read_text(encoding='utf-8')

def need(haystack: str, needle: str, label: str) -> None:
    if needle not in haystack:
        raise AssertionError(f'RM04 missing {label}: {needle!r}')

def reject(haystack: str, needle: str, label: str) -> None:
    if needle in haystack:
        raise AssertionError(f'RM04 retains misleading {label}: {needle!r}')

def main() -> int:
    security = text('core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/ReleaseSecurityModels.kt')
    need(security, 'Runtime diagnostics checks are clean', 'runtime diagnostics summary')
    need(security, 'Runtime diagnostics gate: ${report.summary}', 'runtime diagnostics gate label')
    reject(security, 'Release gate: ${report.summary}', 'generic diagnostics gate label')

    readiness = text('core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/ReleaseReadinessModels.kt')
    need(readiness, 'diagnosticsRuntimePrivacyReady: Boolean', 'install/update runtime privacy input')
    need(readiness, 'Runtime diagnostic redaction is not ready', 'runtime privacy failure wording')
    reject(readiness, 'Diagnostic bundle is not redacted', 'unattested-as-leak wording')

    final = text('core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/FinalReleaseGateModels.kt')
    need(final, 'diagnosticsExportAttested: Boolean', 'final export attestation input')
    need(final, 'id = "diagnostics.export-attestation"', 'final export attestation check id')
    need(final, 'Final diagnostics export redaction is not attested', 'final export attestation wording')
    need(final, 'Runtime redaction is checked separately', 'runtime/export separation')
    reject(final, 'title = "Diagnostics are not redacted"', 'unattested-as-leak final gate wording')

    support = text('core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/SupportBundleReleaseReadinessSeal.kt')
    need(support, 'diagnosticsExportAttested: Boolean', 'support attestation input')
    need(support, 'title = "Final diagnostics export attestation"', 'support attestation check')
    need(support, 'title = "Privacy redaction boundary"', 'runtime privacy check retained')

    vm = text('app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt')
    need(vm, 'diagnosticsRuntimePrivacyReady = diagnosticsRuntimePrivacyReady', 'install/update runtime privacy projection')
    need(vm, 'diagnosticsExportAttested = diagnosticsExportValidated', 'final release attestation projection')
    need(vm, 'redactedReportsOnly = diagnosticsRuntimePrivacyReady', 'support privacy independent of attestation')
    need(vm, 'Final diagnostics export privacy/integrity attestation', 'support evidence wording')
    reject(vm, 'diagnosticsRedacted = diagnosticsRuntimePrivacyReady && diagnosticsExportValidated', 'conflated final gate projection')

    workspace = text('app/src/main/kotlin/com/mikeyphw/xdm/android/ui/developer/DeveloperToolsWorkspace.kt')
    need(workspace, 'Final diagnostics ZIP attestation:', 'developer validation truth wording')

    for rel, marker in [
        ('core-model/src/test/kotlin/com/mikeyphw/xdm/android/model/FinalReleaseGateModelsTest.kt', 'missingFinalDiagnosticsAttestationBlocksReleaseWithoutClaimingRuntimeLeak'),
        ('core-model/src/test/kotlin/com/mikeyphw/xdm/android/model/SupportBundleReleaseReadinessPlannerTest.kt', 'supportBundleSealSeparatesMissingAttestationFromRuntimePrivacyFailure'),
        ('app/src/test/kotlin/com/mikeyphw/xdm/android/Rm04DiagnosticsReleaseFinalSealContractTest.kt', 'sameRunRm04EvidenceIsRequiredByXar16Publication'),
    ]:
        need(text(rel), marker, f'regression anchor {marker}')

    builder = text('tools/build-xar16-release-artifacts.sh')
    need(builder, 'write-rm04-validation-evidence.py', 'same-run RM04 evidence writer')
    need(builder, 'rm04-validation-seal.json', 'same-run RM04 evidence filename')
    verifier = text('tools/verify-xar16-release-evidence.py')
    need(verifier, "'rm04-validation-seal.json'", 'XAR16 required RM04 evidence')
    need(verifier, "rm04.get('canonicalNonDeviceValidationPassed')", 'RM04 evidence semantic verification')
    publication = text('tools/generate-xar16-publication-bundle.sh')
    need(publication, 'rm04-validation-seal.json', 'publication bundle RM04 evidence copy')

    final_gate = text('tools/run-final-release-gate.sh')
    need(final_gate, 'tools/validate-rm04-diagnostics-release-final-seal.py', 'canonical final gate RM04 validator')
    gradle = text('app/build.gradle.kts')
    need(gradle, 'verifyRm04DiagnosticsReleaseFinalSeal', 'RM04 Gradle task')
    need(gradle, 'validate-rm04-diagnostics-release-final-seal.py', 'RM04 Gradle validator wiring')

    manifest = json.loads(text('PROJECT_MANIFEST.json'))
    rm04 = manifest.get('rm04_diagnostics_release_final_seal')
    if not isinstance(rm04, dict) or rm04.get('status') != 'implemented':
        raise AssertionError('PROJECT_MANIFEST must mark RM04 implemented')
    for key in (
        'runtime_and_final_gate_terminology_separated',
        'redaction_and_attestation_semantics_separated',
        'support_bundle_attestation_explicit',
        'same_run_rm04_evidence_hash_bound',
        'xar16_publication_requires_rm04_evidence',
        'roadmap_complete',
    ):
        if rm04.get(key) is not True:
            raise AssertionError(f'RM04 manifest invariant is not sealed: {key}')
    if rm04.get('roadmap_position') != 'overlay 4 of 4':
        raise AssertionError('RM04 roadmap position mismatch')
    if rm04.get('next_overlay') != 'complete':
        raise AssertionError('RM04 must close the 4-overlay roadmap')

    print('RM04 diagnostics/release final seal contract passed: runtime privacy, final-ZIP attestation, support truth, and same-run publication evidence are separated and sealed')
    return 0

if __name__ == '__main__':
    raise SystemExit(main())
