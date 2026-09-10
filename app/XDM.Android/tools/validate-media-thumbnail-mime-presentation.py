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

mime = read("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/MimePresentationModels.kt")
primitives = read("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmPrimitives.kt")
artwork = read("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/media/XdmMediaArtwork.kt")
capture = read("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/media/MediaCaptureCard.kt")
inbox = read("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/media/MediaInboxScreen.kt")
download_row = read("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadRow.kt")
downloads = read("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/downloads/DownloadsScreen.kt")
app = read("app/src/main/kotlin/com/mikeyphw/xdm/android/XdmApp.kt")
library = read("app/src/main/kotlin/com/mikeyphw/xdm/android/ui/library/MediaLibraryScreen.kt")
model_test = read("core-model/src/test/kotlin/com/mikeyphw/xdm/android/model/MimePresentationModelsTest.kt")
contract_test = read("app/src/test/kotlin/com/mikeyphw/xdm/android/MediaThumbnailMimePresentationContractTest.kt")
report = read("XDM_MEDIA_THUMBNAIL_MIME_PRESENTATION_THUMB01_THUMB03_MIME01_REPORT.md")
manifest = json.loads(read("PROJECT_MANIFEST.json") or "{}")
entry = manifest.get("media_thumbnail_mime_presentation_thumb01_thumb03_mime01", {})

for kind in (
    "AdaptiveMedia", "Video", "Audio", "Image", "Pdf", "Archive", "Document", "Spreadsheet",
    "Presentation", "Text", "Code", "Subtitle", "Playlist", "Package", "Torrent", "Font",
    "Calendar", "Contact", "Binary", "Generic", "Download",
):
    need(kind in mime, f"MIME presentation model missing {kind}")
need("application/vnd.apple.mpegurl" in mime and '"m3u8", "mpd" -> MimePresentationKind.AdaptiveMedia' in mime,
     "HLS/DASH must classify as adaptive media")
need("application/vnd.android.package-archive" in mime and '"apk", "apks", "xapk", "aab"' in mime,
     "package/APK MIME fallback missing")
need("application/pdf" in mime and "MimePresentationKind.Pdf" in mime, "PDF presentation missing")
need("fun contentDescription" in mime, "MIME resolver must provide accessibility presentation labels")

need("MimePresentationResolver.resolve" in primitives, "XdmFileTypeIcon must delegate to the MIME resolver")
for token in ("MimePresentationKind.AdaptiveMedia", "MimePresentationKind.Subtitle", "MimePresentationKind.Package", "MimePresentationKind.Spreadsheet"):
    need(token in primitives, f"XdmFileTypeIcon missing mapping for {token}")
need("containerSize" in primitives, "file type icon must support artwork-sized fallback containers")

need("fun XdmMediaArtwork(" in artwork, "shared XdmMediaArtwork component missing")
need("LruCache<String, Bitmap>(12 * 1024)" in artwork, "bounded artwork memory cache missing")
need("MAX_DISK_BYTES = 32L * 1024L * 1024L" in artwork and 'File(context.cacheDir, "media-artwork-v1")' in artwork,
     "bounded app-cache thumbnail store missing")
need("MAX_REMOTE_BYTES = 6 * 1024 * 1024" in artwork, "remote thumbnail response bound missing")
need("withContext(Dispatchers.IO)" in artwork, "artwork I/O must stay off the main thread")
need("presentation.kind == MimePresentationKind.Image" in artwork, "direct source URL preview must be image-only")
need("MediaMetadataRetriever.OPTION_CLOSEST_SYNC" in artwork, "completed local video frame extraction missing")
need("BitmapFactory.decodeFileDescriptor" in artwork, "completed local image decoding must avoid whole-file buffering")
need("sha256(" in artwork and '"$key.jpg"' in artwork, "cache must fingerprint request identity instead of exposing raw URLs in file names")
need("DebugSeverity.Trace" in artwork and 'result = "fallback-icon"' in artwork,
     "thumbnail failures must enter verbose diagnostics without surfacing runtime failure")
need("problemReporter" not in artwork, "thumbnail decode/fetch failure must not create actionable problem spam")

need("capture.thumbnailUrl ?: captureVariants.firstOrNull { it.kind == MediaVariantKind.Thumbnail }?.url" in capture,
     "captured-media card must fall back to persisted thumbnail variants")
need("XdmMediaArtwork(" in capture, "captured-media card must render shared artwork")
need("thumbnailUrl = capture.thumbnailUrl ?: variants.firstOrNull" in inbox and "XdmMediaArtwork(" in inbox,
     "recently queued media must preserve capture/variant artwork")
need("thumbnailUrl: String? = null" in download_row and "localUri = download.completedArtifactUri" in download_row,
     "download rows must accept media artwork and generate completed local artwork")
need("mediaCaptures: List<MediaCaptureRecord>" in downloads and "mediaVariants: List<MediaVariant>" in downloads,
     "Downloads surface must receive media artwork lineage")
need("MediaVariantKind.Thumbnail" in downloads and "mediaThumbnailByDownloadId" in downloads,
     "Downloads surface must map media captures/variants to download thumbnails")
need("mediaCaptures = state.mediaCaptures" in app and "mediaVariants = state.mediaVariants" in app,
     "app route must wire media artwork lineage into Downloads")
need(library.count("XdmMediaArtwork(") >= 2 and "thumbnailUrl = item.thumbnailUrl" in library and "localUri = item.playbackUrl" in library,
     "Media Library list/grid must use shared artwork with local completion fallback")

need("manifestExtensionsAreMediaNotGenericDocuments" in model_test and "commonDocumentFamiliesHaveDedicatedPresentation" in model_test,
     "MIME resolver regression tests missing")
need("sharedArtworkPipelineIsWiredAcrossMediaDownloadsAndLibrary" in contract_test,
     "cross-surface artwork contract test missing")
need("No Coil/Glide/Picasso" in report and "Full Gradle/lint validation is intentionally deferred" in report,
     "report must record dependency and validation scope truthfully")

need(entry.get("status") == "implemented_intermediate", "manifest must mark THUMB/MIME overlay implemented intermediate")
need(entry.get("captured_media_thumbnails") is True and entry.get("downloads_media_thumbnails") is True and entry.get("media_library_thumbnails") is True,
     "manifest must record cross-surface thumbnail delivery")
need(entry.get("disk_cache_limit_mib") == 32 and entry.get("external_image_library_added") is False,
     "manifest must record cache/dependency constraints")
need(entry.get("mime_first_icons") is True and entry.get("adaptive_stream_media_icon") is True,
     "manifest must record MIME/adaptive presentation contract")
need(entry.get("room_schema_unchanged") == 21 and entry.get("validation_deferred") is True,
     "intermediate overlay must preserve schema and deferred validation")
need(entry.get("next_overlay") == "xdm_android_live_locator_title_naming_v1.zip",
     "manifest must point to the Live Locator/title naming overlay")

if errors:
    print("THUMB01-THUMB03/MIME01 media artwork validator failed:")
    for error in errors:
        print(f"- {error}")
    sys.exit(1)
print("THUMB01-THUMB03/MIME01 media artwork validator: OK")
