#!/usr/bin/env python3
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REPO = ROOT.parents[1]

errors: list[str] = []

def read(rel: str) -> str:
    path = REPO / rel
    if not path.is_file():
        errors.append(f"missing file: {rel}")
        return ""
    return path.read_text(encoding="utf-8")

def require(rel: str, *needles: str) -> None:
    text = read(rel)
    for needle in needles:
        if needle not in text:
            errors.append(f"{rel}: missing contract {needle!r}")

def forbid(rel: str, *needles: str) -> None:
    text = read(rel)
    for needle in needles:
        if needle in text:
            errors.append(f"{rel}: forbidden contract remains {needle!r}")

# Export boundary and review/authentication surfaces.
manifest_rel = "app/XDM.Android/app/src/main/AndroidManifest.xml"
manifest = read(manifest_rel)
require(
    manifest_rel,
    'android:name=".ExternalAutomationActivity"',
    'android:name=".ExternalAddDownloadActivity"',
    'android:networkSecurityConfig="@xml/network_security_config"',
    'android:usesCleartextTraffic="false"',
    'android:dataExtractionRules="@xml/data_extraction_rules"',
    'android:fullBackupContent="@xml/backup_rules"',
)
main_block = re.search(r'<activity\s+android:name="\.MainActivity".*?</activity>', manifest, re.S)
if not main_block:
    errors.append("MainActivity manifest block not found")
else:
    for action in (
        "com.mikeyphw.xdm.android.ADD_URL",
        "com.mikeyphw.xdm.android.CAPTURE_MEDIA",
        "com.mikeyphw.xdm.android.PAUSE_ALL",
        "com.mikeyphw.xdm.android.RESUME_ALL",
    ):
        if action in main_block.group(0):
            errors.append(f"MainActivity still exports mutating action {action}")
require(
    "app/XDM.Android/app/src/main/kotlin/com/mikeyphw/xdm/android/ExternalAutomationActivity.kt",
    "ExternalAutomationTrustStore",
    "ExternalCommandAuthorization.IntegrationToken",
    "Approve external automation",
    "privateNetworkApproved",
)
require(
    "app/XDM.Android/app/src/main/kotlin/com/mikeyphw/xdm/android/ExternalHandoffReviewActivity.kt",
    "ExternalCommandAuthorization.UserConfirmed",
    "Open in XDM",
    "InternalAutomationDispatchStore.issue",
)
require(
    "app/XDM.Android/app/src/main/kotlin/com/mikeyphw/xdm/android/MainActivity.kt",
    "ACTION_INTERNAL_AUTOMATION_DISPATCH",
    "InternalAutomationDispatchStore.consume",
)
forbid(
    "app/XDM.Android/app/src/main/kotlin/com/mikeyphw/xdm/android/MainActivity.kt",
    "TaskerContract.draftFor(",
)
require(
    "app/XDM.Android/app/src/main/kotlin/com/mikeyphw/xdm/android/MainActivity.kt",
    "handleExternalIntent(intent)",
    "parseDetailed",
    "is XdmBrowserDeepLinkParseResult.Rejected -> return",
    "sharedText(incoming)",
    "Intent.EXTRA_TEXT",
    "clipData",
    "shouldOpenExternalAddPrompt",
    "handoffMimeType",
    "handoffContentLength",
    "handoffPageUrl",
    "mimeType = mimeType",
    "contentLength = contentLength",
    "viewModel.ingestAutomationCommand(draft)",
)
require(
    "app/XDM.Android/app/src/main/kotlin/com/mikeyphw/xdm/android/ExternalAddDownloadActivity.kt",
    "class ExternalAddDownloadActivity : MainActivity()",
)
require(
    "app/XDM.Android/app/src/main/kotlin/com/mikeyphw/xdm/android/ExternalAutomationSecurity.kt",
    "data class ExternalCallerIdentity",
    "observedPackage",
    "claimedPackage",
    "MessageDigest.isEqual",
    "noBackupFilesDir",
    "One-use",
)

