# XDM Media Parity04 Validation Hotfix V5 Report

## Purpose

Cumulative hotfix v5 supersedes validation hotfixes v1-v4. Hotfix v4 rolled back cleanly during validated apply after `:app:compileDebugUnitTestKotlin` rejected a Kotlin source-contract assertion in `DebugCenterV9ContractTest.kt`. The offending assertion passed a `Pair<String, String>` to `String.contains(...)`:

```kotlin
assertTrue(catalog.contains("legacyEncryptedBlocker" to "retired"))
```

Kotlin has no applicable `String.contains(Pair<...>)` overload, so the test source could not compile.

## Fix

The assertion is split into two ordinary string containment checks:

```kotlin
assertTrue(catalog.contains("legacyEncryptedBlocker"))
assertTrue(catalog.contains("retired"))
```

This preserves the intended contract: Debug Center must expose the retired legacy encrypted Firefox blocker marker and the direct-v3 current path without treating old encrypted handoff as a release blocker.

## Cumulative fixes carried forward

Hotfix v5 also carries forward all v1-v4 changes:

- DeveloperToolsScreen multiline-string Kotlin syntax repair.
- Room schema current-truth test rebases to schema 24.
- Native-first supported HLS expectation rebases after Parity03.
- Firefox direct-v3 / legacy encrypted blocker expectation rebases.
- LogicalMediaGraph nullable URI compile fix.
- `Files.readString` compatibility repair in app unit tests.
- App unit-test repo-root resolution fixes.
- Completed notification Share action roadmap gap closure.
- Carry-forward validator rebases for Parity04 final authority.

## Validation performed in this container

- Verified the exact v4 failure line in the replayed hotfix tree.
- Replaced the invalid overload with compile-safe string checks.
- Parsed the patched test source and confirmed the invalid Pair-based `contains` call is gone.
- Rebuilt the cumulative overlay from the v4 archive plus the v5 test-source repair.
- Updated `.devtool-artifact.json` hashes and apply command for v5.
- Replayed source/final SHA-256 inventory against the clean post-Parity04 preimage available in this workspace.

## Validation not claimed here

- Full Gradle/lint/device validation. That must run in the Termux/Android Devtool environment with validation enabled.

## Apply policy

Apply v5 instead of v1-v4. Previous hotfix attempts rolled back successfully during validation, so v5 expects the post-Parity04 tree with no hotfix applied.
