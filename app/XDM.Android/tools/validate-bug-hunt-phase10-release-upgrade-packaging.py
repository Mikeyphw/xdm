#!/usr/bin/env python3
from __future__ import annotations
import json, re, sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
REPO=ROOT.parent.parent
errors=[]
def text(rel):
    p=ROOT/rel
    if not p.is_file():
        errors.append(f'missing {rel}')
        return ''
    return p.read_text(encoding='utf-8')
def repo_text(rel):
    p=REPO/rel
    if not p.is_file():
        errors.append(f'missing {rel}')
        return ''
    return p.read_text(encoding='utf-8')
def require(cond,msg):
    if not cond: errors.append(msg)

build=text('app/build.gradle.kts')
manifest=text('app/src/main/AndroidManifest.xml')
aria_build=text('transfer-aria2/build.gradle.kts')
data_rules=text('app/src/main/res/xml/data_extraction_rules.xml')
backup_rules=text('app/src/main/res/xml/backup_rules.xml')
workflow=repo_text('.github/workflows/android.yml')
gate=text('tools/run-bug-hunt-phase10-release-gate.sh')
release_verifier=text('tools/verify-phase10-release-artifacts.py')
backup_verifier=text('tools/verify-phase10-backup-policy.py')
upgrade=text('tools/run-phase10-install-upgrade-matrix.sh')
pub=text('tools/generate-phase10-publication-bundle.sh')
doc=text('docs/release/BUG-HUNT-PHASE-10-RELEASE-UPGRADE-PACKAGING.md')
contract=text('app/src/test/kotlin/com/mikeyphw/xdm/android/Phase10ReleaseUpgradePackagingContractTest.kt')
release_inventory=text('tools/phase10-release-inventory.json')
project=json.loads(text('PROJECT_MANIFEST.json') or '{}')
phase=project.get('bug_hunt_phase_10_release_upgrade_packaging', {})

for needle in ['versionCode = 22','versionName = "0.21.0"','XDM_RELEASE_BUILD_ID','XDM_RELEASE_SIGNER_SHA256','XDM_RELEASE_CERTIFICATE_NOT_AFTER','XDM_RELEASE_SIGNING_CONFIGURED','xdmAssertReleaseSigningInputs','create("developmentUnsigned")','abiFilters += setOf("arm64-v8a")']:
    require(needle in build, f'app build missing {needle}')
require('jniLibs.useLegacyPackaging = true' in build, 'app release packaging must extract JNI libraries')
require('jniLibs.keepDebugSymbols += "**/libaria2c.so"' in build, 'debug symbols must be scoped to aria2 runtime only')
require('android:extractNativeLibs' not in manifest, 'manifest must delegate native extraction policy to AGP jniLibs.useLegacyPackaging')
require('android:allowBackup="false"' in manifest, 'allowBackup must remain false')
require('android:dataExtractionRules="@xml/data_extraction_rules"' in manifest, 'manifest must reference data extraction rules')
require('jniLibs.useLegacyPackaging = true' in aria_build, 'aria2 module must extract JNI libraries')
require('--require-16kb-alignment' in text('tools/verify-aria2-runtime.py'), 'aria2 verifier must support 16 KB runtime gate')

for rules_name,rules in [('backup_rules', backup_rules),('data_extraction_rules', data_rules)]:
    for domain in ['root','file','database','sharedpref','external']:
        require(f'domain="{domain}" path="."' in rules, f'{rules_name} must exclude all {domain}')
    for marker in ['xdm.db','checkpoints/','ownership/','journals/','recovery/','termux/','diagnostics/','support/']:
        require(marker in rules, f'{rules_name} missing explicit {marker}')
require('<cloud-backup' in data_rules and '<device-transfer>' in data_rules, 'data extraction rules must split cloud backup and device transfer')

for needle in ['lintRelease','testReleaseUnitTest',':app:assembleRelease',':app:bundleRelease','bundletool build-apks','BUNDLETOOL_JAR','--ks-key-alias','debug-key fallback is forbidden','XDM_ARIA2_ARCHIVE_SHA256','--expected-archive-sha256','--require-trusted-digest','verify-aria2-runtime.py --require-payload --require-16kb-alignment','--require-trusted-archive-digest','verify-phase10-release-artifacts.py --require-16kb','--inventory tools/phase10-release-inventory.json','--apks','generate-phase10-publication-bundle.sh']:
    require(needle in gate, f'Phase10 release gate missing {needle}')
for needle in ['apksigner','jarsigner','aapt2 or aapt','splitApksVerified','signer SHA-256','forbidden release APK payload','required inventory entry','unsupported native ABI','AAB missing BundleConfig.pb','PAGE_ALIGNMENT_16K','16 KB zip-aligned','debug.keystore','phase10-release-attestation.json']:
    require(needle in release_verifier, f'release verifier missing {needle}')
