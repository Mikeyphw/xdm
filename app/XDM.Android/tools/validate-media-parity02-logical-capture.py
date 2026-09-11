#!/usr/bin/env python3
from __future__ import annotations
import json, re, sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
ERRORS=[]
def read(rel):
 p=ROOT/rel
 if not p.is_file(): ERRORS.append(f"missing {rel}"); return ""
 return p.read_text(encoding='utf-8',errors='replace')
def req(c,m):
 if not c: ERRORS.append(m)

overlay='xdm_media_parity02_logical_media_capture_browser_convergence_v2.zip'
successor_overlay='xdm_media_parity03_native_hls_execution_admission_integrity_v2.zip'
parity04_overlay='xdm_media_parity04_browser_ux_userscripts_notifications_release_seal_v1.zip'
hotfix_overlay='xdm_media_parity04_validation_hotfix_v1.zip'
manifest=json.loads(read('PROJECT_MANIFEST.json') or '{}')
phase=manifest.get('media_parity02_logical_media_capture_browser_convergence',{})
req(manifest.get('current_overlay') in {overlay, successor_overlay, parity04_overlay, hotfix_overlay},'current overlay must be Media Parity02 or an accepted successor/hotfix')
req(manifest.get('database',{}).get('version') in {23,24},'authoritative Room schema must be 23 or accepted Parity03 successor schema 24')
req('22_to_23' in manifest.get('database',{}).get('migrations',[]),'manifest migration chain must include 22_to_23')
req(phase.get('status')=='implemented','Parity02 manifest status must be implemented')
req(phase.get('room_schema')==23 and phase.get('migration')=='22_to_23','Parity02 manifest schema/migration truth missing')
for k in ('bounded_graph','cross_observer_dedupe','late_hls_master_reparenting','unsupported_or_protected_capability_classification'):
 req(phase.get(k) is True,f'Parity02 manifest missing {k}')
req(phase.get('aes128_is_drm') is False,'AES-128 must not be classified as DRM')

graph=read('media/src/main/kotlin/com/mikeyphw/xdm/android/media/LogicalMediaGraph.kt')
for s in ('data class MediaObservation','data class LogicalMediaItem','class LogicalMediaGraphEngine','maxLogicalItems: Int = 96','maxEvidence: Int = 384','maxAliases: Int = 768','fun identityUrl(raw: String)','MediaProtectionKind.Aes128','MediaNativeCapability.FallbackRequired','MediaNativeCapability.ProtectedUnsupported','#EXT-X-PART','#EXT-X-PRELOAD-HINT'):
 req(s in graph,f'logical graph missing {s}')
req('x_(?:amz|goog)_(?:credential|security_token|signature|expires|date)' in graph,'logical identity must strip cloud signing credentials')

model=read('core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DownloadModels.kt')
for s in ('enum class MediaProtectionKind { None, Aes128, SampleAes, Drm, UnknownEncrypted }','enum class MediaNativeCapability { Unknown, NativeCandidate, FallbackRequired, ProtectedUnsupported }','val logicalMediaId: String?','val canonicalMediaUrl: String?','val observationCount: Int','val segmentCount: Int'):
 req(s in model,f'model missing {s}')

db=read('persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/AppDatabase.kt')
mig=read('persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/Migrations.kt')
ent=read('persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/Entities.kt')
dao=read('persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/MediaCaptureDao.kt')
repo=read('persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/DownloadRepository.kt')
req(re.search(r'version\s*=\s*(23|24)\b',db) is not None,'AppDatabase must be schema 23 or accepted successor schema 24')
for s in ('Migration22To23 = object : Migration(22, 23)','CREATE TABLE IF NOT EXISTS media_observations','index_media_captures_logicalMediaId','22_to_23'):
 req(s in (mig+json.dumps(manifest)),f'persistence migration missing {s}')
req('data class MediaObservationEntity' in ent,'media observation entity missing')
req('saveMediaObservationEvidence' in repo and 'limit: Int = 384' in repo,'bounded observation persistence missing')
req('DELETE FROM media_observations' in dao,'observation pruning DAO missing')

