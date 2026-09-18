#!/usr/bin/env python3
from pathlib import Path
r=Path(__file__).resolve().parents[1]
def need(path, *terms):
    s=(r/path).read_text()
    for term in terms:
        assert term in s, f"{path}: missing {term}"
need(Path("app/src/main/kotlin/com/mikeyphw/xdm/android/ffmpeg/NativeHlsMediaManager.kt"), "probeResourceLength", "it.all { part -> part.expectedBytes != null }", "part.expectedBytes ?: part.byteRange?.length")
need(Path("media/src/main/kotlin/com/mikeyphw/xdm/android/media/NativeHlsExecutionEngine.kt"), "it.all { part -> part.expectedBytes != null }")
need(Path("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferLaunchPolicy.kt"), "userVisible -> TransferLaunchMode.ForegroundService")
need(Path("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/activity/ActivityScreen.kt"), 'Text("Remove")', 'Text("Clear activity")')
need(Path("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt"), "clearHistory(preserveUnresolved = false)")
need(Path("transfer-aria2/src/main/kotlin/com/mikeyphw/xdm/android/transfer/aria2/Aria2ProcessManager.kt"), "Runtime log:")
print("post-roadmap runtime hotfix static seal: PASS")

# V4 retained-contract convergence.
need(Path("app/src/test/kotlin/com/mikeyphw/xdm/android/Phase63ReleaseReadinessSupportBundleSealContractTest.kt"), "supportBundleSealSeparatesMissingAttestationFromRuntimePrivacyFailure")
need(Path("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/AndroidTransferRequestSecurityGuard.kt"), "InetAddress.getAllByName(host)")
need(Path("app/src/test/kotlin/com/mikeyphw/xdm/android/RemediationPhase04_05ContractTest.kt"), "userVisible -> TransferLaunchMode.ForegroundService")
need(Path("app/src/test/kotlin/com/mikeyphw/xdm/android/Ux03DownloadsQueueProductUiContractTest.kt"), "destinationCardLabel(download)")
print("post-roadmap runtime hotfix v4 retained-contract seal: PASS")
