#!/usr/bin/env python3
from pathlib import Path
import json, sys
ROOT=Path(__file__).resolve().parents[1]
errors=[]
def text(rel):
    p=ROOT/rel
    if not p.is_file(): errors.append(f'missing {rel}'); return ''
    return p.read_text(errors='replace')
def need(src, token, label):
    if token not in src: errors.append(f'{label}: missing {token}')

notifications=text('scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferNotifications.kt')
worker=text('scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/QueueIntelligenceWorker.kt')
uidt=text('scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/UserInitiatedTransferJobService.kt')
receiver=text('scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferActionReceiver.kt')
recovery=text('scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/QueueSchedulingRecoveryCoordinator.kt')
permission=text('scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/NotificationPermissionStore.kt')
locator=text('app/src/main/kotlin/com/mikeyphw/xdm/android/MediaLocatorActivity.kt')
problem=text('app/src/main/kotlin/com/mikeyphw/xdm/android/AppProblemReporter.kt')
repo=text('persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/DownloadRepository.kt')
artwork=text('app/src/main/kotlin/com/mikeyphw/xdm/android/ui/media/XdmMediaArtwork.kt')
downloads=text('app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadsScreen.kt')
add_surface=text('app/src/main/kotlin/com/mikeyphw/xdm/android/ui/intake/AddDownloadSurface.kt')
preflight=text('app/src/main/kotlin/com/mikeyphw/xdm/android/ui/intake/DownloadPreflightProbe.kt')
terminal_policy=text('scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TerminalNotificationActionPolicy.kt')
fgs=text('scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferForegroundService.kt')
model=text('core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DownloadModels.kt')
entity=text('persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/Entities.kt')
migrations=text('persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/Migrations.kt')
database=text('persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/AppDatabase.kt')
ext_bg=text('browser-extension/src/main/extension/xdm-firefox/network-observer.js')
ext_handoff=text('browser-extension/src/main/extension/xdm-firefox/handoff.js')
main=text('app/src/main/kotlin/com/mikeyphw/xdm/android/MainActivity.kt')
phase4_contract=text('app/src/test/kotlin/com/mikeyphw/xdm/android/BugHuntPhase4QueueSchedulingStateMachinesContractTest.kt')
architecture_contract=text('app/src/test/kotlin/com/mikeyphw/xdm/android/ArchitectureContractTest.kt')
completed_notification_contract=text('app/src/test/kotlin/com/mikeyphw/xdm/android/CompletedNotificationPhase45ContractTest.kt')
live_locator_contract=text('app/src/test/kotlin/com/mikeyphw/xdm/android/LiveLocatorTitleNamingContractTest.kt')
post_ux13_contract=text('app/src/test/kotlin/com/mikeyphw/xdm/android/PostUx13RoadmapCompletionHotfixContractTest.kt')
remediation_0607_contract=text('app/src/test/kotlin/com/mikeyphw/xdm/android/RemediationPhase06_07ContractTest.kt')
remediation_13_contract=text('app/src/test/kotlin/com/mikeyphw/xdm/android/RemediationPhase13FinalGateContractTest.kt')
ux13_contract=text('app/src/test/kotlin/com/mikeyphw/xdm/android/Ux13EndToEndUiUxReleaseSealContractTest.kt')
ux01_contract=text('app/src/test/kotlin/com/mikeyphw/xdm/android/Ux01DestinationStorageTruthContractTest.kt')

strings=text('app/src/main/res/values/strings.xml')
xdm_app=text('app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt')
media_inbox=text('media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaInboxContract.kt')
gate=text('tools/run-final-release-gate.sh')
manifest=json.loads(text('PROJECT_MANIFEST.json') or '{}')

for src, token, label in [
    (uidt,'runtime.liveSummaryFor(downloadId','UIDT exact transfer summary'),
    (worker,'withLiveForeground','WorkManager live foreground'),
    (worker,'runtime.liveProgress.collectLatest','WorkManager progress stream'),
    (notifications,'NotificationUpdateThrottle','notification throttle contract carry-forward'),
]:
    # Throttle lives in owners, not TransferNotifications; allow any owner below.
    if label == 'notification throttle contract carry-forward':
        if 'NotificationUpdateThrottle' not in worker + uidt + text('scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferForegroundService.kt'):
            errors.append('notification throttle missing from execution owners')
    else: need(src,token,label)
