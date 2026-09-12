# v6 authoritative unit-test compile closure

The authoritative Termux v5 run rolled back at `:media:compileDebugUnitTestKotlin` because `AdaptiveMediaExecutionMc03Test` used nonexistent `MediaNativeCapability.NativeHls`. The production capability enum is `Unknown | NativeCandidate | FallbackRequired | ProtectedUnsupported`; `NativeHls` is a `MediaDownloadStrategy`, not a capability. v6 changes the subtitle-only regression fixture to `MediaNativeCapability.NativeCandidate`, preserving the intended assertion that a native-capable HLS capture with subtitle-only intent must route to yt-dlp rather than native HLS media execution. FF02 and the post-seal gate now reject the invalid capability reference explicitly.

# XDM Android FFmpeg roadmap post-seal hotfix audit

## Audit basis

This roadmap audit verifies production ownership and execution, not source presence alone.

This audit compares the post-FF04-v7r4 repository to the original FF01–FF04 handoff roadmap. A source class, report, or green static check is not treated as delivery unless a production path actually reaches it.

## Roadmap audit result

| Roadmap area | Pre-hotfix status | Audit evidence / correction |
|---|---|---|
| FF01 embedded FFmpeg/FFprobe runtime, NDK 29, 16 KB, typed shell-free execution, Android CA trust | Delivered | Runtime packaging, attestation, typed operations, HTTPS trust and app-owned manager are production wired. |
| FF02 selected-track adaptive mux / verification / atomic publication | Delivered | `EmbeddedFfmpegMediaManager` owns resolved adaptive processing and verifies with FFprobe before `DestinationWriter` promotion. |
| FF02 supported native-HLS durable execution | **Gap** | `NativeHlsExecutionEngine` and `NativeHlsFfmpegFinalizer` existed, but the finalizer had no production caller. `NativeHlsMediaManager` now owns playlist execution, durable parts, retries/ranges/AES-128, recovery, finalization, FFprobe verification and publication. |
| FF02 ordinary local remux / fast-start / audio extraction / FFprobe | **Gap** | These action kinds still declared/used Termux. Completed-file actions now execute embedded-first through `FfmpegPostProcessor` / `EmbeddedFfmpegRuntime`. |
| FF03 Automatic / Embedded / Termux preference and safe fallback | Delivered, preserved | The explicit Termux fallback remains Termux-owned and requires fresh verified FFmpeg + FFprobe. Sensitive sessions still cannot silently cross the boundary. |
| FF03 yt-dlp external resolver boundary | Delivered, preserved | yt-dlp remains external and is not made the owner of normal app-resolved mux/post-processing. |
| FF04 runtime provenance, APK bytes/licenses, FFprobe, NDK 29, 16 KB, no-Termux acceptance | Delivered | v7r4 validated this successfully on the target device. |
| FF04 promise-closure validation | **Gap in evidence** | Old FF02/FF04 validators allowed `NativeHlsFfmpegFinalizer` to exist without a production caller. They now require production native-HLS execution wiring and embedded-first completed-file post-processing. |

## Native HLS production path after the hotfix

`MainViewModel` dispatches `MediaExecutionLane.NativeHlsSegmented` to `NativeHlsMediaManager` rather than the ordinary download backend. The manager:

1. recovers exact URL/header material only from the encrypted `MediaRequestHandoffStore`;
2. re-runs `AndroidTransferRequestSecurityGuard` for manifest, part, map and key requests;
3. negotiates the existing `NativeHlsExecutionEngine` plan;
4. admits an idempotent generation and persists only redacted/persistable URLs in Room;
5. downloads ordered parts with bounded retry, HTTP byte ranges and AES-128 CBC support where the existing capability model allows it;
6. checkpoints complete parts and aggregate progress in the existing native-HLS tables;
7. preserves completed parts across Pause / process recovery and deletes owned temporary data on Cancel;
8. hands only complete local parts to `NativeHlsFfmpegFinalizer`;
9. requires embedded FFmpeg success and embedded FFprobe semantic verification;
10. publishes through `AndroidDestinationWriter` and marks the Download/media output Completed only after publication metadata is durable.

