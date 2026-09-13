# XAR01 — Reproducible Android Build, Signing & Native Runtime Provenance

**Roadmap position:** overlay 1 of 17  
**Primary section:** S01 — Build & Packaging  
**Canonical roots owned:** 18/18  
**Status:** implemented; focused executable contract green

## Scope delivered

- CI provisions the exact Android NDK `29.0.14206865` and the stable-toolchain verifier inspects the repository workflow that actually runs.
- Normal developer/debug builds keep optional native runtimes optional. Direct release packaging is fail-closed and verifies preinstalled runtime payloads instead of silently downloading/building them; the canonical signed-release gate is the explicit provisioning path.
- aria2 installation requires a trusted upstream archive SHA-256, incorporates it into cache identity, rejects mismatched cached payloads, packages GPL/source notices, and verifies those notices in the APK.
- FFmpeg/OpenSSL source and binary caches are generation/provenance bound to exact source archives, NDK revision, NDK metadata, compiler/binutils bytes and versions, configure/build markers, and produced runtime hashes. Termux LLVM fallback tools are therefore part of cache identity rather than an invisible host dependency.
- Release signing preflight loads the configured keystore certificate, hashes the actual certificate, compares it to `XDM_RELEASE_SIGNER_SHA256`, writes signer evidence, and is attached to direct publishable AGP release packaging/signing entry points.
- FFmpeg/OpenSSL generated license assets are declared as installer outputs and remain part of strict runtime/APK verification.
- The browser-extension release gate derives app/extension versions dynamically instead of hardcoding an obsolete XPI version.
- The root release helper and CI now use the same canonical signed-release gate; Devtool artifact collection cleans normal outputs first and requires externally verified artifacts.
- `bundletool` is self-provisioned through `tools/ensure-bundletool.py` with a pinned version (`1.18.3`) and SHA-256 (`a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29`).
- Retained release validators were reconciled with the new behavior so older source-text contracts cannot demand the unsafe pre-XAR01 cache/runtime semantics.

## Canonical finding closure

| Canonical ID | Severity | Finding | XAR01 closure |
|---|---|---|---|
| `S01-01` | — | Active CI does not provision required NDK 29 | Exact NDK 29.0.14206865 is installed in both CI jobs and verified by the stable baseline contract. |
| `S01-02` | — | Optional native runtimes are effectively mandatory build-time dependencies | Native runtime source builds are removed from ordinary build graphs; explicit strict runtime tasks/gates own provisioning. |
| `S01-03` | — | Direct release builds can consume unpinned aria2 bytes | aria2 official acquisition requires an explicit trusted archive SHA-256 before download/cache reuse. |
| `S01-04` | — | Stable toolchain verifier checks the wrong CI workflow | The stable toolchain verifier now reads the repository-root Android workflow and canonical root release/devtool files. |
| `S01-05` | — | Browser release gate version drift | Browser gate derives extension/app versions from current project metadata rather than hardcoded release names. |
| `S01-06` | — | Advertised release helper is weaker/incomplete relative to release contract | The advertised root helper delegates signed release production to the canonical Phase 10 release gate and copies only its verified publication. |
| `S01-07` | — | FFmpeg 16 KB release evidence is incomplete | FFmpeg/FFprobe and generated licenses are included in release inventory and strict APK/runtime 16 KiB verification. |
| `S01-08` | — | Devtool may satisfy required artifacts with stale cached APK/AAB outputs | Devtool Android build starts with clean + assembleDebug and artifact collection requires external verification, preventing stale-output-only success. |
| `S01-09` | — | Static release validators can false-green important build conditions | XAR01 adds executable behavioral tests and updates retained validators whose old string assertions contradicted production semantics. |
| `S01-10` | — | aria2 distribution notice packaging is not evidenced | aria2 GPL-2.0/source notice assets are generated, declared, packaged, hashed, and APK-verified. |
| `S01-11` | — | CI path-filter drift surface | Android workflow path filters include the Devtool config and canonical release helper so release-critical changes trigger CI. |
| `S01-12` | — | bundletool acquisition is not self-contained | Repository-owned content-addressed bundletool provisioning replaces dependency on a pre-existing CI filesystem jar. |
| `DS1-S01-01` | High | Release signer pin is not bound to configured signing certificate | Gradle computes SHA-256 from the configured keystore certificate and compares it with the pin before publishable release packaging. |
| `DS1-S01-02` | High | FFmpeg/OpenSSL cache can self-attest stale or modified outputs as current pinned build | FFmpeg/OpenSSL cache reuse requires matching source tree, configuration/build markers, toolchain identity, and current artifact hashes. |
| `DS1-S01-03` | High | Wrong NDK revision accepted and attested as requested NDK 29 | NDK discovery parses source.properties and rejects any revision other than the exact required NDK revision. |
| `DS1-S01-04` | High | Termux native LLVM fallback is unpinned and absent from cache/provenance identity | NDK/Termux compiler and binutils executable SHA-256 + version fingerprints are part of native cache identity. |
| `DS1-S01-05` | High | Release signer preflight is bypassable through direct release packaging tasks | Direct release assemble/bundle/package/sign/validate-signing tasks depend on the signer preflight. |
| `DS1-S01-06` | Medium | FFmpeg generated license assets omitted from installer Gradle outputs | Generated FFmpeg/OpenSSL license files are explicit installer outputs and strict runtime/APK inputs. |

## Focused validation evidence

Passed in the implementation workspace:

```text
python3 tools/validate-xar01-build-provenance.py
  -> XAR01 build/signing/native provenance validation passed (18/18 S01 canonical roots covered).
  -> 7 focused adversarial provenance tests passed.

python3 tools/validate-bug-hunt-phase10-release-upgrade-packaging.py
  -> passed
python3 tools/validate-gradle-task-graph-optimization.py
  -> passed
python3 tools/validate-ffmpeg04-full-release-seal.py --skip-prerequisites
  -> passed
python3 tools/validate-ffmpeg-roadmap-postseal-hotfix.py --skip-prerequisites
  -> passed

python3 -m py_compile <all changed XAR01 Python tools>
bash -n <all changed XAR01 shell entrypoints>
YAML/JSON parse checks for changed workflow/runtime manifests
  -> passed
```

## Validation boundary / deferred findings

This is an intermediate roadmap overlay, so full project validation is intentionally not claimed here. A live Gradle configuration/task-graph execution could not be performed in the artifact-building container because the Gradle 9.7.1 wrapper distribution was not present and outbound network access is disabled. The overlay therefore carries the executable XAR01 contracts for the user's real Termux/Devtool environment.

The browser static gate also continues to report pre-existing browser/intake findings (sensitive raw-header payload semantics and deep-link automation routing). Those belong to later roadmap ownership, especially XAR05/XAR10, and are deliberately not hidden or opportunistically rewritten by XAR01.

## Apply policy

Apply XAR01 with `--no-validate` because it is overlay 1 of 17. The focused XAR01 contract is included in the repository and the final implementation/release overlays will execute the converged explicit validation matrix.
