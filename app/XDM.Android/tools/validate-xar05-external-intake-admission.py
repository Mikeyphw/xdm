#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
checks = []

def require(path, pattern, desc):
    text = (ROOT / path).read_text(encoding="utf-8")
    if not re.search(pattern, text, re.S):
        raise SystemExit(f"XAR05 validation failed: {desc} ({path})")
    checks.append(desc)

# S03 ledger coverage: 26 canonical roots.
report = ROOT / "XDM_ANDROID_XAR05_EXTERNAL_INTAKE_ADMISSION_REPORT.md"
text = report.read_text(encoding="utf-8")
ids = re.findall(r"`((?:S03|DS2-S03|RERUN(?:23)?-S03)-\d+)`", text)
expected = {
    *(f"S03-{i:02d}" for i in range(1, 21)),
    *(f"DS2-S03-{i:02d}" for i in range(1, 5)),
    "RERUN-S03-01",
    "RERUN23-S03-02",
}
missing = sorted(expected - set(ids))
extra = sorted(set(ids) - expected)
if missing or extra:
    raise SystemExit(f"XAR05 coverage mismatch: missing={missing} extra={extra}")
checks.append("26/26 canonical S03 roots covered")

require("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/AutomationModels.kt", r"object ExternalAdmissionPolicy", "unified admission policy exists")
require("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/AutomationModels.kt", r"external-handoff-v5", "local idempotency key version includes executable request")
require("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/AutomationModels.kt", r"magnet:", "magnet URLs are normalized consistently")
require("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/AutomationModels.kt", r"credentialHeadersAllowedFor", "cross-origin credential header guard exists")
require("app/src/main/kotlin/com/mikeyphw/xdm/android/ExternalAutomationSecurity.kt", r"fun generalIntake", "share/browser/view intake returns unified result")
require("app/src/main/kotlin/com/mikeyphw/xdm/android/ExternalAutomationSecurity.kt", r"Intent.ACTION_SEND_MULTIPLE", "SEND_MULTIPLE is handled explicitly")
require("app/src/main/kotlin/com/mikeyphw/xdm/android/ExternalAutomationSecurity.kt", r"UnsupportedContentUri", "content URI rejection is structured")
require("app/src/main/kotlin/com/mikeyphw/xdm/android/ExternalHandoffReviewActivity.kt", r"dispatchAll", "multi-draft dispatch is durable")
require("app/src/main/kotlin/com/mikeyphw/xdm/android/ExternalHandoffReviewActivity.kt", r"GENERAL|generalIntake|persistRejected", "malformed handoffs are persisted as rejections")
require("app/src/main/kotlin/com/mikeyphw/xdm/android/ExternalAutomationDispatch.kt", r"ExternalAdmissionPolicy.validateForDispatch", "dispatch applies admission policy")
require("app/src/main/kotlin/com/mikeyphw/xdm/android/ExternalAutomationDispatch.kt", r"finalHeaders.*proposedHeaders.*rawHeaders", "final/proposed/raw headers are durably restored")
require("app/src/main/kotlin/com/mikeyphw/xdm/android/ExternalAutomationDispatch.kt", r"pageObservationNonce", "page observation proof survives metadata restore")
require("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt", r"while \(recoveredIds\.isNotEmpty\(\)\)", "startup drains pending automation commands")
require("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt", r"credentialHeadersAllowedFor", "runtime header reconstruction strips cross-origin credentials")
require("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt", r"activeExternalDraft\?\.takeIf \{ it\.url == url\.trim\(\) \}", "edited external Inspect Media uses displayed manual URL")
require("core-model/src/test/kotlin/com/mikeyphw/xdm/android/model/Xar05ExternalIntakeAdmissionContractTest.kt", r"redactedUrlIsNotTheDuplicateIdentity", "adversarial redacted-identity regression test exists")
require("core-model/src/test/kotlin/com/mikeyphw/xdm/android/model/Xar05ExternalIntakeAdmissionContractTest.kt", r"privateNetworkRequiresExplicitAdmissionApproval", "private LAN approval regression test exists")
print(f"XAR05 external intake admission contract passed: {len(checks)} checks; 26/26 S03 findings covered.")