Separate audio renditions and unsupported/protected/live HLS remain outside this simple native-media-playlist executor and continue through the existing adaptive/fallback policy.

## Embedded-first completed-file post-processing

`FfprobeInspect`, `RemuxFastStart`, `ExtractAudio`, and `FfmpegRemux` no longer inherently require Termux. `TermuxMediaPipelineManager` retains the existing durable post-processing database/publication machinery but dispatches these normal local operations into `EmbeddedFfmpegRuntime` / `FfmpegPostProcessor`.

The FF03 external fallback is not removed or disguised: `PostProcessingJobSpec.externalFfmpegFallback` records explicit external ownership. `enqueueFfmpegFallback`, the explicitly named Termux FFprobe/conversion commands, and yt-dlp still use the Termux readiness/tool boundary.

## Validation closure

The hotfix adds or strengthens:

- `FfmpegRoadmapPostSealHotfixContractTest`;
- `tools/validate-ffmpeg-roadmap-postseal-hotfix.py`;
- `tools/validate-ffmpeg02-media-mux-hls-postprocessing.py` production-caller and embedded-ownership assertions;
- the retained Phase-7 post-processing contract for embedded-default vs explicit external fallback;
- `:app:verifyFfmpegRoadmapPostSealHotfix`;
- `.devtool.toml` FFmpeg preflight;
- `tools/run-final-release-gate.sh`;
- `:app:verifyFfmpeg04FinalReleaseValidation`.

The hotfix does not change the pinned FFmpeg/OpenSSL runtime payload, build cache, NDK 29 profile, APK byte attestation or licensing configuration.

## Second-pass roadmap re-audit — v2

A second literal pass over every FF01–FF04 handoff bullet found additional edge cases that the first post-seal hotfix did not fully close. v2 is cumulative over v1 and supersedes it.

### Additional gaps closed

1. **Audio-only HLS verification:** the native-HLS finalizer no longer hard-codes a video stream. Plans carry video/audio expectations and FFprobe always requires at least one real media stream.
2. **Generation-safe part identity:** native-HLS part primary keys are job/generation scoped. A user-requested Add again generation cannot collide with an older generation's rows.
3. **Refresh-safe resume:** a completed part is reused only when media sequence, sanitized URL, byte range, init-map identity, key identity/IV and discontinuity identity still match the refreshed playlist.
4. **Capability truth:** true master playlists, separate rendition graphs, subtitle-only requests and AES-128-encrypted init maps are rejected from the simple one-rendition native executor and remain on the adaptive/fallback boundary.
5. **Final media container truth:** native HLS never publishes a `.m3u8`/playlist MIME as the completed artifact. Video/mixed output uses `.mkv`; AAC audio-only uses `.m4a`; other audio-only codecs conservatively use `.mka`. Long/path-like capture names reserve the final extension before the filename bound is applied.
6. **Cancellation correctness:** part retries rethrow `CancellationException`; OkHttp calls are cancellable; Pause/Cancel cancel-and-join the owned worker before durable state is settled.
7. **Single-owner launch:** the concurrent ownership map treats every non-completed LAZY job as reserved, so concurrent launch calls cannot create two native-HLS executors for one Download.
8. **Filesystem isolation:** the temp directory is derived from a hash of the full durable temp key rather than only `g1`/`g2`, preventing unrelated captures from sharing part files.
9. **Publication crash recovery:** native-HLS recovery now runs after XDM's canonical startup publication-journal reconciliation. A `DestinationCommitted` journal is adopted into the HLS job, Download and media-output rows instead of remuxing/re-publishing a duplicate. The verified staged SHA-256/byte count is persisted before promotion and canonical finalization-journal evidence is completed after Room metadata reconciliation.
10. **Atomic FF04 device proof:** the instrumentation acceptance test now muxes into `AndroidDestinationWriter` staging, probes staged media, atomically promotes to app-private storage, then probes the committed file. The FF04 validator requires that exact chain.
11. **Post-completion cleanup safety:** failure to remove best-effort staging/journal leftovers after durable completion cannot flip the completed Download back to failure/recovery.

