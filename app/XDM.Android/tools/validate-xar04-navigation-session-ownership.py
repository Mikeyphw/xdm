#!/usr/bin/env python3
from pathlib import Path
import re
ROOT = Path(__file__).resolve().parents[1]
errors=[]
def read(rel):
    p=ROOT/rel
    if not p.exists():
        errors.append(f"missing {rel}"); return ""
    return p.read_text(encoding='utf-8')
def require(cond,msg):
    if not cond: errors.append(msg)

nav=read('app/src/main/kotlin/com/mikeyphw/xdm/android/AddDownloadNavigationSession.kt')
app=read('app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt')
vm=read('app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt')
add=read('app/src/main/kotlin/com/mikeyphw/xdm/android/ui/intake/AddDownloadSurface.kt')
fold=read('app/src/main/kotlin/com/mikeyphw/xdm/android/XdmFoldPostureSource.kt')
shell=read('app/src/main/kotlin/com/mikeyphw/xdm/android/XdmAdaptiveShell.kt')
downloads=read('app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadsScreen.kt')
prefs=read('app/src/main/kotlin/com/mikeyphw/xdm/android/UserPreferencesStore.kt')
activity=read('core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/OperationalActivity.kt')
build=read('app/build.gradle.kts')
gate=read('tools/run-final-release-gate.sh')
manifest=read('PROJECT_MANIFEST.json')
test=read('app/src/test/kotlin/com/mikeyphw/xdm/android/Xar04NavigationSessionOwnershipContractTest.kt')

owned = {
 'S02-01','S02-02','S02-03','S02-04','S02-05','S02-06','S02-07','S02-08',
 'DS1-S02-01','DS1-S02-02','DS1-S02-03','DS1-S02-04','DS1-S02-05','DS1-S02-06','DS1-S02-07','RERUN-S02-01'
}
report=read('XDM_ANDROID_XAR04_NAVIGATION_SESSION_OWNERSHIP_REPORT.md')
for fid in owned:
    require(fid in report, f'{fid} missing from report')

require('data class AddDownloadNavigationSession' in nav, 'Add Download route needs authoritative session model')
require('AddDownloadSessionKind' in nav and 'returnRoute: AppRoute' in nav and 'ownerDraftId: String?' in nav, 'session must own kind, return route, and draft id')
require('visibleRoute(route: AppRoute, session: AddDownloadNavigationSession?)' in nav, 'visible route must be policy-owned')
require('sanitizeReturnRoute' in nav and 'RestorablePrimaryRoutes' in nav and 'AppRoute.Add' not in nav.split('RestorablePrimaryRoutes =',1)[1].split(')',1)[0], 'Add cannot be restorable as primary destination')

require('val addDownloadSession by viewModel.addDownloadSessionState.collectAsStateWithLifecycle()' in app, 'XdmApp must collect add session once from VM')
require('activeExternalDraft = state.externalAddDraft?.takeIf' in app and 'addDownloadSession?.ownsDraft(draft.id)' in app, 'external draft must be session-owned before rendering')
require('onAddDownload = viewModel::beginManualAddDownload' in app, 'shell Add action must not call raw navigate(Add)')
require('viewModel.dismissAddDownloadSession()' in app, 'sheet dismiss/back must go through session dismiss owner')
require('activeExternalDraft?.takeIf { it.url == url.trim() }' in app, 'Inspect Media must not reuse stale external draft after URL edit')

require('val addDownloadSessionState: StateFlow<AddDownloadNavigationSession?>' in vm, 'VM must expose session state')
require('fun beginManualAddDownload()' in vm and 'externalAddDraft.value = null' in vm, 'manual Add must clear stale external draft')
require('showAddDownloadSession(AddDownloadNavigationPolicy.externalSession' in vm, 'external drafts must create owned session')
require('activeAddSession?.ownsDraft(draft.id) == true && draft.url == url.trim()' in vm, 'addDownload must treat edited external form as manual/headerless')
require('pending.addSessionId != null && session?.sessionId != pending.addSessionId' in vm, 'duplicate decision must be session-fenced')
require('recordDuplicateDecision(pending.requestedUrl)' in vm, 'duplicate prompt must bind decision URL')
require('selectedProblemId = navigation.selectedProblemId ?: prefs.selectedProblemId' in vm, 'problem target must restore from durable preferences')
require('preferences.setProblemNavigation(normalized)' in vm, 'problem notification must persist target')

require('addDownloadSessionId: String? = null' in add and 'rememberSaveable(formSessionKey)' in add and 'LaunchedEffect(formSessionKey, externalDraftId)' in add, 'Add form state must be keyed by session to prevent stale draft restore')
require('enum class OperationalActivityActionId' in activity and 'actionId: OperationalActivityActionId?' in activity, 'Activity actions must dispatch by stable ID')
require('OperationalActivityActionId.fromLegacyLabel' in app and 'when (event.actionId ?: OperationalActivityActionId.fromLegacyLabel' in app, 'Activity UI must prefer stable action ID over display label')
require('SelectedProblemId' in prefs and 'setProblemNavigation(problemId: String)' in prefs, 'problem notification target must be durable')
require('selectAuthoritativeFoldingFeature' in fold and 'sortedWith(' in fold and 'firstOrNull()' in fold, 'fold policy must consider all FoldingFeatures')
require('fallbackShellTag' in shell and 'windowProfile.windowClass == XdmWindowClass.Expanded -> XdmScreenTags.ShellExpanded' in shell, 'expanded fallback must not be tagged Medium')
require('detailSelectionUserOwned' in downloads and 'onDetailSelectionChanged(detailDownloadId.takeIf { detailSelectionUserOwned || !twoPaneLayoutActive })' in downloads, 'auto two-pane detail selection must not force compact modal after restore')
require('verifyXar04NavigationSessionOwnership' in build, 'Gradle verification task missing')
require('validate-xar04-navigation-session-ownership.py' in gate, 'final gate missing XAR04 validator')
require('xdm_android_xar04_navigation_session_ownership_v1.tar.gz' in manifest, 'project manifest current overlay missing XAR04')
require('externalDraftIsOwnedByExactlyOneAddSession' in test and 'duplicateDecisionIsBoundToExactSessionUrl' in test, 'focused policy regression tests missing')

if errors:
    print('XAR04 navigation/session ownership validation failed:')
    for e in errors: print(' - '+e)
    raise SystemExit(1)
print('XAR04 navigation/session ownership contract passed: 16/16 S02 findings covered.')
