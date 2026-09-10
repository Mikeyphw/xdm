# XDM Android post-UX13 roadmap-completion hotfix

Date: 2026-09-09
Overlay: `xdm_android_post_ux13_roadmap_completion_hotfix_v1.zip`
Baseline: `xdm_android_ux13_end_to_end_ui_ux_release_seal_v1.zip`
Room schema: 21 (unchanged)

## Why this hotfix exists

A fresh post-UX13 screenshot/source audit found that the end-to-end seal had left one browser-media interstitial and several smaller presentation seams from the original screenshot-driven roadmap. This hotfix makes the manifest truthful again: UX13 remains the end-to-end baseline, while this artifact is the current UI release authority.

## Browser-media handoff

The direct v3 Firefox/browser media path no longer displays the `Open browser media in XDM` AlertDialog. After the exported intake boundary successfully parses a direct capture session, it immediately converts that accepted external URI into the existing internal-only `ACTION_INTERNAL_BROWSER_DIRECT_CAPTURE_IMPORT` action and opens the Media intake path.

This does **not** auto-start a download. It only imports the detected media into XDM for the existing Media workflow. The private-network approval bit continues to be computed for the exact normalized target as before. Generic external commands still use the `Open in XDM` confirmation boundary, and the legacy encrypted-capture recovery path keeps its existing protected review/decryption boundary.

The removed dialog therefore no longer exposes a raw media URL, `candidate(s)`, `bounded candidate set`, or a generic `Continue` action before Media opens.

## Roadmap re-audit and folded gaps

The re-audit replayed the UX01–UX13 implementation reports, the original screenshot acceptance checklist, the current manifest promises, and current production source. The following carry-forward gaps were found and folded into this hotfix:

1. **Direct browser media still had a second confirmation dialog.** Removed as described above.
2. **Media intake diagnostics were still printed inline.** Sanitized diagnostics now live under expandable `Technical details` and render as selectable monospace technical text.
3. **Media intake feedback still exposed implementation vocabulary.** User-facing `reviewable`, `bounded page prefix`, `durable revision`, `request handoffs`, and candidate-plumbing copy was replaced with task-oriented Media language. Feedback badges now use the UX12 vocabulary (`Running`, `Ready`, `Needs action`, `Failed`, `Completed`).
4. **Recovery copy still used `Needs recovery`.** Download-facing recovery state is normalized to `Needs action` while the underlying `RecoveryRequired` model remains unchanged.
5. **The Downloads overview still rendered an all-zero/Idle metric strip.** The strip is now hidden when there is no downloading, waiting, queued, moving-speed, or remaining-time information to show.
6. **Developer Center still repeated its own title in an intro card below the page header.** The duplicate title/card is removed; only lightweight explanatory copy remains above the section controls.
7. **Release source-truth text was stale.** README and the legacy `current_release_authority` manifest field now name UX13 as the end-to-end baseline and this hotfix as current UI authority; README also accurately documents the constrained user-requested Live Locator WebView rather than claiming XDM contains no WebView.

No other unresolved UX01–UX13 product promise was found. Existing UX13 contracts continue to own shared storage truth, Waiting semantics, media lifecycle, Live Locator renderer recovery, Library/Activity recovery, Settings/Developer boundaries, accessibility semantics, and the retained validation matrix.

## Safety and persistence

- Room remains schema 21; no migration is added.
- No new storage permission or top-level route is added.
- Direct browser capture still routes through the established internal-only action/extra boundary before `MainActivity` imports it.
- Direct browser media import does not enqueue or start a transfer automatically.
- Sanitization remains authoritative before Media intake diagnostics reach UI state.
- Existing generic external-command and legacy encrypted-capture safety boundaries are preserved.

## Validation ownership

This is a final corrective overlay, not an intermediate roadmap patch. It must be applied **without** `--no-validate`. The canonical final gate must execute `validate-post-ux13-roadmap-completion-hotfix.py` after the UX13 carry-forward validator, and the complete Gradle/unit/lint/browser-extension/build task set remains required. Any required validation failure must roll the overlay back.

## Validation repair revision v2

The first packaged revision reached the real Android validation environment and rolled back before commit. That run exposed deferred compile/test debt that the intermediate UX overlays had intentionally not executed. The v2 artifact is a validation-repair revision of the same post-UX13 product hotfix: it does not restore the removed browser-media dialog or change the roadmap scope.

