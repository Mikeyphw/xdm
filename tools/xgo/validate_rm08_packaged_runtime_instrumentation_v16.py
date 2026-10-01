#!/usr/bin/env python3
from pathlib import Path
import sys

path = Path("app/XDM.Android/app/src/androidTest/kotlin/com/mikeyphw/xdm/android/Aria2Rm08PackagedRuntimeInstrumentedTest.kt")
text = path.read_text(encoding="utf-8")
required = [
    "fun packagedRuntimeEnforcesTlsAndRecoversSavedOwnedSession()",
    "verifyPackagedRuntimeAndTls(fixture)",
    "verifyOwnedSessionRecovery(fixture)",
    "fixture.reset()",
    "awaitStatus(",
    "awaitSessionRecord(",
    "awaitSessionRecordAbsent(",
    "assertSessionRecord(store.sessionFile, gid, sourceUrl)",
    "pause = true",
    "continueDownload = false",
    "manager = fixture.newManager(store)",
    "assertTrue(\"aria2 shutdown must be clean for Gate-12\", stopped.clean)",
    "putString(\"xgo_rm08_claims\", \"packaged-bytes,rpc-auth,tls-reject,tls-success,session-restart-recovery\")",
]
missing = [token for token in required if token not in text]
if missing:
    print("ERROR: RM08 v16 validation token(s) missing:")
    for token in missing:
        print(f"- {token}")
    raise SystemExit(1)
for forbidden in [
    "val created = rpc.tellStatus(recoveryGid)",
    "delay(100L)",
    "withTimeout(30_000L)",
]:
    if forbidden in text:
        print(f"ERROR: obsolete RM08 test pattern retained: {forbidden}")
        raise SystemExit(1)
if text.count("@Test") != 1:
    print("ERROR: RM08 v16 must retain exactly one JUnit entry point")
    raise SystemExit(1)
if text.count("fixture.reset()") < 3:
    print("ERROR: RM08 v16 does not isolate TLS and recovery phases")
    raise SystemExit(1)
print("XGO RM08 packaged-runtime instrumentation redesign v16: PASS")
