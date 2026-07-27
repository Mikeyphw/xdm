#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
checks = []

def read(rel: str) -> str:
    return (ROOT / rel).read_text(encoding="utf-8")

def require(name: str, condition: bool) -> None:
    if not condition:
        raise SystemExit(f"Phase 45 validation failed: {name}")
    checks.append(name)

notifications = read("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferNotifications.kt")
activity = read("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/OpenDownloadedFileActivity.kt")
manifest = read("scheduler/src/main/AndroidManifest.xml")
runtime = read("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferExecutionRuntime.kt")
models = read("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferExecutionModels.kt")
paths = read("scheduler/src/main/res/xml/xdm_completed_download_paths.xml")
contract = read("app/src/test/kotlin/com/mikeyphw/xdm/android/CompletedNotificationPhase45ContractTest.kt")
project_manifest = read("PROJECT_MANIFEST.json")

require("completed notification action constant", "ACTION_OPEN_COMPLETED_DOWNLOAD" in notifications)
require("download details action constant", "ACTION_OPEN_DOWNLOAD_DETAILS" in notifications)
require("completed tap uses activity pending intent", "PendingIntent.getActivity" in notifications and "OpenDownloadedFileActivity::class.java" in notifications)
require("notification contains only download id for completed open", "putExtra(EXTRA_DOWNLOAD_ID, downloadId)" in notifications)
require("notification builder does not embed ACTION_VIEW file intent", not ("Intent.ACTION_VIEW" in notifications and "setDataAndType" in notifications))
require("completed tap routes to trampoline", "if (state == DownloadState.Completed)" in notifications and "openCompletedPendingIntent(downloadId)" in notifications)
require("completed action opens XDM/details", "DownloadState.Completed -> addAction(android.R.drawable.ic_menu_view, \"Open XDM\", openAppPendingIntent(downloadId))" in notifications)
require("failed paused recovery still use app/details content intent", "openAppPendingIntent(downloadId)" in notifications)
require("trampoline activity is non-exported", "android:name=\".OpenDownloadedFileActivity\"" in manifest and "android:exported=\"false\"" in manifest)
require("fileprovider declared", "androidx.core.content.FileProvider" in manifest and "${applicationId}.completed-downloads" in manifest)
require("fileprovider paths declared", "root-path" in paths and "external-path" in paths)
require("trampoline revalidates download lookup", "findDownload(downloadId)" in activity and "download.state != DownloadState.Completed" in activity)
require("trampoline grants read permission", "Intent.FLAG_GRANT_READ_URI_PERMISSION" in activity)
require("trampoline uses ACTION_VIEW with mime fallback", "Intent(Intent.ACTION_VIEW)" in activity and "?: \"*/*\"" in activity)
require("trampoline converts file uri through FileProvider", "FileProvider.getUriForFile" in activity)
require("trampoline falls back to XDM", "openXdmDetails" in activity and "no-viewer" in activity and "uri-permission-lost" in activity)
require("terminal event carries destination and mime", "val destinationUri: String? = null" in models and "val mimeType: String? = null" in models)
require("runtime persists concrete completed uri", "destinationUri = if (verifiedSnapshot.state == DownloadState.Completed" in runtime and "verifiedSnapshot.completedUri" in runtime)
require("app contract test present", "CompletedNotificationPhase45ContractTest" in contract)
require("project manifest records phase 45", "browser_bridge_phase45_completed_notification_open_file_intent" in project_manifest)
print(f"Phase 45 completed notification open-file validation passed ({len(checks)} checks).")
