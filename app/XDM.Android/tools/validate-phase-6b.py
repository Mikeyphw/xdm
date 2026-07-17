#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REPO = ROOT.parents[1]

checks = {
    "runtime models": ROOT / "transfer-aria2/src/main/kotlin/com/mikeyphw/xdm/android/transfer/aria2/Aria2RuntimeModels.kt",
    "android environment": ROOT / "transfer-aria2/src/main/kotlin/com/mikeyphw/xdm/android/transfer/aria2/Aria2AndroidEnvironment.kt",
    "session store": ROOT / "transfer-aria2/src/main/kotlin/com/mikeyphw/xdm/android/transfer/aria2/Aria2SessionStore.kt",
    "rpc client": ROOT / "transfer-aria2/src/main/kotlin/com/mikeyphw/xdm/android/transfer/aria2/Aria2RpcClient.kt",
    "process manager": ROOT / "transfer-aria2/src/main/kotlin/com/mikeyphw/xdm/android/transfer/aria2/Aria2ProcessManager.kt",
    "backend": ROOT / "transfer-aria2/src/main/kotlin/com/mikeyphw/xdm/android/transfer/aria2/EmbeddedAria2Backend.kt",
    "tests": ROOT / "transfer-aria2/src/test/kotlin/com/mikeyphw/xdm/android/transfer/aria2/Aria2ProcessManagerTest.kt",
    "architecture": ROOT / "docs/architecture/PHASE-6B-ARIA2-RUNTIME-FOUNDATION.md",
    "binary packaging": ROOT / "transfer-aria2/ARIA2_BINARY_PACKAGING.md",
}
for label, path in checks.items():
    assert path.is_file(), f"Missing {label}: {path}"

models = checks["runtime models"].read_text()
environment = checks["android environment"].read_text()
session = checks["session store"].read_text()
rpc = checks["rpc client"].read_text()
manager = checks["process manager"].read_text()
backend = checks["backend"].read_text()
application = (ROOT / "app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApplication.kt").read_text()
manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text()
app_gradle = (ROOT / "app/build.gradle.kts").read_text()
aria2_gradle = (ROOT / "transfer-aria2/build.gradle.kts").read_text()
screens = (ROOT / "app/src/main/kotlin/com/mikeyphw/xdm/android/Screens.kt").read_text()
workflow = (REPO / ".github/workflows/android.yml").read_text()

assert 'ARIA2_PACKAGED_BINARY_NAME = "libaria2c.so"' in models
assert 'ARIA2_PRIMARY_ABI = "arm64-v8a"' in models
assert 'nativeLibraryDir' in environment
assert 'filesDir, "aria2"' in session
assert '.xdm.aria2.part' in session
assert 'rpc-listen-all=false' in session
assert 'rpc-secret=' in session
assert 'tokenParameter()' in rpc
assert 'Proxy.NO_PROXY' in rpc
assert '127.0.0.1' in models
assert 'processLauncher.launch(prepared.plan)' in manager
assert 'sessionStore.deleteLaunchConfiguration(prepared.configuration)' in manager
assert 'saveSession()' in manager and 'shutdown(force = false)' in manager
assert 'EmbeddedAria2Backend' in application
assert 'Aria2BackendPlaceholder(' not in application
assert not (ROOT / 'transfer-aria2/src/main/kotlin/com/mikeyphw/xdm/android/transfer/aria2/Aria2BackendPlaceholder.kt').exists()
assert 'android:extractNativeLibs=' not in manifest
assert 'jniLibs.useLegacyPackaging = true' in app_gradle
assert 'jniLibs.useLegacyPackaging = true' in aria2_gradle
assert 'jniLibs.keepDebugSymbols += "**/libaria2c.so"' in app_gradle
assert 'jniLibs.keepDebugSymbols += "**/libaria2c.so"' in aria2_gradle
assert 'Run probe' in screens
assert 'process.pid()' not in (ROOT / 'transfer-aria2/src/main/kotlin/com/mikeyphw/xdm/android/transfer/aria2/Aria2ProcessLauncher.kt').read_text()
assert 'fun deleteLaunchConfiguration(file: File): Boolean' in models
assert 'file.writeText("", Charsets.UTF_8)' in session
assert 'process not bundled yet' not in screens.lower()
assert 'phase 6' not in screens.lower()
assert ':transfer-aria2:test' in workflow
assert 'validate-phase-6.py' in workflow

native_payload_root = ROOT / "transfer-aria2/src/main/jniLibs"
if native_payload_root.exists():
    unexpected_payloads = [path for path in native_payload_root.rglob("*") if path.is_file() and path.suffix != ".so"]
    assert not unexpected_payloads, f"Non-native files found under jniLibs: {unexpected_payloads}"

writable_exec_patterns = [
    'File(context.filesDir, "aria2c")',
    'File(context.cacheDir, "aria2c")',
    '/data/user/0/com.mikeyphw.xdm.android/files/aria2/aria2c',
]
all_runtime_source = "\n".join(path.read_text() for path in checks.values() if path.suffix == ".kt")
for pattern in writable_exec_patterns:
    assert pattern not in all_runtime_source, f"Writable executable path detected: {pattern}"

print("Phase 6B validation passed: ARM64 native packaging, loopback RPC, secret redaction, session persistence, lifecycle supervision, diagnostics, and task gate are present")
