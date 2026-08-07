#!/usr/bin/env python3
from pathlib import Path
import json
import sys

ROOT = Path(__file__).resolve().parents[1]
failures = []

def req(cond, msg):
    if not cond:
        failures.append(msg)

def text(rel):
    p = ROOT / rel
    req(p.is_file(), f"missing {rel}")
    return p.read_text(encoding="utf-8") if p.is_file() else ""

manifest = text("app/src/main/AndroidManifest.xml")
manager = text("transfer-aria2/src/main/kotlin/com/mikeyphw/xdm/android/transfer/aria2/Aria2ProcessManager.kt")
runtime = text("transfer-aria2/src/main/kotlin/com/mikeyphw/xdm/android/transfer/aria2/Aria2RuntimeModels.kt")
rpc = text("transfer-aria2/src/main/kotlin/com/mikeyphw/xdm/android/transfer/aria2/Aria2RpcClient.kt")
session = text("transfer-aria2/src/main/kotlin/com/mikeyphw/xdm/android/transfer/aria2/Aria2SessionStore.kt")
storage = text("storage/src/main/kotlin/com/mikeyphw/xdm/android/storage/AndroidDestinationWriter.kt")
doctor = text("storage/src/main/kotlin/com/mikeyphw/xdm/android/storage/DirectStorageDoctor.kt")
direct = text("storage/src/main/kotlin/com/mikeyphw/xdm/android/storage/PersonalDirectStorage.kt")
file_writer = text("storage/src/main/kotlin/com/mikeyphw/xdm/android/storage/FileDestinationWriter.kt")
catalog = text("storage/src/main/kotlin/com/mikeyphw/xdm/android/storage/DestinationCatalog.kt")
settings = text("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/settings/SettingsScreen.kt")
intake = text("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/intake/AddDownloadSurface.kt")
main_vm = text("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")
termux_models = text("app/src/main/kotlin/com/mikeyphw/xdm/android/termux/TermuxBridgeModels.kt")
termux_manager = text("app/src/main/kotlin/com/mikeyphw/xdm/android/termux/TermuxBridgeManager.kt")
termux_templates = text("app/src/main/kotlin/com/mikeyphw/xdm/android/termux/TermuxShellTemplates.kt")
native_probe = text("transfer-native/src/main/kotlin/com/mikeyphw/xdm/android/transfer/nativeengine/NativeStoragePathProbe.kt")
acceptance = text("docs/quality/RUNTIME-FOUNDATION-PHASE55-56-ACCEPTANCE.md")
audit = text("docs/quality/RUNTIME-FOUNDATION-PHASE55-56-PROMISE-CLOSURE-AUDIT.md")
contract = text("app/src/test/kotlin/com/mikeyphw/xdm/android/RuntimeFoundationPhase55_56PromiseClosureContractTest.kt")
project_manifest = text("PROJECT_MANIFEST.json")

# Phase 55: RPC/runtime repair.
req("android.permission.MANAGE_EXTERNAL_STORAGE" in manifest, "personal build must declare all-files access")
for token in [
    "ConnectionRefused", "Unauthorized", "HttpFailure", "MalformedResponse", "RpcFailure",
    "ConfigurationInvalid", "PortUnavailable", "BinaryLoadFailure", "ProcessExited", "Timeout",
    "LaunchFailure", "ConfigurationCleanup", "AuthenticationBoundary", "RuntimeOwnership", "OrphanRecovery",
]:
    req(token in runtime, f"aria2 startup failure kind missing {token}")
for token in ["secretGeneration", "startedAtEpochMs", "processId"]:
    req(token in runtime or token in manager, f"aria2 runtime state missing {token}")
for token in [
    "rejectsUnauthenticated", "cleanupTransientLaunchConfigurations", "rotatable.rotate()", "safeLogTail",
    "rpc.addUri", "rpc.tellStatus", "rpc.pause", "rpc.unpause", "rpc.saveSession", "rpc.removeDownloadResult",
    "LoopbackProbeServer", "storageProbe", "reconcilePersistedRuntime", "waitUntilRpcStops",
]:
    req(token in manager, f"aria2 manager missing promise-closure behavior {token}")
req("Aria2RpcProtocolException" in rpc, "malformed aria2 RPC responses need a dedicated protocol exception")
req("readRuntimeLogTail" in session, "aria2 session store must expose a bounded/redacted log tail")
req("Aria2RuntimeLease" in runtime and "Aria2OrphanRecovery" in runtime, "aria2 runtime must model durable ownership and orphan recovery")
for token in ["runtime-owner.properties", "writeRuntimeLease", "readRuntimeLease", "clearRuntimeLease"]:
    req(token in session, f"aria2 session store missing persisted runtime ownership behavior {token}")