need(notifications,'val isSingle = summary.activeCount == 1','single-vs-aggregate action truth')
need(notifications,'"Pause all"','aggregate pause action')
need(notifications,'"Resume all"','aggregate resume action')
need(notifications,'CHANNEL_ATTENTION','attention channel')
need(notifications,'CHANNEL_ROUTINE','routine channel')
need(notifications,'canPostChannel(channelFor(state))','channel-specific terminal delivery')
need(recovery,'pendingTerminalNotifications()','pending terminal reconciliation')
need(recovery,'if (existing != null) return@synchronized false','atomic file terminal reservation')
need(recovery,'recordNotificationControlCommand','durable notification controls')
for action in ['PauseOne','ResumeOne','CancelOne','RetryOne']:
    need(receiver, f'QueueControlCommand.{action}', f'durable {action}')
need(permission,'KEY_DENIED_ONCE','durable notification denial history')
need(main,'ACTION_APP_NOTIFICATION_SETTINGS','notification settings route')
need(main,'reconcilePendingTerminalNotifications()','reconcile after settings return')
need(problem,'EXTRA_PROBLEM_ID','problem deep link')
need(problem,'"Retry"','problem retry action')
need(problem,'areNotificationsEnabled()','problem app notification availability')
need(locator,'webView.saveState','WebView history state save')
need(locator,'webView.restoreState','WebView history state restore')
need(locator,'val restoredState = requireNotNull(savedInstanceState)','restored WebView state warning-free binding')
need(locator,'onReceivedIcon','favicon')
need(locator,'findAllAsync','find in page')
need(locator,'Intent.ACTION_SEND','page share')
need(locator,'Intent.ACTION_VIEW','external browser')
need(locator,'XdmArtworkLoader.load','captured candidate thumbnails')
need(locator,'R.string.media_locator_candidate_details','localized candidate detail row')
need(locator,'R.string.media_locator_type_inferred','localized inferred media type fallback')
need(strings,'name="media_locator_candidate_details"','candidate detail string resource')
need(strings,'name="media_locator_type_inferred"','inferred type string resource')
if 'item.mimeType ?: "type inferred"' in locator:
    errors.append('candidate details still contain hard-coded inferred type text')
need(locator,'thumbnailProvenance','WebView artwork provenance')
need(ext_bg,'thumbnailProvenance','extension privileged provenance merge')
need(ext_handoff,'thumbnailProvenance','extension handoff provenance')
need(entity,'val thumbnailProvenance: String','persisted provenance column')
need(migrations,'Migration21To22 = object : Migration(21, 22)','schema 21->22 migration')
need(database,'version = 24','Room schema 24')
need(repo,'uniqueMediaFileNames','persisted capture filename collision handling')
need(repo,'listAll()','existing capture filename comparison')
need(model,'object MediaArtworkMergePolicy','thumbnail provenance merge authority')
need(repo,'MediaArtworkMergePolicy.merge','persisted thumbnail quality merge')
need(artwork,'"artwork-policy-v2"','artwork cache policy version')
remote_pos=artwork.find('val remote = request.remoteUrl')
frame_pos=artwork.find('val generatedFrame = request.effectiveLocalUri')
if remote_pos < 0 or frame_pos < 0 or remote_pos >= frame_pos: errors.append('video artwork must prefer captured/remote thumbnail before generated frame')
need(downloads,'mediaOutputs: List<MediaOutputRecord>','all media generations available to Downloads')
need(downloads,'mediaOutputs.forEach','additional-generation thumbnail mapping')
need(add_surface,'effectiveFileName','effective server/link filename')
need(add_surface,'effectiveFileName,','effective filename submitted to download admission')
need(preflight,'DownloadFileNameSuggestionSource.ServerContentDisposition','Content-Disposition source classification')
need(uidt,'JOB_END_NOTIFICATION_POLICY_REMOVE','UIDT active notification removal policy')
need(uidt,'JOB_END_NOTIFICATION_POLICY_DETACH','UIDT terminal notification retention policy')
need(notifications,'.setOnlyAlertOnce(true)','idempotent terminal repost alert policy')
need(notifications,'.setGroup(GROUP_TERMINAL)','terminal group policy')
need(terminal_policy,'QueueControlCommand.ReviewRecovery','shared truthful terminal recovery action')
need(recovery,'TerminalNotificationActionPolicy.actionsFor','terminal actions rehydrated after restart')
need(fgs,'runCatching','foreground notification delivery isolation')
need(worker,'.onSuccess {','worker terminal dispatch mark only after notify succeeds')
need(gate,'validate-notification-webview-gap-hotfix.py','canonical gate wiring')