# URL/network target policy and request execution gates.
require(
    "app/XDM.Android/core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/AutomationModels.kt",
    "enum class ExternalCommandAuthorization",
    "enum class ExternalNetworkTarget",
    "classifyNetworkTarget",
    "requiresPrivateNetworkApproval",
    "hasCredentialBearingQuery",
    "persistableUrl",
    "redactQueryParameter",
    "normalizeQueryName",
    "sensitiveQuerySuffixes",
    "claimedOriginPackage",
    "verifiedIntegrationId",
)
require(
    "app/XDM.Android/scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/AndroidTransferRequestSecurityGuard.kt",
    "NetworkSecurityPolicy",
    "isAndroidJvmUnitTestStub",
    "not mocked",
    "cleartextCredentialsApproved",
    "privateNetworkApproved",
    "InetAddress.getAllByName",
)
require(
    "app/XDM.Android/transfer-native/src/main/kotlin/com/mikeyphw/xdm/android/transfer/nativeengine/NativeHttpDownloadBackend.kt",
    "NativeRequestSecurityInterceptor",
    "NativeAndroidNetworkSecurityPolicy",
    "isAndroidJvmUnitTestStub",
    "not mocked",
    "HTTPS-to-HTTP redirect blocked",
    "Redirect to a private or unresolved network target blocked",
)
require(
    "app/XDM.Android/transfer-api/src/main/kotlin/com/mikeyphw/xdm/android/transfer/DownloadBackend.kt",
    "privateNetworkApproved",
    "cleartextCredentialsApproved",
)
forbid(
    "app/XDM.Android/app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt",
    'setOf("authorization", "cookie", "referer", "user-agent", "origin", "accept", "range")',
)

# Encrypted scoped request envelope and Room redaction/migration.
require(
    "app/XDM.Android/scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/SecureRequestEnvelopeStore.kt",
    "AndroidKeyStore",
    "AES/GCM/NoPadding",
    "updateAAD",
    "boundHost",
    "attemptGeneration",
    "expiresAtEpochMs",
    "noBackupFilesDir",
    "deleteExpired",
)
require(
    "app/XDM.Android/scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/MediaRequestHandoffStore.kt",
    "process-local handoff",
    "AndroidSecureRequestEnvelopeStore",
    "rememberCommand",
    "rememberCapture",
    "rememberVariant",
    "forCommand",
)
require(
    "app/XDM.Android/app/src/main/kotlin/com/mikeyphw/xdm/android/SensitivePersistenceMigrator.kt",
    "migrate",
    "persistableUrl",
)
require(
    "app/XDM.Android/persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/AppDatabase.kt",
    "version = 14",
)
require(
    "app/XDM.Android/persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/Migrations.kt",
    "Migration13To14",
)

# Network security and backup/device-transfer rules.
require(
    "app/XDM.Android/app/src/main/res/xml/network_security_config.xml",
    'cleartextTrafficPermitted="false"',
)
require(
    "app/XDM.Android/app/src/debug/res/xml/network_security_config.xml",
    'cleartextTrafficPermitted="true"',
    "localhost",
    "127.0.0.1",
)
for rel in (
    "app/XDM.Android/app/src/main/res/xml/backup_rules.xml",
    "app/XDM.Android/app/src/main/res/xml/data_extraction_rules.xml",
):
    require(rel, 'domain="database" path="."', 'domain="file" path="."', 'domain="sharedpref" path="."')

forbid(
    "app/XDM.Android/core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/AutomationModels.kt",
    '"$rawName=REDACTED"',
)
forbid(
    "app/XDM.Android/core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/ReleaseSecurityModels.kt",
    '"$name=<redacted>"',
)

# Diagnostics, support export and clipboard privacy.
require(
    "app/XDM.Android/core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DebugEventModels.kt",
    "PrivacyDiagnosticsRedactor.redact",
)
require(
    "app/XDM.Android/core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/ReleaseSecurityModels.kt",
    "PrivacyDiagnosticsRedactor",
)
require(
    "app/XDM.Android/app/src/main/kotlin/com/mikeyphw/xdm/android/ui/common/UiTextHelpers.kt",
    "EXTRA_IS_SENSITIVE",
    "copySensitiveTextToClipboard",
)
require(
    "app/XDM.Android/app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadsScreen.kt",
    "copySensitiveTextToClipboard",
)