The repair makes the Add Download preflight label null-safe when classification is not yet available, exposes only the three Settings helpers that are intentionally shared across the split settings files as `internal`, and reconciles retained source-contract tests with the shipped UX05/UX08/UX09/UX10/UX12 terminology and file ownership. The core-model clipboard contract now expects `XDM Diagnostics & support` rather than the retired Debug Workbench title.

For product/source-truth compatibility, `PROJECT_MANIFEST.current_overlay` continues to identify `xdm_android_post_ux13_roadmap_completion_hotfix_v1` as the semantic product overlay. `xdm_android_post_ux13_roadmap_completion_hotfix_v2` is the packaging/validation repair revision that makes that same product state compile and validate from the post-UX13 baseline.

## Validation repair revision v3

The second packaged repair revision reached Android unit-test compilation and exposed one remaining deferred test-framework mismatch from UX06/UX07: those two app-module source contracts imported `kotlin.test`, while the Android app module declares JUnit 4 (`testImplementation(libs.junit)`) and the rest of the app test suite uses `org.junit`. Revision v3 converts only those two contracts to the project-standard JUnit 4 imports; no product behavior changes.

A repository-wide test-source audit now covers all unit and instrumentation Kotlin test trees. No `kotlin.test` or JUnit Jupiter imports remain. The post-UX13 static validator also rejects unsupported `kotlin.test`/JUnit Jupiter imports before Gradle compilation so this class of deferred failure is caught by `:app:finalRemediationStaticGate`.

For product/source-truth compatibility, `PROJECT_MANIFEST.current_overlay` continues to identify `xdm_android_post_ux13_roadmap_completion_hotfix_v1` as the semantic product overlay. `xdm_android_post_ux13_roadmap_completion_hotfix_v3` is the test-framework/validation repair revision that supersedes v2 for application from the same post-UX13 baseline.

## Validation repair revision v4

The v3 Android validation run compiled production sources, passed the canonical static gate, passed browser-extension JS and Firefox validation, assembled the debug APK and Android-test APK, and then executed 408 app unit tests. Exactly three tests failed: the UX06 Live Locator contract and both UX07 Library/Activity contract methods. Their product assertions were valid, but those two test classes opened source files with paths relative to the Gradle test process working directory. On the real Android Gradle runner that working directory is not guaranteed to be the XDM.Android root, so the tests failed with `FileNotFoundException`.

Revision v4 makes UX06 and UX07 locate the XDM.Android root by walking up from `user.dir` before resolving source files. The same tests were exercised from both the XDM.Android root and the app-module directory, including the latter working-directory shape that exposed v3. The UX09 Developer Center contract also treats `user.dir` as nullable-safe, removing the Kotlin Java-platform-type warning reported by v3.

The post-UX13 static validator now rejects unrooted `File("app/src/...` and `File("core-model/src/...` reads in app source-contract tests, so this working-directory assumption is caught before Gradle unit-test execution. No product behavior changes in v4: the browser-media confirmation dialog remains removed and all roadmap-completion fixes are preserved.

For product/source-truth compatibility, `PROJECT_MANIFEST.current_overlay` continues to identify `xdm_android_post_ux13_roadmap_completion_hotfix_v1` as the semantic product overlay. `xdm_android_post_ux13_roadmap_completion_hotfix_v4` is the test-root/validation repair revision that supersedes v3 for application from the same post-UX13 baseline.

## Validation repair revision v5

After revision v4 was applied and committed, a standalone Android lint run reported two obsolete Live Locator string resources: `media_locator_locate` and `media_locator_rescan`. UX06 replaced those actions with the current native-shell `Scan media`/reload flow, so the old resources had no remaining production or XML references.

Revision v5 removes those two dead resource declarations rather than suppressing `UnusedResources`. The post-UX13 static validator now also forbids both resource names, allowing the canonical static gate to catch this exact regression before Android lint. No runtime behavior, route, persistence contract, Room schema, or browser-media handoff behavior changes.

Revision v5 is a delta on top of the successfully applied and committed v4 state. `PROJECT_MANIFEST.current_overlay` remains the semantic v1 post-UX13 product hotfix; `validation_repair_artifact` identifies v5 as the latest validation/lint repair revision.