need(strings,'name="media_locator_no_external_browser"','external-browser fallback resource')
if 'import androidx.compose.foundation.layout.weight' in xdm_app: errors.append('XdmApp must use ColumnScope weight, not internal layout weight import')
need(permission,'androidx.core.content.edit','notification permission KTX edit import')
need(permission,'preferences.edit {','notification permission KTX edits')
if 'SDK_INT < Build.VERSION_CODES.O' in notifications: errors.append('scheduler minSdk 26 makes pre-O channel branch obsolete')
need(media_inbox,'titleIsPageDerived','page-title provenance for filename disambiguation')
need(media_inbox,'hasExplicitTitle = titleIsPageDerived','URL fallback names must not receive page-title discriminators')
need(phase4_contract,'val terminalPolicy = source("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TerminalNotificationActionPolicy.kt")','Phase-4 contract terminal policy source binding')
need(phase4_contract,'terminalNotificationsLocked().firstOrNull { it.idempotencyKey == record.idempotencyKey }','Phase-4 contract two-phase terminal idempotency')
need(completed_notification_contract,'TerminalNotificationActionPolicy.kt','completed notification contract shared terminal policy')
need(completed_notification_contract,'QueueControlCommand.OpenOne -> addAction','completed notification contract production action mapping')
need(live_locator_contract,'hasExplicitTitle = titleIsPageDerived','Live Locator naming contract explicit page-title provenance')
need(post_ux13_contract,'current_runtime_quality_authority','post-UX13 baseline contract current runtime authority')
need(remediation_0607_contract,'terminalNotificationsLocked().firstOrNull { it.idempotencyKey == record.idempotencyKey }','remediation 06/07 durable terminal identity')
need(remediation_13_contract,'current_runtime_quality_authority','phase 13 contract current runtime authority')
need(ux13_contract,'current_runtime_quality_authority','UX13 baseline current runtime authority')
need(ux01_contract,'File(requireNotNull(System.getProperty("user.dir")))','UX01 test root warning-free binding')
if 'current Room schema v21' in architecture_contract:
    errors.append('architecture contract still treats Room 21 as current')
for current_schema_contract, label in [
    (post_ux13_contract,'post-UX13 current schema'),
    (remediation_13_contract,'phase 13 current schema'),
    (ux13_contract,'UX13 current schema'),
]:
    if 'assertTrue(' in current_schema_contract and '\"version\": 21' in current_schema_contract:
        errors.append(f'{label}: stale Room 21 current-schema assertion remains')
entry=manifest.get('notification_webview_gap_hotfix_2026_09_10',{})
if entry.get('status') not in {'implemented','implemented_final_hotfix','implemented_final_hotfix_v2','implemented_final_hotfix_v3','implemented_final_hotfix_v4','implemented_final_hotfix_v5'}: errors.append('manifest hotfix status missing')
if entry.get('room_schema_current') != 22: errors.append('historical notification/WebView hotfix schema must remain 22')
if manifest.get('database',{}).get('version') != 24: errors.append('authoritative database schema must be 24')
if entry.get('overlay') != 'xdm_android_notification_webview_gap_hotfix_v5.zip': errors.append('manifest must name replacement v5 overlay')
for key in ['second_pass_source_audited','persisted_artwork_quality_merge','video_artwork_continuity_remote_before_generated_frame','all_media_output_generations_receive_download_artwork','content_disposition_filename_is_effective_when_blank','uidt_active_end_policy_remove','terminal_repost_only_alert_once','terminal_delivery_mark_after_success_only','terminal_actions_rehydrate_from_production_policy','full_gradle_followup_repaired','media_url_fallback_filename_preserved','android_resource_compile_repaired','compose_weight_compile_repaired','scheduler_lint_cleanups_repaired','full_gradle_unit_test_compile_repaired','media_locator_bundle_warning_cleanup','full_gradle_contract_assertions_rebased','current_schema_contracts_rebased_to_22','terminal_notification_contracts_use_shared_policy','unit_test_user_dir_warning_cleanup']:
    if entry.get(key) is not True: errors.append(f'manifest second-pass promise missing: {key}')
if entry.get('remaining_gaps') != []: errors.append('manifest must not claim unresolved second-pass gaps')
if '21_to_22' not in manifest.get('database',{}).get('migrations',[]): errors.append('manifest migration chain missing 21_to_22')
if '22_to_23' not in manifest.get('database',{}).get('migrations',[]): errors.append('manifest migration chain missing Parity02 22_to_23')

if errors:
    print('Notification/WebView gap hotfix validator FAILED')
    for e in errors: print('-',e)
    sys.exit(1)
print('Notification/WebView gap hotfix validator: OK')