# Narrow FileProvider and ownership-checked grants.
paths_rel = "app/XDM.Android/scheduler/src/main/res/xml/xdm_completed_download_paths.xml"
require(paths_rel, '<files-path name="completed_app_private_downloads" path="downloads/"', '<external-files-path name="completed_external_app_downloads" path="Download/"')
forbid(paths_rel, "<root-path", 'path="."')
require(
    "app/XDM.Android/scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/CompletedFileGrantPolicy.kt",
    "DownloadState.Completed",
    "canonicalFile",
    "file.name != download.fileName",
    "FileProvider.getUriForFile",
)
require(
    "app/XDM.Android/scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/OpenDownloadedFileActivity.kt",
    "CompletedFileGrantPolicy.resolve",
)
require(
    "app/XDM.Android/scheduler/src/test/kotlin/com/mikeyphw/xdm/android/scheduler/CompletedNotificationOpenFileContractTest.kt",
    "CompletedFileGrantPolicy.resolve",
    "private val grantPolicy",
    "FileProvider.getUriForFile",
)

# Tests and repository record.
require(
    "app/XDM.Android/core-model/src/test/kotlin/com/mikeyphw/xdm/android/model/ExternalControlSecurityTest.kt",
    "quality=1080&token=REDACTED",
    "publicQueryNamesContainingSecretSubstringsRemainVisible",
    "encodedCredentialQueryNamesAreRedacted",
)
require(
    "app/XDM.Android/core-model/src/test/kotlin/com/mikeyphw/xdm/android/model/ReleaseSecurityModelsTest.kt",
    "token=<redacted>&quality=1080",
    "redactionPreservesPublicQueryFieldsAndAvoidsSubstringFalsePositives",
)
require(
    "app/XDM.Android/core-model/src/test/kotlin/com/mikeyphw/xdm/android/model/DebugEventModelsTest.kt",
    'json.contains("quality=1080")',
)
for rel in (
    "app/XDM.Android/core-model/src/test/kotlin/com/mikeyphw/xdm/android/model/ExternalControlSecurityTest.kt",
    "app/XDM.Android/core-model/src/test/kotlin/com/mikeyphw/xdm/android/model/ReleaseSecurityModelsTest.kt",
    "app/XDM.Android/core-model/src/test/kotlin/com/mikeyphw/xdm/android/model/DebugEventModelsTest.kt",
    "app/XDM.Android/scheduler/src/test/kotlin/com/mikeyphw/xdm/android/scheduler/SecureRequestEnvelopeStoreTest.kt",
    "app/XDM.Android/app/src/androidTest/kotlin/com/mikeyphw/xdm/android/ExternalControlSecurityInstrumentedTest.kt",
    "app/XDM.Android/docs/architecture/BUG-HUNT-REMEDIATION-PHASE-1.md",
):
    read(rel)
manifest_json = read("app/XDM.Android/PROJECT_MANIFEST.json")
if manifest_json:
    try:
        parsed = json.loads(manifest_json)
        phase = parsed.get("bug_hunt_remediation_phase_1", {})
        if phase.get("status") != "implemented" or phase.get("room_schema") != 14 or phase.get("revision") != 5:
            errors.append("PROJECT_MANIFEST phase-1 r5 record is incomplete")
        if phase.get("room_schema_unchanged_for_phase1") is not True:
            errors.append("PROJECT_MANIFEST must record Phase 1 keeps Room schema 14")
        if phase.get("browser_removal_contracts_preserved") is not True:
            errors.append("PROJECT_MANIFEST must record browser-removal contract preservation")
        if phase.get("jvm_network_security_policy_stub_safe") is not True:
            errors.append("PROJECT_MANIFEST must record JVM-safe NetworkSecurityPolicy boundary")
        if phase.get("scheduler_fileprovider_contract_test_aligned") is not True:
            errors.append("PROJECT_MANIFEST must record scheduler FileProvider contract alignment")
        if phase.get("external_add_download_activity_reuses_main_activity_shell") is not True:
            errors.append("PROJECT_MANIFEST must record ExternalAddDownloadActivity shell reuse")
    except json.JSONDecodeError as exc:
        errors.append(f"PROJECT_MANIFEST.json invalid JSON: {exc}")

if errors:
    for error in errors:
        print(f"ERROR: {error}")
    sys.exit(1)
print("Bug Hunt Remediation Phase 1 r5 security/privacy contract passed")
