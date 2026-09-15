#!/usr/bin/env python3
from __future__ import annotations
import json, re, sys, shutil
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
REPO = ROOT.parent.parent
ROOTS = [
    'S16-01','S16-02','S16-03','S16-04','S16-05','S16-06','S16-07','S16-08','S16-09','S16-10','S16-11',
    'DS8-S16-01','DS8-S16-02','DS8-S16-04','DS8-S16-05','DS8-S16-06','DS8-S16-07',
]

def read(rel: str) -> str:
    p = ROOT / rel
    if not p.is_file():
        raise SystemExit(f'missing {rel}')
    return p.read_text(encoding='utf-8')

def must(text: str, needle: str, label: str):
    if needle not in text:
        raise SystemExit(f'missing {label}: {needle}')


def purge_python_cache() -> None:
    for path in sorted(ROOT.rglob('*.pyc'), reverse=True):
        try:
            path.unlink()
        except FileNotFoundError:
            pass
    for path in sorted(ROOT.rglob('__pycache__'), key=lambda p: len(p.parts), reverse=True):
        if path.is_dir():
            shutil.rmtree(path, ignore_errors=True)

def main() -> None:
    purge_python_cache()
    manifest=json.loads(read('PROJECT_MANIFEST.json'))
    seal=manifest.get('xar16_signed_release_evidence_seal') or {}
    if manifest.get('current_overlay') != 'XAR16': raise SystemExit('PROJECT_MANIFEST current_overlay is not XAR16')
    if seal.get('canonical_findings_closed') != 17: raise SystemExit('XAR16 finding count is not 17')
    if seal.get('canonical_ids') != ROOTS: raise SystemExit('XAR16 canonical id list mismatch')
    if 'xdm_android_xar16_signed_release_evidence_seal_v13.tar.gz' not in manifest.get('xar_applied_overlay_archives', []): raise SystemExit('XAR16 archive missing from applied archive list')
    bg=read('app/build.gradle.kts')
    for n in ['verifyXar15PrivacySecurityPerformance','verifyXar16ReleaseEvidenceSeal','xdmSignedReleaseSeal','XDM_APK_SET_INSTALL_VERIFIED','XDM_PUBLICATION_EVIDENCE_VERIFIED','XDM_SIGNED_RELEASE_JOURNEYS_PASSED']:
        must(bg,n,'Gradle release evidence wiring')
    model=read('core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/FinalReleaseGateModels.kt')
    for n in ['ffmpegPayloadVerified: Boolean = true','lintValidationPassed: Boolean = true','nativeSymbolsValidated: Boolean = true','realDeviceSmokePassed: Boolean = true','apkSetInstalled: Boolean = true','publicationEvidenceVerified: Boolean = true','signedReleaseJourneysPassed: Boolean = true','id = "apkset.install"','id = "publication.evidence"','id = "release.journeys"']:
        must(model,n,'runtime final gate evidence')
    vm=read('app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt')
    for n in ['BuildConfig.XDM_FFMPEG_PAYLOAD_VERIFIED','BuildConfig.XDM_LINT_VALIDATION_PASSED','BuildConfig.XDM_NATIVE_SYMBOLS_VALIDATED','BuildConfig.XDM_APK_SET_INSTALL_VERIFIED','BuildConfig.XDM_PUBLICATION_EVIDENCE_VERIFIED','BuildConfig.XDM_SIGNED_RELEASE_JOURNEYS_PASSED','apkSetInstalled = apkSetInstallVerified']:
        must(vm,n,'runtime BuildConfig evidence projection')
    verifier=read('tools/verify-phase10-release-artifacts.py')
    for n in ['split_apk: bool = False','splitSemantics','set-level-required-inventory','requiredAabEntries','packageName']:
        must(verifier,n,'split APK/AAB verification semantics')
    inv=json.loads(read('tools/phase10-release-inventory.json'))
    for key in ['packageName','requiredAabEntries','sameRunEvidenceRequired','apkSetInstallRequired','signedReleaseJourneysRequired']:
        if key not in inv: raise SystemExit(f'inventory missing {key}')
    for rel in ['tools/xar16-release-matrix.json','tools/build-xar16-release-artifacts.sh','tools/run-xar16-install-upgrade-matrix.sh','tools/run-xar16-signed-release-journeys.sh','tools/generate-xar16-publication-bundle.sh','tools/verify-xar16-release-evidence.py','tools/run-xar16-signed-release-gate.sh']:
        if not (ROOT/rel).is_file(): raise SystemExit(f'missing XAR16 tool {rel}')
    final=read('tools/run-final-release-gate.sh')
    for n in ['validate-xar16-release-evidence-seal.py','XDM_XAR16_FULL_RELEASE_GATE','run-xar16-signed-release-gate.sh','XDM_XAR16_IN_PROGRESS']:
        must(final,n,'final gate XAR16 wiring')
    phase10=read('tools/run-bug-hunt-phase10-release-gate.sh')
    must(phase10,'XDM_XAR16_DISABLE_SUPERSEDE','phase10 gate supersede escape hatch')
    must(phase10,'run-xar16-signed-release-gate.sh','phase10 gate XAR16 delegation')
    matrix=read('tools/run-bug-hunt-phase11-validation-matrix.sh')
    must(matrix,'validate-xar16-release-evidence-seal.py','phase11 XAR16 static validator')
    must(matrix,'run-xar16-signed-release-gate.sh --ci','phase11 XAR16 release gate')
    root_workflow=(REPO/'.github/workflows/android.yml')
    if root_workflow.is_file():
        wf=root_workflow.read_text(encoding='utf-8')
        for n in ['validate-xar16-release-evidence-seal.py','run-xar16-signed-release-gate.sh --ci','XDM_RELEASE_CERTIFICATE_NOT_AFTER','XDM_XAR16_PREVIOUS_RELEASE_APK']:
            must(wf,n,'root workflow XAR16 release wiring')
    inner=(ROOT/'.github/workflows/android.yml')
    if inner.is_file():
        iw=inner.read_text(encoding='utf-8')
        must(iw,'validate-xar16-release-evidence-seal.py','inner workflow XAR16 validator')
    prod=list(ROOT.glob('browser-extension/src/main/extension/xdm-firefox/**/*'))
    leftovers=[p for p in ROOT.rglob('*') if '__pycache__' in str(p) or p.suffix=='.pyc']
    if leftovers:
        raise SystemExit('generated Python cache file present after cleanup: ' + ', '.join(str(p.relative_to(ROOT)) for p in leftovers[:5]))

    root_gradle=read('build.gradle.kts')
    must(root_gradle,'PYTHONDONTWRITEBYTECODE','root Gradle Exec bytecode-cache suppression')
    must(root_gradle,'tasks.withType<org.gradle.api.tasks.Exec>()','root Gradle Exec bytecode-cache suppression')
    app_gradle=read('app/build.gradle.kts')
    must(app_gradle,'environment("PYTHONDONTWRITEBYTECODE", "1")','app validator bytecode-cache suppression')
    must(read('tools/verify-ffmpeg-runtime.py'),'{"schemaVersion": 2}','FFmpeg runtime v2 schema verifier')
    must(read('tools/verify-ffmpeg-runtime.py'),'stale for source-only validation','FFmpeg source-only stale runtime tolerance')
    must(read('tools/verify-aria2-runtime.py'),'"schemaVersion": MANIFEST["schemaVersion"]','aria2 runtime schemaVersion verifier')
    must(read('tools/verify-aria2-runtime.py'),'stale for source-only validation','aria2 source-only stale runtime tolerance')
    planner=read('core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/EngineEscalationPlanner.kt')
    if planner.count('aria2Eligible = aria2Eligible') < 3:
        raise SystemExit('EngineEscalationPlanner missing aria2Eligible helper propagation')
    observer=read('browser-extension/src/main/extension/xdm-firefox/network-observer.js')
    must(observer,'function normalizedHeaderPayload','Firefox handoff normalization idempotence')
    must(observer,'alreadyNormalized','Firefox handoff normalization idempotence')
    aria_models=read('transfer-aria2/src/main/kotlin/com/mikeyphw/xdm/android/transfer/aria2/Aria2RuntimeModels.kt')
    must(aria_models,'suspend fun purgeSavedSession()','aria2 purgeSavedSession interface contract')
    native_backend=read('transfer-native/src/main/kotlin/com/mikeyphw/xdm/android/transfer/nativeengine/NativeHttpDownloadBackend.kt')
    must(native_backend,'checkpoint?.expectedLength','native finalization nullable checkpoint length handoff')
    selective=read('transfer-native/src/main/kotlin/com/mikeyphw/xdm/android/transfer/nativeengine/NativeSelectiveRepairService.kt')
    must(selective,'return RepairOutcome(','selective repair explicit success return')
    ux13=read('tools/validate-ux13-end-to-end-ui-ux-release-seal.py')
    must(ux13,'XAR_NEXT_PHASES','UX13 compact XAR successor harmony')
    must(ux13,'accepted Parity04/XAR successor','UX13 XAR next phase harmony')

    post_ux13=read('tools/validate-post-ux13-roadmap-completion-hotfix.py')
    must(post_ux13,'XAR_SUCCESSORS = {f"XAR{index:02d}"','post-UX13 compact XAR successor acceptance')
    must(post_ux13,'XAR17_313_root_closure_audit_final_gate','post-UX13 XAR17 next phase acceptance')
    external_security=read('app/src/main/kotlin/com/mikeyphw/xdm/android/ExternalAutomationSecurity.kt')
    must(external_security,'MAX_CLIP_ITEMS','bounded ClipData item count')
    must(external_security,'MAX_CLIP_TEXT_CHARS','bounded ClipData text extraction')
    shared_parser=read('browser-integration/src/main/kotlin/com/mikeyphw/xdm/android/browser/SharedLinkParser.kt')
    must(shared_parser,'ExtraRequestFingerprint','browser request fingerprint contract constant')
    locator=read('app/src/main/kotlin/com/mikeyphw/xdm/android/MediaLocatorActivity.kt')
    must(locator,'MAX_BRIDGE_JSON_BYTES','bounded WebView observation bridge payload')
    must(locator,'operationId = pageOperationId','bridge debug record must not pass nullable operation id as session id')
    vm_source=read('app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt')
    must(vm_source,'val privateScopes = emptySet<String>()','direct browser capture private scopes must be durable string authorities')
    app_source=read('app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApplication.kt')
    must(app_source,'val nativeHlsRecovery: Result<Int>','native HLS startup recovery Result type')
    termux=read('app/src/main/kotlin/com/mikeyphw/xdm/android/termux/TermuxMediaPipelineManager.kt')
    must(termux,'check(repository.saveMediaCaptureWithVariants(refreshedCapture, variants, resolvedAt))','Termux variant persistence CAS check retained')
    must(termux, '''            true
        } else {
            repository.saveMediaCapture(refreshedCapture)''', 'Termux metadata accepted flag remains Boolean')
    test15=read('app/src/test/kotlin/com/mikeyphw/xdm/android/Xar15PrivacySecurityPerformanceContractTest.kt')
    must(test15,'import org.junit.Test','XAR15 contract uses JUnit4 Test')
    if 'import kotlin.test' in test15:
        raise SystemExit('XAR15 contract test still imports kotlin.test')
    phase61=read('tools/validate-phase61-final-gate-validator-harmony.py')
    if 'XAR17_313_root_closure_audit_final_gate' not in phase61:
        raise SystemExit('Phase61 retained validator must accept XAR17 final closure next_phase')
    matrix_data=json.loads(read('tools/xar16-release-matrix.json'))
    if matrix_data.get('canonicalRoots') != ROOTS: raise SystemExit('XAR16 release matrix roots mismatch')
    print(f'XAR16 release evidence seal validator passed: {len(ROOTS)}/17 roots covered')
if __name__ == '__main__':
    main()

