#!/usr/bin/env python3
from pathlib import Path
import json, sys
ROOT=Path(__file__).resolve().parents[1]
errors=[]

def read(rel):
    p=ROOT/rel
    if not p.is_file(): errors.append(f"missing {rel}"); return ""
    return p.read_text()

def require(rel,*needles):
    text=read(rel)
    for n in needles:
        if n not in text: errors.append(f"{rel} missing {n!r}")
    return text

workspace=require("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadsWorkspace.kt",
    'All("All")','Downloading("Downloading")','Waiting("Waiting")','Finished("Finished")',
    "val downloadingCount: Int","val waitingCount: Int","val queuedCount: Int",
    "fun queueIssue(summary: QueueIntelligenceSummary)","fun rowStatus(download: Download, truth: DownloadUiTruth)",
    "private val waitingStates = queuedStates + DownloadState.Paused","Storage check failed","Waiting — $policy")
for stale in ('Active("Active")','Paused("Paused")'):
    if stale in workspace: errors.append(f"workspace retains obsolete primary filter {stale}")
screen=require("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadsScreen.kt",
    "queueIssue = DownloadsWorkspacePlanner.queueIssue(queueIntelligence)",
    'XdmMetric("downloading"','XdmMetric("waiting"','XdmMetric("queued"',
    "issue.iconVector()","Resume paused")
if "text = queueIntelligence.message" in screen: errors.append("Downloads still exposes raw queueIntelligence.message")
row=require("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadRow.kt",
    "val rowStatus = DownloadsWorkspacePlanner.rowStatus(download, truth)",
    "val destination = destinationUiLabel(download.destinationUri)",
    "DownloadActionIcon.Resume -> Icons.Rounded.Download", "XdmProgressLine(")
planner=require("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DownloadActionPlanner.kt",
    "Resume,", "DownloadActionIcon.Resume")
require("app/src/test/kotlin/com/mikeyphw/xdm/android/Ux03DownloadsQueueProductUiContractTest.kt",
    "downloadsUsesFourUserFacingFiltersAndSeparateWaitingMetrics",
    "queueErrorsAreGroupedAndRowsStayConcise",
    "resumeAndStartDownloadActionsDoNotUseTheMediaPlayGlyph")
manifest=json.loads(read("PROJECT_MANIFEST.json") or "{}")
phase=manifest.get("ux02_ux03_navigation_downloads_product_ui",{})
for k in ("four_primary_download_filters","waiting_metrics_truthful","queue_issue_grouping","concise_download_rows","resume_download_icon"):
    if phase.get(k) is not True: errors.append(f"manifest ux02_ux03 missing {k}=true")
if errors:
    print("UX03 Downloads/queue product UI validation failed:")
    for e in errors: print("-",e)
    sys.exit(1)
print("UX03 Downloads/queue product UI validation passed")