req("RecoveredOwnedDaemon" in manager, "aria2 manager must identify recovered XDM-owned orphan daemons")
for token in ["cannot link executable", "address already in use", "unknown option"]:
    req(token in manager, f"aria2 log classifier missing {token!r}")

# Phase 56: direct storage and fallbacks.
req("ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION" in direct, "direct storage permission intent missing")
req("Environment.isExternalStorageManager()" in direct, "direct storage grant verification missing")
for token in ["customDirectoryUri", "directoryForDestination", "rawDirectory.isAbsolute", "Android/data", "Android/obb"]:
    req(token in direct, f"custom direct-storage guard missing {token}")
req("DestinationUris.DIRECT_DOWNLOADS" in storage, "direct destination is not wired to Android writer")
req("runDirectStorageDoctor(destinationUri" in storage, "Android writer must run doctor against an explicit direct destination")
req("OpenDocumentTree" in intake and "OpenDocumentTree" in settings, "SAF fallback must remain available in intake and settings")
req("DestinationUris.PUBLIC_DOWNLOADS" in catalog and "Public Downloads via Android" in settings, "MediaStore fallback must remain visible")
req("Direct file access" in settings and "PersonalDirectStorage.permissionIntent(context)" in settings, "settings must expose personal direct-storage grant/use flow")
req("Custom direct folder" in settings and "PersonalDirectStorage.customDirectoryUri" in settings, "custom direct folder UI is missing")
req("Storage doctor" in settings and "runStorageDoctor" in settings, "Storage Doctor action is missing from Settings")
for step in ["permission", "mkdir", "create", "write+fsync", "rename", "read", "delete"]:
    req(f'"{step}"' in doctor, f"Storage Doctor missing filesystem step {step}")
req("output.fd.sync()" in doctor, "Storage Doctor must fsync the probe file")
req("nativeStoragePathProbe.run(directDestination)" in main_vm, "Storage Doctor must exercise the native destination path")
req("aria2ProcessManager.storageProbe(directory)" in main_vm, "Storage Doctor must make embedded aria2 write to the selected direct directory")
req("runStoragePathProbe(directory.absolutePath)" in main_vm, "Storage Doctor must run the Termux target-path probe")
req("selectedDestination = uiState.value.destinationUri" in main_vm, "Storage Doctor must inspect the currently selected destination")
for token in ["DestinationWriter", "artifacts.stagingFile", ".promote()", "output.fd.sync()"]:
    req(token in native_probe, f"native destination probe missing {token}")
req("StoragePathProbe" in termux_models and "runStoragePathProbe" in termux_manager, "typed Termux storage-path probe is missing")
for token in ["TARGET=${shellQuote(path)}", "yt-dlp --version", "ffmpeg -version", "XDM_STORAGE_PROBE", "cleanup()"]:
    req(token in termux_templates, f"Termux storage probe missing {token}")
req('if (request.destinationUri.endsWith(\'/\')) file.resolve(request.fileName)' in file_writer, "directory-style custom file URI must append requested file name")

# DocumentsProvider safety.
all_storage = "\n".join(p.read_text(encoding="utf-8") for p in (ROOT / "storage/src/main/kotlin").rglob("*.kt"))
req("File(uri.path)" not in all_storage, "DocumentsProvider URI must never be converted through File(uri.path)")
req("ContentResolver" in storage, "SAF/content destination writer must retain ContentResolver")

# Contract/docs/schema.
req("RuntimeFoundationPhase55_56PromiseClosureContractTest" in contract, "promise-closure app contract test missing")
for phrase in ["malformed response", "Storage Doctor", "custom", "native backend", "Room schema remains 17"]:
    req(phrase.lower() in acceptance.lower(), f"acceptance contract missing {phrase}")
req("five roadmap promises were incomplete" in audit, "promise-closure audit must explicitly record all r1 gaps")
req("app-process restart" in audit.lower() and "owned" in audit.lower(), "promise-closure audit must seal persisted owned-daemon recovery")
try:
    parsed = json.loads(project_manifest)
    rf = parsed.get("runtime_foundation_2026_phase55_56", {})
    req(rf.get("artifact_revision") == "r2_promise_closure", "PROJECT_MANIFEST must identify r2 promise closure")
    req(rf.get("phase56_personal_direct_storage", {}).get("room_schema_unchanged") == 17, "Room schema contract must remain 17")
    req(rf.get("phase56_personal_direct_storage", {}).get("storage_doctor") is True, "PROJECT_MANIFEST must seal Storage Doctor")
except Exception as error:
    failures.append(f"PROJECT_MANIFEST.json could not be parsed: {error}")

if failures:
    print("Runtime foundation Phase 55-56 promise-closure validation failed:")
    for failure in failures:
        print("-", failure)
    sys.exit(1)
print("RUNTIME_FOUNDATION_PHASE55_56_PROMISE_CLOSURE_VALIDATION_OK")