migration_test=read('persistence/src/androidTest/kotlin/com/mikeyphw/xdm/android/persistence/GenerationIntegrityMigrationTest.kt')
for s in ('runMigrationsAndValidate(name, 23, true, Migrations.Migration22To23)','media_observations','logicalMediaId','legacy:capture-23'):
 req(s in migration_test,f'22->23 migration regression missing {s}')

locator=read('app/src/main/kotlin/com/mikeyphw/xdm/android/MediaLocatorActivity.kt')
req('LogicalMediaGraphEngine' in locator,'Live Locator must use logical media graph')
req('saveMediaObservationEvidence' in locator,'Live Locator must persist bounded redacted evidence')
req('located[candidate.logicalMediaId]' in locator,'Live Locator UI must key rows by logical media identity')

developer=read('app/src/main/kotlin/com/mikeyphw/xdm/android/ui/developer/DeveloperToolsScreen.kt')
req('Raw media observations' in developer and 'state.mediaObservations.take(12)' in developer,'developer-only raw evidence surface missing')
req('currentRoomSchemaVersion = 23' in developer or 'currentRoomSchemaVersion = 24' in developer,'Developer Center schema truth must be current')

main=read('app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt')
envelope=read('app/src/main/kotlin/com/mikeyphw/xdm/android/BrowserCaptureEnvelopeManager.kt')
for s in ('candidate.logicalMediaId ?: candidate.stableMediaId','LogicalMediaGraphEngine.identityUrl(candidate.canonicalUrl ?: factualRawRecord.sourceUrl)','exactUrl = source.url','Received ${mediaItemCountLabel(distinctRecords.size)} from Firefox'):
 req(s in main,f'Android Firefox logical import missing {s}')
for s in ('val logicalMediaId: String? = null','val canonicalUrl: String? = null','val variantHints: List<VariantHint>','val trackHints: List<TrackHint>'):
 req(s in envelope,f'direct-v3 logical handoff model missing {s}')

ext=ROOT/'browser-extension/src/main/extension/xdm-firefox'
det=(ext/'detector-core.js').read_text()
store=(ext/'candidate-store.js').read_text()
popup=(ext/'popup.js').read_text()
handoff=(ext/'handoff.js').read_text()
for s in ('logicalMediaUrl','parseHlsRelationships','variantInfo','trackInfo','encryptedAes128','protectedMedia','lowLatency'):
 req(s in det,f'Firefox detector missing {s}')
req('logicalMediaId' in store and 'segmentCount' in store and 'observationCount' in store,'Firefox candidate store lacks logical grouping metadata')
req('logicalMediaCandidates' in popup and 'Send ${count} to XDM' in popup,'Firefox pre-handoff chooser missing')
for s in ('logicalMediaId','canonicalUrl','variantInfo','trackInfo','observationCount','segmentCount'):
 req(s in handoff,f'Firefox direct-v3 handoff missing {s}')

test=read('media/src/test/kotlin/com/mikeyphw/xdm/android/media/LogicalMediaGraphEngineTest.kt')
for s in ('1000','X-Amz-Credential','segment','Aes128','ProtectedUnsupported'):
 req(s in test,f'logical graph golden/stress tests missing {s}')
node_detector=read('browser-extension/tests/test_detector.js'); node_handoff=read('browser-extension/tests/test_handoff.js')
req('logicalSigned' in node_detector and 'variantInfo.length' in node_detector,'Firefox HLS/canonical regression test missing')
req('logical-master-12345678' in node_handoff and 'trackInfo[0].language' in node_handoff,'Firefox logical handoff metadata regression missing')
contract=read('app/src/test/kotlin/com/mikeyphw/xdm/android/MediaParity02LogicalMediaCaptureContractTest.kt')
req('webViewAndFirefoxConvergeOnLogicalMediaInsteadOfRawParts' in contract,'Parity02 app integration contract missing')

final_gate=read('tools/run-final-release-gate.sh')
req('tools/validate-media-parity02-logical-capture.py' in final_gate,'canonical final gate must carry Parity02 validator')

if ERRORS:
 print('Media Parity02 logical capture/browser convergence validation failed:')
 for e in ERRORS: print('-',e)
 sys.exit(1)
print('Media Parity02 logical capture/browser convergence validation passed')