for needle in ['allowBackup=false','data_extraction_rules','backup_rules','device-transfer','cloud-backup']:
    require(needle in backup_verifier, f'backup verifier missing {needle}')
for needle in ['adb install -r "$PREVIOUS_APK"','adb install -r "$CANDIDATE_APK"','adb reboot','install -d','downgrade unexpectedly succeeded']:
    require(needle in upgrade, f'upgrade matrix missing {needle}')
for needle in ['SHA256SUMS','SHA512SUMS','release-metadata.json','publicationRequires','phase10-sbom.json','phase10-provenance.json','CHECKSUM_ATTESTATION_REQUIRED']:
    require(needle in pub, f'publication bundle missing {needle}')
for needle in ['signed-release:', 'XDM_RELEASE_KEYSTORE_BASE64', 'XDM_RELEASE_SIGNER_SHA256', 'BUNDLETOOL_JAR', 'bash tools/run-bug-hunt-phase10-release-gate.sh', 'actions/attest', 'xdm-android-signed-release']:
    require(needle in workflow, f'Android workflow missing {needle}')
require('app/build/outputs/apk/release/*.apk' not in workflow.split('xdm-android-debug-artifacts',1)[0], 'debug validation job must not search release APK output')
for needle in ['versionCode 22','versionName 0.21.0','APK-set','16 KB native alignment','device-to-device transfer','SBOM','provenance','checksum attestation','Phase 10 r2 gap closure','Room schema 17','XDM_ARIA2_ARCHIVE_SHA256']:
    require(needle in doc, f'Phase10 doc missing {needle}')
for needle in ['Phase10ReleaseUpgradePackagingContractTest','releaseBuildRequiresSigningAndNamedUnsignedVariant','releaseGateBuildsSignedApkAndBundleBeforePublication','runtimeReleaseReadinessUsesCurrentSchemaAndBuildAttestation','cloudBackupAndDeviceTransferExcludeSensitiveState']:
    require(needle in contract, f'Phase10 contract test missing {needle}')

require(10 in project.get('project',{}).get('implemented_phases',[]), 'PROJECT_MANIFEST must include bug-hunt Phase 10')
require(phase.get('status') == 'implemented', 'PROJECT_MANIFEST Phase10 status missing')
require(phase.get('release_signing_required') is True, 'manifest must require release signing')
require(phase.get('version_code') == 22 and phase.get('version_name') == '0.21.0', 'manifest must record Phase10 version bump')
require(phase.get('room_schema_current', 0) >= 17 and project.get('database',{}).get('version', 0) >= 18, 'manifest must report current Room schema 20 or newer while retaining Phase 10 schema provenance')
require(phase.get('supported_abis') == ['arm64-v8a'], 'manifest must truthfully scope supported ABIs to attested arm64-v8a')
require(phase.get('aria2_archive_digest_required') is True, 'manifest must require pinned aria2 archive digest')
require('requiredApkEntries' in release_inventory and 'forbiddenNameFragments' in release_inventory, 'release inventory must define allow/deny entries')
require(phase.get('backup_and_device_transfer_excluded') is True, 'manifest must record backup/D2D exclusions')
require(phase.get('signed_checksums_required') is True, 'manifest must require signed/attested checksums')


main_vm=text('app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt')
for needle in ['CurrentRoomSchemaVersion = 20','BuildConfig.XDM_RELEASE_SIGNING_CONFIGURED','BuildConfig.XDM_PINNED_RELEASE_SIGNER_SHA256','releaseSigningAttestationConfigured()']:
    require(needle in main_vm, f'MainViewModel release readiness missing {needle}')
require('releaseSigningConfigured = !BuildConfig.DEBUG' not in main_vm, 'MainViewModel must not derive release signing from !BuildConfig.DEBUG')
require('schemaVersion = 14' not in main_vm, 'MainViewModel must not hardcode old schema 14')

# Stale validator harmony: old validators must tolerate new version or phase10 overlay.
for rel in ['tools/validate-downloader-experience-phase-8c.py','tools/validate-downloader-experience-phase-8d.py','tools/validate-downloader-experience-phase-8e.py','tools/validate-phase-36-external-download-handoff.py','tools/validate-uix-r6-accessibility-performance-release-seal.py','tools/validate-phase65-diagnostic-export-download-action-fix.py']:
    src=text(rel)
    require('0.21.0' in src or 'versionCode = 22' in src or 'current_version_name' in src, f'{rel} not harmonized for Phase10 version')

if errors:
    print('Bug-hunt Phase 10 release/upgrade/packaging validation failed:')
    for e in errors: print('- '+e)
    sys.exit(1)
print('Bug-hunt Phase 10 release, upgrade, packaging, and publication validator passed')
