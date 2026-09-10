#!/usr/bin/env python3
from pathlib import Path
import json
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
REPO = ROOT.parents[1]
errors = []

def need(condition: bool, message: str) -> None:
    if not condition:
        errors.append(message)

def text(path: Path) -> str:
    try:
        return path.read_text()
    except Exception as exc:
        errors.append(f"cannot read {path}: {exc}")
        return ""

manifest = json.loads(text(ROOT / "PROJECT_MANIFEST.json") or "{}")
entry = manifest.get("post_dl03_release_followup", {})
dl = manifest.get("download_progress_dl02_dl03", {})
final_gate = text(ROOT / "tools/run-final-release-gate.sh")
common_gate = text(ROOT / "tools/run-final-common-validation.sh")
readme = text(ROOT / "README.md")
root_ci = text(REPO / ".github/workflows/android.yml")
inner_ci = text(ROOT / ".github/workflows/android.yml")
build = text(ROOT / "app/build.gradle.kts")
phase1 = text(ROOT / "tools/validate-bug-hunt-phase1-external-control-secrets-privacy.py")
phase2 = text(ROOT / "tools/validate-bug-hunt-phase2-download-execution.py")
contract_test = text(ROOT / "app/src/test/kotlin/com/mikeyphw/xdm/android/PostDl03ReleaseFollowupContractTest.kt")

for validator in (
    "tools/validate-runtime-foundation-phase59-61.py",
    "tools/validate-dl02-dl03-progress-seal.py",
    "tools/validate-post-dl03-release-followup.py",
):
    need(validator in final_gate, f"canonical final gate missing current validator: {validator}")

need("post-DL03 roadmap seal remains its historical baseline" in final_gate,
     "final gate must preserve the post-DL03 roadmap seal as the historical baseline for later repairs")
need("matrix_owned_validators=(" in final_gate and 'bash tools/run-bug-hunt-phase11-validation-matrix.sh --static-only --ci' in final_gate,
     "final gate must delegate matrix-owned validators to the retained Phase-11 static matrix")
need('for validator in "${validators[@]}"; do' in final_gate,
     "final gate must execute non-matrix validators exactly through the dedicated validators loop")
need(entry.get("static_gate_duplicate_matrix_execution") is False and entry.get("phase11_static_matrix_coverage_retained") is True,
     "manifest must record duplicate static execution removal without coverage loss")
need(":app:finalRemediationStaticGate" in common_gate,
     "common validation must enter through the canonical static gate")
need('commandLine("bash", "tools/run-final-release-gate.sh", "--ci")' in build,
     "Gradle finalRemediationStaticGate must execute canonical final release gate")
need("bash tools/run-final-release-gate.sh --ci" in root_ci,
     "repository Android CI must execute canonical final release gate")
need("bash tools/run-final-release-gate.sh --ci" in inner_ci,
     "Android-local CI must execute canonical final release gate")
need(root_ci.count("java-version: '21'") >= 2,
     "repository validate and signed-release jobs must both pin Java 21")
need("java-version: '17'" not in root_ci,
     "repository Android CI must not retain Java 17 drift")
need(root_ci.count("gradle-version: '9.7.1'") >= 2,
     "repository validate and signed-release jobs must both pin Gradle 9.7.1")
need("gradle-version: '9.7.0'" not in root_ci and "--gradle-version 9.7.0" not in root_ci,
     "repository Android CI must not retain Gradle 9.7.0 drift")
need("## XDM Android 0.21.0" in readme and "Room is schema v22" in readme,
     "README must expose current 0.21.0 / Room v22 release truth")
need("## Current final release gate" in readme,
     "README must identify the current release gate explicitly")
need("Phase 17 established the original public-release boundary at Room schema v14; that phase is now historical." in readme,
     "README must preserve Phase 17 schema v14 only as historical context")
need(entry.get("base_commit") == "b6928c32", "post-DL03 release follow-up base commit mismatch")
need(entry.get("roadmap_status") == "MC01-MC05 and DL01-DL03 sealed", "roadmap completion status missing")
need(entry.get("current_app_version") == "0.21.0", "current app version must be 0.21.0")
need(entry.get("room_schema_current") == 21 and entry.get("room_schema_changed") is False,
     "post-DL03 follow-up must retain Room schema 21")
need(entry.get("ci_java_version") == 21 and entry.get("ci_gradle_version") == "9.7.1",
     "manifest CI toolchain pins must be Java 21 / Gradle 9.7.1")
need(entry.get("signed_release_uses_canonical_gate") is True,
     "signed release must remain bound to canonical final gate")
need(entry.get("runtime_behavior_changed") is False,
     "release follow-up must not claim runtime behavior changes")
need(entry.get("validation_deferred") is False, "release follow-up validation cannot be deferred")
need(entry.get("next_overlay", "sentinel") is None, "handoff roadmap should be complete after release follow-up")
need(dl.get("next_overlay") == "post_dl03_release_followup_complete",
     "DL02/DL03 handoff pointer must record completion of the release follow-up")
need("ACTION_INTERNAL_BROWSER_DIRECT_CAPTURE_IMPORT" in phase1 and "XdmBrowserDeepLinkParser.parseDetailed(rawDeepLink" in phase1,
     "Phase 1 static validator must carry the reviewed internal browser-direct exception")
need('"XdmBrowserDeepLinkParser",' not in phase1,
     "Phase 1 static validator must not blanket-forbid the current reviewed browser-direct parser")
need("NativeRollingSpeedMeter" in phase2 and "verifyPersistedSegment(paths.partial, segment)" in phase2,
     "Phase 2 static validator must carry DL02 rolling-speed/incremental-integrity contracts")
need('("bytesAtAttemptStart", "resume speed correction")' not in phase2 and 'reject(native, "bytesAtAttemptStart"' in phase2,
     "Phase 2 static validator must reject the superseded attempt-local speed baseline")
need(text(ROOT / "XDM_POST_DL03_RELEASE_FOLLOWUP_REPORT.md").find("No additional handoff roadmap phase is pending") >= 0,
     "release follow-up report must record roadmap completion")
need("import org.junit.Test" in contract_test and "import org.junit.Assert.assertTrue" in contract_test and "import org.junit.Assert.assertFalse" in contract_test,
     "post-DL03 app contract must use the app module's JUnit 4 test API")
need("kotlin.test" not in contract_test,
     "post-DL03 app contract must not depend on unavailable kotlin.test APIs")

if errors:
    print("Post-DL03 release follow-up validator failed:")
    for error in errors:
        print(f"- {error}")
    sys.exit(1)
print("Post-DL03 release follow-up validator: OK")