## Literal handoff roadmap closure matrix

### FF01 — embedded runtime + media execution

| Promise | v2 audit status | Evidence |
|---|---|---|
| `media-ffmpeg` Android module | Delivered | module and Gradle wiring retained |
| pinned FFmpeg/FFprobe runtime | Delivered | runtime manifest + lock verification |
| FFmpeg 9.0.1 + OpenSSL 3.5.8 provenance | Delivered | pinned source hashes/provenance |
| NDK 29 arm64 build tooling | Delivered | pinned `29.0.14206865`, arm64 runtime verification |
| 16-KB native compatibility | Delivered | builder/linker profile + ELF verifier |
| typed shell-free operations | Delivered | `FfmpegOperation` argv execution |
| FFprobe parsing | Delivered | embedded probe model/parser |
| Android CA-store HTTPS trust | Delivered | embedded HTTPS trust/runtime profile |
| durable embedded execution ownership | Delivered | `EmbeddedFfmpegMediaManager` |
| FFmpegLive not mandatory-Termux | Delivered | runtime routing policy/embedded lane |
| planner/dispatcher/worker integration | Delivered | FF01/FF02/FF03 gates |
| Developer Center/tests/docs/Devtool | Delivered | retained FF01 contracts and final gate |

### FF02 — mux + HLS + post-processing

| Promise | v2 audit status | Evidence |
|---|---|---|
| selected video/audio/subtitle mux | Delivered | embedded adaptive selected-track operations |
| stream-copy-first | Delivered | typed mux/remux operations retain `-c copy` preference |
| adaptive execution | Delivered | `EmbeddedFfmpegMediaManager` and media execution library |
| HLS/native-HLS finalization boundary | **Closed by post-seal v2** | production `NativeHlsMediaManager` → `NativeHlsFfmpegFinalizer` |
| remux/audio/subtitle/faststart primitives | Delivered | embedded `FfmpegPostProcessor`; explicit external fallback remains separate |
| machine-readable FFmpeg progress | Delivered | progress pipe/parser and durable UI progress |
| cancellation | **Hardened by v2** | cancellable network calls, rethrown cancellation, cancel-and-join |
| staged + atomic publication | **Hardened by v2** | FFmpeg staging + `DestinationWriter` publication + crash reconciliation |
| FFprobe post-output verification | **Hardened by v2** | stream-shape expectations + `requireAnyStream` |
| lifecycle/recovery | **Hardened by v2** | job-scoped parts, playlist identity checks, publication-journal adoption |
| UI progress/cancel | Delivered | Download rows + ViewModel native-HLS controls |
| tests/gates | **Hardened by v2** | post-seal contract + strengthened FF02/FF04 + retained gates |

### FF03 — runtime routing + safe fallback

| Promise | v2 audit status | Evidence |
|---|---|---|
| persisted Automatic / Embedded / Termux | Delivered | FF03 preference model |
| Automatic embedded-first | Delivered | routing policy |
| verified Termux FFmpeg+FFprobe fallback only | Delivered/preserved | `externalFfmpegFallback` + readiness gate |
| no silent sensitive-session externalization | Delivered/preserved | routing and session sensitivity checks |
| signed/tokenized/header/private-network in-app | Delivered/preserved | Android handoff/security guard/native-HLS fetch path |
| yt-dlp boundary | Delivered/preserved | external resolver/fallback lane |
| external FFmpeg lane | Delivered/preserved | explicitly named Termux operations and fallback |
| timeline progress | Delivered | retained FF03 progress model |
| live-safe timeout | Delivered | retained FF03 live timeout policy |
| diagnostics/Developer Center truth | Delivered | FF03/FF04 gates |
| runtime controls | Delivered | persisted preference UI |
| diagnostic redaction | Delivered | retained support/redaction gates |

### FF04 — release seal

