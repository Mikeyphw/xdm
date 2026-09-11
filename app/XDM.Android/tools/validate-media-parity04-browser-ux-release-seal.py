#!/usr/bin/env python3
"""Final Media Parity04 browser UX/userscripts/notification release seal."""
from pathlib import Path
import json
import sys

ROOT = Path(__file__).resolve().parents[1]

def read(rel: str) -> str:
    path = ROOT / rel
    if not path.exists():
        raise AssertionError(f"missing {rel}")
    return path.read_text(encoding="utf-8")

def require(text: str, needle: str, label: str) -> None:
    if needle not in text:
        raise AssertionError(f"missing {label}: {needle}")

media_locator = read("app/src/main/kotlin/com/mikeyphw/xdm/android/MediaLocatorActivity.kt")
userscripts = read("app/src/main/kotlin/com/mikeyphw/xdm/android/WebViewUserscriptStore.kt")
add_download = read("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/intake/AddDownloadSurface.kt")
notifications = read("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TransferNotifications.kt")
terminal_policy = read("scheduler/src/main/kotlin/com/mikeyphw/xdm/android/scheduler/TerminalNotificationActionPolicy.kt")
model = read("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/MediaParity04UxModels.kt")
strings = read("app/src/main/res/values/strings.xml")
accessibility = read("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmAccessibility.kt")
manifest = json.loads(read("PROJECT_MANIFEST.json"))

# Lightweight WebView browser: address/browser tools remain, but media candidates are not pinned.
require(media_locator, "private lateinit var mediaFab: Button", "floating media button field")
require(media_locator, "FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)", "floating media layout")
require(media_locator, "showMediaBottomSheet()", "logical media picker entry point")
require(media_locator, "list.visibility = View.GONE", "fixed raw list removed from viewport")
require(media_locator, "It is opened from the floating Media button", "browser-first viewport rationale")
require(media_locator, "setItems(labels) { _, which -> candidates.getOrNull(which)?.let(::reviewCandidate) }", "selection before admission")
require(media_locator, "resources.getQuantityString(R.plurals.media_locator_media_fab", "media FAB count")

# No silent recoverable failures.
require(media_locator, "Toast.makeText(this, message, Toast.LENGTH_SHORT).show()", "toast feedback helper")
require(media_locator, "showFeedbackToast(getString(R.string.media_locator_invalid_url))", "invalid URL feedback")
require(media_locator, "showFeedbackToast(\"$title. $detail\")", "main-frame error feedback")
require(media_locator, "showFeedbackToast(getString(R.string.media_locator_userscripts_injection_failed))", "userscript failure feedback")

# Userscript support must be explicit and constrained, not a fake extension runtime.
for needle, label in [
    ("Tampermonkey-style", "tampermonkey copy"),
    ("@match", "match metadata"),
    ("@include", "include metadata"),
    ("@grant none", "grant-none constraint"),
    ("Browser-extension APIs are not available inside Android WebView", "privileged API rejection"),
    ("documentStartScriptFor", "document-start injection"),
    ("UserscriptPattern.matches", "URL pattern matching"),
]:
    require(userscripts + strings, needle, label)

# Add Download UX: one primary Download action, filename inference, and quality/track review.
require(add_download, "label = { Text(\"File name\") }", "filename field")
require(add_download, "Suggested by the server (Content-Disposition)", "filename source explanation")
require(add_download, "headline = \"Quality & tracks\"", "quality and tracks chooser")
require(add_download, "Use Download below only after this screen points to the exact file or selected media plan.", "download admission guidance")
require(add_download, "else -> \"Download\"", "single primary Download button")
require(add_download, "singleLine = false", "multiline long URL/name fields")
require(accessibility, "const val MediaOptionsChooser", "quality chooser semantics tag")

# Notification actions: completed notifications act on the artifact and retain details fallback.
require(terminal_policy, "NotificationActionModel(QueueControlCommand.OpenOne, \"Open file\"", "open file terminal action")
require(terminal_policy, "NotificationActionModel(QueueControlCommand.StartOne, \"Details\"", "details terminal action")
require(notifications, "if (state == DownloadState.Completed) openCompletedPendingIntent(downloadId)", "tap opens completed artifact")
require(notifications, "QueueControlCommand.OpenOne -> addAction(android.R.drawable.ic_menu_view, action.label, openCompletedPendingIntent(downloadId))", "open file button")
require(notifications, "QueueControlCommand.StartOne -> addAction(android.R.drawable.ic_menu_info_details, action.label, openAppPendingIntent(downloadId))", "details button")

# Release model and manifest truth.
for surface in [
    "LightweightBrowser",
    "FloatingMediaButton",
    "MediaBottomSheet",
    "FirefoxChooser",
    "AddDownloadReview",
    "Userscripts",
    "ToastFeedback",
    "NotificationActions",
    "LongTextWrapping",
    "DebugCenter",
]:
    require(model, surface, f"Parity04 acceptance surface {surface}")

key = "media_parity04_browser_ux_userscripts_notifications_release_seal"
if manifest.get("current_release_authority") != key:
    raise AssertionError("Parity04 must be the current release authority")
if manifest.get("current_experience_polish_authority") != key:
    raise AssertionError("Parity04 must be the current experience-polish authority")
if manifest.get("current_validation_truth_authority") != key:
    raise AssertionError("Parity04 must be the current validation-truth authority")
if manifest.get("database", {}).get("version") != 24:
    raise AssertionError("Parity04 must preserve Room schema 24")
section = manifest.get(key) or {}
expected = {
    "status": "implemented_final_seal",
    "overlay": "xdm_media_parity04_browser_ux_userscripts_notifications_release_seal_v1.zip",
    "room_schema_current": 24,
}
for k, v in expected.items():
    if section.get(k) != v:
        raise AssertionError(f"manifest {key}.{k} expected {v!r}, got {section.get(k)!r}")
if not section.get("parity01_02_03_carry_forward"):
    raise AssertionError("Parity04 must explicitly carry Parity01-03 forward")

print("media parity04 browser UX/userscripts/notification release seal passed")
