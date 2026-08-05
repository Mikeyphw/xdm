#!/usr/bin/env python3
"""Validate the extension HLS capture resolution hotfix.

This source gate is intentionally narrow: it fails the pre-hotfix tree where a
browser-extension HLS capture could be accepted, stored as a library shell, and
never resolved into variants/download actions.
"""
from __future__ import annotations

import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]


def read(rel: str) -> str:
    path = ROOT / rel
    if not path.exists():
        raise AssertionError(f"missing {rel}")
    return path.read_text(encoding="utf-8")


def require(text: str, needle: str, label: str) -> None:
    if needle not in text:
        raise AssertionError(f"missing {label}: {needle}")


def require_not(text: str, needle: str, label: str) -> None:
    if needle in text:
        raise AssertionError(f"forbidden {label}: {needle}")


def main() -> int:
    automation = read("core-model/src/main/kotlin/com/mikeyphw/xdm/android/model/AutomationModels.kt")
    deep_link = read("browser-integration/src/main/kotlin/com/mikeyphw/xdm/android/browser/XdmBrowserDeepLinkPayload.kt")
    engine = read("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaSniffingEngine.kt")
    library = read("media/src/main/kotlin/com/mikeyphw/xdm/android/media/MediaExecutionLibrary.kt")
    vm = read("app/src/main/kotlin/com/mikeyphw/xdm/android/MainViewModel.kt")
    repository = read("persistence/src/main/kotlin/com/mikeyphw/xdm/android/persistence/DownloadRepository.kt")
    test = read("media/src/test/kotlin/com/mikeyphw/xdm/android/media/ExtensionHlsCaptureResolutionHotfixTest.kt")

    require(automation, "val mediaKind: String? = null", "browser media kind on command draft")
    require(deep_link, "mediaKind.toMimeTypeHint()", "media kind MIME hint mapping")
    require(deep_link, '"hls", "m3u8"', "HLS media kind mapping")
    require(deep_link, '"dash", "mpd"', "DASH media kind mapping")

    require(engine, "private fun parseInlineManifestVariants", "inline manifest resolver")
    require(engine, "parseHlsPlaylist(captureId, candidate.sourceUrl, body)", "inline HLS parser use")
    require(engine, "parseDashManifest(captureId, candidate.sourceUrl, body)", "inline DASH parser use")
    require(engine, "manifest-resolved-inline", "inline manifest diagnostic")
    require(engine, "bodySignatureKind(normalized, input.bodyPrefix) ?: classifier.classify(facts)", "body signature classification before fallback")

    require(vm, "private suspend fun resolveCapturedPlaylistIfPossible", "capture resolver helper")
    require(vm, "mediaPageProbe.probePage(exactUrl, pageTitle = record.title, requestHeaders = requestHeaders)", "captured media URL probe")
    require(vm, "resolveCapturedPlaylistIfPossible(merged, record.sourceUrl, requestHeaders, now)", "extension command resolution")
    require(vm, "resolveCapturedPlaylistIfPossible(merged, session.exactRequestUrl, session.usableHeaders, now)", "network capture resolution")
    require(vm, "resolveCapturedPlaylistIfPossible(merged, intake.record.sourceUrl, draft.requestHeaders, inspectNow)", "manual external media resolution")
    require(vm, "repository.saveMediaCapturesWithVariants(merged, allVariants, now)", "extension command transactional capture+variant persistence")
    require(vm, "repository.saveMediaCaptureWithVariants(resolved, capturedVariants, now)", "network capture transactional capture+variant persistence")
    require(vm, "capturedVariants.forEach { variant ->", "network capture remembers resolved variants")
    require(vm, "repository.saveMediaCaptureWithVariants(refreshed.copy(sourceUrl = record.sourceUrl), variants, now)", "check-again transactional capture+variant persistence")
    require(vm, "resolved ${resolvedVariants.size} manifest variant(s)", "capture result reports resolved variants")
    require(vm, "record.copy(sourceUrl = probeUrl)", "check-again uses exact captured URL")

    require(repository, "import androidx.room.withTransaction", "Room transaction import")
    require(repository, "suspend fun saveMediaCaptureWithVariants", "single capture+variant transaction")
    require(repository, "suspend fun saveMediaCapturesWithVariants", "batch capture+variant transaction")
    require(repository, "database.withTransaction", "transactional capture+variant persistence")

    require(library, "val completedDownload = download.takeIf { it.state == DownloadState.Completed } ?: return@mapNotNull null", "library excludes in-progress placeholders")
    require(library, "val playback = completedPlaybackUrl(completedDownload) ?: return@mapNotNull null", "library requires playable committed artifact")
    require(library, "canPlayDirect = true", "completed adaptive output is playable")
    require(library, "download.destinationUri", "canonical completed URI playback")
    require_not(library, "downloadId = download?.id ?: capture.downloadId", "library placeholder fallback")
    require_not(library, "download.destinationUri.trimEnd('/')", "content URI filename append")

    require(test, "hlsBodyFromExtensionCaptureCreatesRealTrackVariants", "HLS inline variant test")
    require(test, "libraryDoesNotShowCapturedOnlyExtensionHlsPlaceholder", "library placeholder regression test")
    require(test, "Library must not show unavailable/finishing placeholders", "in-progress library placeholder assertion")
    require(test, "canPlayDirect", "completed adaptive output playback assertion")
    require(test, "it.kind == MediaVariantKind.Audio", "audio variant assertion")
    require(test, "it.kind == MediaVariantKind.Video", "video variant assertion")

    print("extension HLS capture hotfix validation passed")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except AssertionError as exc:
        print(f"extension HLS capture hotfix validation failed: {exc}", file=sys.stderr)
        raise SystemExit(1)