| Promise | v2 audit status | Evidence |
|---|---|---|
| audit every FF01–FF03 promise | **Re-audited by v2** | this matrix + `verifyFfmpegRoadmapPostSealHotfix` |
| real pinned Termux/NDK29 build | Delivered on target | successful v7r4 target validation/runtime build |
| reproducible/provenance lock | Delivered | runtime lock + source/profile hashes |
| runtime SHA-256 attestation | Delivered | installed runtime capability gate |
| exact APK FFmpeg/FFprobe bytes | Delivered | `verifyFfmpegDebugApkRuntime` |
| licenses | Delivered | license assets/hashes/verifier |
| arm64-v8a identity | Delivered | ELF/runtime verifier |
| 16-KB ELF compatibility | Delivered | ELF alignment gate |
| profile/capability checks | Delivered | manifest/runtime configure checks |
| APK/native size budgets | Delivered | verifier budgets |
| deterministic video-only + audio-only fixtures | Delivered | fixture manifest + SHA-256 |
| instrumented separate video+audio embedded mux | Delivered | FF04 Android test |
| atomic instrumented output + committed-file FFprobe | **Closed by v2** | AndroidDestinationWriter staging/promotion + committed probe |
| explicit no-Termux acceptance | Delivered | routing acceptance + device test uses embedded runtime only |
| Debug Center evidence | Delivered | FF04 Developer/Debug Center gate |
| repaired legacy/static gates | Delivered | retained Phase 7/10/11 and final static gate |
| full explicit Devtool validation | Required for this v2 artifact | final Termux command uses the staged FF04 lifecycle gate; no `--no-validate` |

## Final v2 audit conclusion

No remaining source-level FF01–FF04 promise gap was found after the second adversarial pass. The pinned native runtime itself is unchanged from the successfully validated v7r4 payload. v2 changes production ownership/recovery, acceptance proof, and validation contracts around that runtime. The authoritative compile/unit/lint/APK run remains the final Devtool apply on the user's Termux/NDK 29 environment.

## Third-pass roadmap re-audit — v3

The third independent pass tried to falsify the cumulative v2 closure rather than reusing its conclusions. It traced FF02 lifecycle boundaries through actual destination publication and refreshed-playlist recovery, and it traced audio-only stream-copy containers through both native-HLS and embedded adaptive/live ownership.

### Additional gaps closed

1. **Embedded adaptive/live publication crash recovery:** `EmbeddedFfmpegMediaManager` now decodes the canonical destination publication journal during startup recovery. A proven already-committed destination is adopted into the media-output row instead of re-running FFmpeg and publishing a duplicate.
2. **Commit-in-progress provider recovery:** `AndroidDestinationWriter.publicationCommitMatches()` can reconcile `DestinationCommitInProgress` only after re-querying the exact expected byte length. For `content://` providers it additionally requires the preserved staged file and proves SHA-256 equality with the provider item; filesystem targets rely on their atomic-move boundary. An uncertain or partially copied provider item remains recovery-required instead of being guessed successful.
3. **Cancellation after commit:** destination promotion is the point of no return. Completion metadata reconciliation runs in `NonCancellable`; a cancellation/error racing after promotion preserves the recovery journal and cannot downgrade a committed artifact to Cancelled or delete the evidence needed for startup adoption.
4. **Embedded audio container compatibility:** audio-only embedded adaptive/live jobs use `.m4a` only for AAC/MP4A. Opus, Vorbis, FLAC, AC-3/E-AC-3, MP3 and unknown codecs conservatively use `.mka`, preventing stream-copy failures caused solely by an incompatible guessed MP4-family container.
5. **Execution-owner filename defense:** `NativeHlsMediaManager` independently normalizes a direct/malformed caller's final name to `.mkv`, `.m4a`, or `.mka` while reserving the extension inside the filename limit. The normal planner already enforced this; the owner now enforces it too.
6. **Init-map byte-range recovery identity:** native-HLS durable part identity now includes the secret-safe init-map URL **and its BYTERANGE offset/length**. A refreshed playlist cannot reuse a completed fMP4 part assembled with a stale initialization slice merely because the map URI stayed the same. This uses the existing `initMapUrl` identity column and does not require a Room schema bump.
7. **Publication-journal codec proof:** the journal now has focused round-trip/read regression coverage, including values containing `=`, so recovery evidence is proven readable rather than write-only.
8. **HTTPS trust re-check:** the apparent absence of CA wiring at the manager call site was re-traced and is **not a gap**. `EmbeddedFfmpegRuntime.withAndroidTrust()` injects the Android CA bundle into remote FFmpeg/FFprobe operations before command compilation. No duplicate trust path was added.

