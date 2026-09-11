#!/usr/bin/env python3
from pathlib import Path
import json
import sys

ROOT = Path(__file__).resolve().parents[1]
errors = []

def read(rel: str) -> str:
    try:
        return (ROOT / rel).read_text(encoding="utf-8")
    except Exception as exc:
        errors.append(f"cannot read {rel}: {exc}")
        return ""

def need(condition: bool, message: str) -> None:
    if not condition:
        errors.append(message)

add = read("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/intake/AddDownloadSurface.kt")
shell = read("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt")
inbox = read("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/media/MediaInboxScreen.kt")
card = read("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/media/MediaCaptureCard.kt")
workspace = read("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaConsumerWorkspace.kt")
post = read("app/src/test/kotlin/com/mikeyphw/xdm/android/PostDl03ReleaseFollowupContractTest.kt")
contract = read("app/src/test/kotlin/com/mikeyphw/xdm/android/AddMediaUxRemodelContractTest.kt")
architecture_contract = read("app/src/test/kotlin/com/mikeyphw/xdm/android/ArchitectureContractTest.kt")
browser_extension_contract = read("app/src/test/kotlin/com/mikeyphw/xdm/android/BrowserExtensionPhase43BContractTest.kt")
downloader_experience_contract = read("app/src/test/kotlin/com/mikeyphw/xdm/android/DownloaderExperiencePhase8ABContractTest.kt")
runtime_foundation_contract = read("app/src/test/kotlin/com/mikeyphw/xdm/android/RuntimeFoundationPhase57_58ContractTest.kt")
uix_r4_contract = read("app/src/test/kotlin/com/mikeyphw/xdm/android/UixR4MediaLibraryContractTest.kt")
gate = read("tools/run-final-release-gate.sh")
readme = read("README.md")
report = read("XDM_ADD_MEDIA_UX_REMODEL_REPORT.md")
manifest = json.loads(read("PROJECT_MANIFEST.json") or "{}")
entry = manifest.get("add_media_ux_remodel", {})

need("XdmAdaptiveSheet(" in shell and 'title = "New download"' in shell,
     "Add must remain the compact adaptive New download sheet")
need("Add a download with a single explicit action" in add, "Add surface must declare its single-action contract")
need('else -> "Download"' in add and "onAdd(" in add, "valid direct downloads must have one explicit Download action")
need("preferMediaInspection" in add and '"Inspect media"' in add,
     "page/adaptive inputs must keep inspection distinct from direct Download")
need("Advanced options" in add and "AnimatedVisibility(advancedExpanded)" in add,
     "expert Add controls must stay collapsed behind Advanced options")
need("Browser context attached" in add and "XdmScreenTags.BrowserSessionHealth" in add and "XdmScreenTags.EngineEscalation" in add,
     "browser/engine diagnostics must remain available under Advanced rather than being deleted")
need("destinationUiLabel(destinationUri)" in add, "Add must show a human-readable destination label")
for stale in ("reviewConfirmed", "Review download", "Add to queue", "Step 1 of 2", "Step 2 of 2", "XdmScreenTags.AddReview"):
    need(stale not in add, f"active Add surface must not restore obsolete second-confirmation token: {stale}")

need("Direct media downloads in one tap" in inbox, "Media header must state direct-media one-tap behavior")
need("AnimatedVisibility(mediaToolsExpanded)" in inbox and "More tools" in inbox,
     "batch/advanced media intake must be collapsed by default")
need(inbox.count('Text("Live locator")') == 1, "Media workspace should expose one clear Live locator action, not duplicate it")
need("val hasTrackChoices" in card and "if (hasTrackChoices && videoVariants.isNotEmpty())" in card and "if (hasTrackChoices)" in card and
     "showTrackControls = hasTrackChoices" in card and "if (showTrackControls)" in card,
     "quality/track controls must be conditional on meaningful adaptive choices in both card and Options sheet")
need("MediaConsumerState.Ready -> Button(" in card and 'MediaConsumerState.Downloaded -> "Open"' in workspace,
     "ready direct media must expose Download and downloaded media must expose Open")
need('Text("Edit")' in card or 'Text("Details")' in card,
     "secondary media controls must retain a compact editable Details/Edit affordance")
need('MediaConsumerState.Unavailable -> "Unavailable"' in card, "media unavailable state must remain truthful")

need('System.getProperty("user.dir") ?: "."' in post,
     "post-DL03 contract must avoid nullable user.dir Java-platform warning")
need("requireNotNull(root.parentFile?.parentFile)" in post,
     "post-DL03 contract must avoid nullable parentFile warning")
need("tools/validate-add-media-ux-remodel.py" in gate, "canonical final gate must execute the Add/Media UX remodel validator")
gate_authority_ok = (
    "post-UX13 roadmap-completion hotfix is the current UI release authority" in gate
    or (
        "post-UX13 roadmap-completion hotfix remains the historical UI release baseline" in gate
        and "ACT01/ACT02 list quick-actions final seal is the current experience-polish validation authority" in gate
    )
)
need(gate_authority_ok and "UX13 remains the end-to-end UI/UX baseline" in gate,
     "canonical gate must retain post-UX13 and UX13 provenance while allowing a later validated experience-polish authority")
