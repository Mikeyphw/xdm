# Bug Hunt Remediation Phase 1 r2: External Control, Secrets, and Privacy

## Scope

This phase implements the complete first phase of `ANDROID_BUG_HUNT_REMEDIATION_ROADMAP_COMPLETE.md`. It covers exported automation control, untrusted browser/share handoffs, caller attribution, integration authentication, private-network review, request-secret persistence, cleartext and redirect safety, diagnostics/clipboard privacy, backup exclusions, and completed-file sharing.

## Security boundaries

- `MainActivity` is launcher-only for external callers. Exported Tasker-style commands terminate in `ExternalAutomationActivity`; browser/share/view inputs terminate in `ExternalAddDownloadActivity`.
- Untrusted inputs require a visible review. A generated integration secret permits public automation commands, but local/private targets still require confirmation. Claimed package identity is retained only as untrusted provenance.
- Approved drafts cross into `MainActivity` through a process-local one-use capability; the full draft is never serialized into the internal intent.
- Exact URLs and request headers live in an AES-GCM Android Keystore envelope under `noBackupFilesDir`. Room stores redacted URL forms and separated observed/claimed/verified identity.
- Transfer execution revalidates private targets and cleartext credentials. Native redirects reject HTTPS downgrade and private/unresolved targets without approval; aria2 receives only the approved envelope fields.
- Production cleartext is disabled by network security configuration. Debug permits only loopback development endpoints.
- Cloud backup and device-to-device extraction exclude all app data. Startup migration removes historical raw request secrets from Room and durable sidecars.
- Completed-file sharing is limited to completed app-private download roots or readable Android content URIs. Broad root/external FileProvider grants are removed.

## Query-redaction precision

- Durable and diagnostic URL forms preserve public query fields such as media quality, language, and ordinary author metadata.
- Only structurally recognized credential names are redacted. Exact names and separator-delimited suffixes cover tokens, signatures, credentials, sessions, passwords, keys, and common AWS/Google signed-URL forms.
- Query names are decoded before classification, so percent-encoded credential names cannot bypass redaction.
- Substring matches are forbidden: public names such as `author`, `monkey`, and `quality` remain intact.

## Failure lesson

The first Phase 1 artifact failed `:core-model:test` because `persistableUrl()` and `PrivacyDiagnosticsRedactor.redactUrl()` replaced every query value. The prevention rule is that privacy transformations must be validated in both directions: secrets must disappear, and explicitly public metadata must remain unchanged.

## Validation

The overlay validator checks the manifest/export boundary, network and backup resources, encrypted envelope implementation, Room migration/schema changes, external-control tests, private/cleartext guards, redaction and clipboard handling, FileProvider roots/grant policy, and absence of raw sensitive values in newly durable records. Full Gradle/lint/instrumentation validation remains required by Devtool.

## Phase 1 r3 validation hotfix

Phase 1 r3 supersedes the original Phase 1 and r2 artifacts from the pre-Phase-1 baseline. It preserves the full Phase 1 security slice and r2 key-aware query-redaction behavior, then repairs the `:transfer-native:testDebugUnitTest` failure caused by direct local-JVM access to Android's `NetworkSecurityPolicy` stub. Production and instrumented Android builds still use the real platform policy; local JVM tests treat only the SDK-stub `not mocked` failure as permissive so native transfer tests can exercise segmented download, retry, checkpoint, and content-range behavior.

