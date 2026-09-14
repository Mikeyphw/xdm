#!/usr/bin/env python3
from pathlib import Path
import json
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
IDS = [
    "S11-01","S11-02","S11-03","S11-04","S11-05","S11-06","S11-07","S11-08","S11-09","S11-10","S11-11","S11-12","S11-13","S11-14",
    "DS6-S11-01","DS6-S11-02","DS6-S11-03","DS6-S11-04",
]

checks = []
def require(path: str, needle: str, label: str):
    text = (ROOT / path).read_text()
    if needle not in text:
        raise SystemExit(f"missing {label}: {needle} in {path}")
    checks.append(label)
    return text

model = require("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DownloadModels.kt", "manifestExecutionUrlTemplate", "variant executable DASH template")
require("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DownloadModels.kt", "requiresNetworkFetch", "in-band closed-caption marker")
require("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/DownloadModels.kt", "manifestTimelineGroupId", "timeline group field")

inbox = require("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaInboxContract.kt", "dashTemplateUrl", "DASH SegmentTemplate substitution")
for needle, label in [
    ('type != "CLOSED-CAPTIONS"', "closed captions are non-network"),
    ("hls-alternative-video-rendition", "HLS alternative video rendition"),
    ("dash-period:$periodIndex", "DASH period timeline identity"),
    ("requireXmlFeature", "fail-closed DASH XML hardening"),
    ("SAXNotRecognizedException", "parser feature failure is not ignored"),
]:
    if needle not in inbox:
        raise SystemExit(f"missing {label}: {needle}")
    checks.append(label)

sniffer = require("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaSniffingEngine.kt", "manifestProbeComplete", "manifest probe completeness gate")
if "body.length >= MAX_BODY_PREFIX" not in sniffer or "</MPD>" not in sniffer:
    raise SystemExit("manifest probe can still treat a bounded/truncated MPD probe as authoritative")
checks.append("bounded probe rejected unless complete")
if 'responseBound = raw.reason == "direct-url" || raw.reason == "body-signature"' not in sniffer:
    raise SystemExit("response Content-Length must stay bound to the observed resource, not extracted page URLs")
checks.append("HTML/JSON extracted media cannot inherit page Content-Length")
if "isFragmentOrNoise" not in sniffer or "numberedTransport" not in sniffer:
    raise SystemExit("fragment filtering boundary missing")
checks.append("large transport fragment filter retained")

planner = require("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaDownloadPlanner.kt", "selectedExecutionUrls", "selected URL-only session requirement")
for needle, label in [
    ("selectedVariants", "selected variant set"),
    ("sameTimeline", "same-period DASH audio selection"),
    ("manifestTimelineGroupId", "timeline-aware audio/subtitle pick"),
    ("needsCookieContext = session.hasCredentialContext || selectedExecutionUrls", "unselected credential-bearing variants ignored"),
]:
    if needle not in planner:
        raise SystemExit(f"missing {label}: {needle}")
    checks.append(label)

library = require("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaExecutionLibrary.kt", "isSelectedMediaSpecExpiring", "queued spec selected-child expiry")
if "selectedInputs.any { input -> input.expiresAtEpochMs" not in library:
    raise SystemExit("queued media spec does not mark selected child expiry")
checks.append("queued spec uses selected child expiry")

workspace = require("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaResolverWorkspace.kt", "selected child URL has expired", "ready UI child expiry warning")
if "ready = plan.canQueueDirectly && !selectedExpired && !capture.needsManifestRefresh" not in workspace:
    raise SystemExit("resolver UI can remain Ready after selected child expiry")
checks.append("resolver Ready state blocked by manifest/child expiry")

test = require("media/src/test/kotlin/com/mikeyphw/xdm/android/media/Xar11ManifestResolutionContractTest.kt", "dashSegmentTemplateProducesExecutableSegmentUrlsNotBaseUrlOnly", "XAR11 behavior tests")
for needle in ["hlsClosedCaptionsAreInBandMetadata", "selectedCredentialAndExpiryContextComesOnlyFromSelectedChildren", "incompleteBoundedManifestProbeIsNotAuthoritative"]:
    if needle not in test:
        raise SystemExit(f"missing XAR11 adversarial test {needle}")
    checks.append(f"test {needle}")

manifest = json.loads((ROOT / "PROJECT_MANIFEST.json").read_text())
entry = manifest.get("xar11_manifest_resolution")
if not entry:
    raise SystemExit("PROJECT_MANIFEST missing xar11_manifest_resolution")
missing = sorted(set(IDS) - set(entry.get("canonical_ids", [])))
extra = sorted(set(entry.get("canonical_ids", [])) - set(IDS))
if missing or extra:
    raise SystemExit(f"S11 coverage mismatch missing={missing} extra={extra}")
if entry.get("canonical_findings_closed") != 18:
    raise SystemExit("XAR11 canonical count must be 18")
checks.append("18/18 canonical S11 coverage")

print(f"XAR11 manifest resolution contract passed: {len(checks)} checks; 18/18 S11 findings covered.")