need("Add/Media UX remodel remains a retained functional milestone" in gate,
     "canonical gate must retain the Add/Media UX remodel as a functional milestone")
need("execution/media semantics repair remains its functional baseline" in gate,
     "canonical gate must retain execution/media repair as the functional baseline")
post_authority_ok = (
    "post-UX13 roadmap-completion hotfix is the current UI release authority" in post
    or (
        "post-UX13 roadmap-completion hotfix remains the historical UI release baseline" in post
        and "ACT01/ACT02 list quick-actions final seal is the current experience-polish validation authority" in post
    )
)
need(post_authority_ok and "UX13 remains the end-to-end UI/UX baseline" in post,
     "post-DL03 carry-forward contract must retain post-UX13 and UX13 provenance under the current experience-polish authority")
need("Add/Media UX remodel remains a retained functional milestone" in post,
     "post-DL03 carry-forward contract must retain Add/Media milestone provenance")
need("one explicit **Download** action" in readme and "Current Add and Media UX" in readme,
     "README must expose the current one-action Add/Media user model")
need("The old `Review download -> Add to queue` second confirmation is removed." in report,
     "UX remodel report must record removal of the redundant confirmation")

need(manifest.get("current_release_authority") in {"post_ux13_roadmap_completion_hotfix", "media_parity01_runtime_truth_diagnostics_backend_reliability", "media_parity02_logical_media_capture_browser_convergence", "media_parity03_native_hls_execution_admission_integrity"},
     "manifest must identify the post-UX13 hotfix as current release authority")
need(entry.get("base_commit") == "c214240e", "UX remodel base commit must be c214240e")
need(entry.get("functional_baseline") == "execution_media_semantics_repair", "UX remodel must retain execution/media functional baseline")
need(entry.get("single_explicit_add_action") is True and entry.get("second_confirmation_removed") is True,
     "manifest must seal the single-action Add flow")
need(entry.get("external_handoff_auto_queue") is False, "external handoffs must never auto-queue")
need(entry.get("advanced_diagnostics_collapsed") is True and entry.get("browser_session_diagnostics_collapsed") is True,
     "manifest must seal collapsed diagnostics")
need(entry.get("direct_media_primary_action") == "Download" and entry.get("direct_media_fake_quality_choices") is False,
     "manifest must seal simple direct-media presentation")
need(entry.get("adaptive_quality_tracks_only_when_present") is True and entry.get("media_tools_collapsed_by_default") is True,
     "manifest must seal adaptive-only choices and collapsed media tools")
need(entry.get("post_dl03_nullable_user_dir_warning_fixed") is True and entry.get("post_dl03_nullable_parent_warning_fixed") is True,
     "manifest must record both warning fixes")
need(entry.get("room_schema_current") == 21 and entry.get("room_schema_changed") is False,
     "UX remodel must retain Room schema 21")
need(entry.get("validation_deferred") is False and entry.get("next_overlay") is None,
     "UX remodel validation must not be deferred")
need("addSheetUsesOneExplicitDownloadActionWithoutSecondConfirmation" in contract and
     "mediaWorkspaceKeepsDirectMediaSimpleAndAdaptiveChoicesConditional" in contract and
     "warningCleanupAndReleaseAuthorityCarryForward" in contract,
     "app contract must cover Add, Media, warning cleanup, and release authority")
need("kotlin.test" not in contract, "UX remodel contract must use the app module's JUnit 4 test API")
need("val addSurface = File(root, \"app/src/main/kotlin/com/mikeyphw/xdm/android/ui/intake/AddDownloadSurface.kt\").readText()" in architecture_contract,
     "Architecture Add contracts must scope negative checks to the real Add surface rather than legacy Screens.kt")
need("Suggested by the server (Content-Disposition)" in architecture_contract and
     "Optional • XDM will infer a name when left empty." in architecture_contract and
     "val canDownload = review.canStartDirectly" in architecture_contract,
     "Architecture filename/add-state contracts must carry server-aware one-action Add inference forward")
need(r'title = \"Video quality\"' in architecture_contract and "hasTrackChoices" in architecture_contract,
     "Architecture media contract must assert adaptive-only quality/track choices")
need("text = review.mediaInspectionGuidance" in browser_extension_contract and
     r'preferMediaInspection -> \"Inspect media\"' in browser_extension_contract,
     "browser-extension Add contract must use the remodeled inspection guidance/action")
need("onInspectMedia(url, effectiveFileName)" in downloader_experience_contract,
     "downloader-experience contract must match the one-action Add inspection callback")
need("Needs action" in runtime_foundation_contract and "Browser capture recommended" not in runtime_foundation_contract,
     "runtime-foundation contract must use the normalized Needs action feedback vocabulary")
need(all(token in uix_r4_contract for token in ("Page or media URL", "Video quality", "Estimated size", "More tools")),
     "UIX-R4 media contract must carry the simplified direct-media/adaptive-media copy")

if errors:
    print("Add/Media UX remodel validator failed:")
    for error in errors:
        print(f"- {error}")
    sys.exit(1)
print("Add/Media UX remodel validator: OK")