### Third-pass closure result

After these corrections, every FF01–FF04 handoff promise has a production owner and a source-level acceptance path. **No remaining source-level FF01–FF04 promise gap** was found in the third pass. The target Termux Devtool run remains authoritative for Kotlin/Gradle/unit/lint/APK execution and can still expose target-only defects that this static audit cannot simulate.

## Fourth-pass roadmap re-audit — v4

The fourth independent pass attacked FF01–FF04 through runtime state transitions and failure windows instead of repeating the v3 source checklist. One additional FF02 lifecycle gap survived the earlier seals.

### Gap found: durable embedded state with a dead Retry action

`EmbeddedFfmpegMediaManager` correctly persisted adaptive/live failures as `Failed`, `Cancelled`, or `RecoveryRequired`, and `MediaExecutionLibrary` correctly exposed those generations as retryable. However, `EmbeddedFfmpeg` output records intentionally have `downloadId = null`. All Library Retry handlers only knew how to retry an external Termux owner or an ordinary Download row, so pressing Retry on an embedded adaptive/live generation performed no action.

v4 closes the mismatch end to end:

1. `MainViewModel.retryEmbeddedFfmpegOutput` accepts only retryable `EmbeddedFfmpeg` generations.
2. It rehydrates exact capture/variant URLs and request headers only from `MediaRequestHandoffStore`; secrets are not copied into durable output metadata.
3. It preserves the prior destination URI, final filename and selected track IDs, reconstructs the original live/audio/video/best-video intent, and re-runs the current planner/security/runtime negotiation.
4. Retry is accepted only if the refreshed plan still resolves to `FfmpegAdaptive` or `FfmpegLive`; a changed capability never silently escapes to Termux.
5. The retry is admitted as `AdditionalGeneration`, preserving historical lineage and keeping the failed generation immutable.
6. Library list, grid and Details Retry surfaces dispatch `EmbeddedFfmpeg` by output ID to this recovery path.
7. Native-HLS failed/recovery downloads remain intercepted by `NativeHlsMediaManager.resume`; normal local post-processing remains on its existing immutable `PostProcessingJobSpec` generation retry path.

### Fourth-pass result

After tracing enqueue → active execution → cancellation/failure → recovery exposure → user Retry → new-generation admission for all three Android-owned FFmpeg paths, no additional source-level FF01–FF04 handoff promise gap remained. v4 supersedes v1–v3 and is cumulative against the successful post-v7r4 baseline.

## Fourth-pass authoritative build follow-up: v5

Real Termux validation of v4 exposed a compile-completeness gap that static behavior assertions did not catch: `MediaExecutionLibrary.kt` used `MediaVariantKind.Audio` without importing the model enum, and `NativeHlsMediaManager.kt` contained the same latent omission. Devtool correctly rolled the entire v4 transaction back. v5 adds both imports and converts that exact failure mode into an FF02/post-seal validation requirement. All v4 roadmap behavior and recovery guarantees remain cumulative.
## v7 authoritative compile closure

The v6 Termux validation reached `:app:compileDebugKotlin` and failed because `embeddedToolVersionsJson()` was a non-suspend helper calling suspend `EmbeddedFfmpegRuntime.capabilities()`. v7 makes that helper suspend; its existing callers already execute in `runEmbeddedMediaAction()`, which is suspend. FF02/post-seal validation now requires this signature explicitly. The v6 Devtool transaction rolled back successfully, so v7 remains cumulative directly over the post-v7r4 baseline.

