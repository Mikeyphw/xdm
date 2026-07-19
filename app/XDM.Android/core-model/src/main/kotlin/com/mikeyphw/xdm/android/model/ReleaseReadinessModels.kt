package com.mikeyphw.xdm.android.model

enum class ReleaseReadinessSeverity { Info, Warning, Blocking }

data class ReleaseReadinessCheck(
    val id: String,
    val severity: ReleaseReadinessSeverity,
    val title: String,
    val detail: String,
)

data class InstallUpdateReadinessReport(
    val versionName: String,
    val versionCode: Int,
    val packageId: String,
    val schemaVersion: Int,
    val buildType: String,
    val checks: List<ReleaseReadinessCheck>,
) {
    val blockingCount: Int get() = checks.count { it.severity == ReleaseReadinessSeverity.Blocking }
    val warningCount: Int get() = checks.count { it.severity == ReleaseReadinessSeverity.Warning }
    val readyForBetaInstall: Boolean get() = blockingCount == 0
    val summary: String get() = when {
        blockingCount > 0 -> "$blockingCount blocking install/update issue${if (blockingCount == 1) "" else "s"}"
        warningCount > 0 -> "$warningCount install/update warning${if (warningCount == 1) "" else "s"}"
        else -> "Install/update gate checks are clean"
    }

    fun redactedSummary(): String = buildString {
        appendLine("XDM Android install/update readiness")
        appendLine("Version: $versionName ($versionCode)")
        appendLine("Package: $packageId")
        appendLine("Build: $buildType")
        appendLine("Schema: $schemaVersion")
        append("Gate: $summary")
    }
}

object ReleaseInstallReadinessGate {
    fun evaluate(
        versionName: String,
        versionCode: Int,
        packageId: String,
        schemaVersion: Int,
        buildType: String,
        releaseSafetyComplete: Boolean,
        recoverySurfaceReady: Boolean,
        diagnosticsExportRedacted: Boolean,
        aria2PayloadGateRetained: Boolean,
        updateKeepsPackageIdentity: Boolean,
        releaseSigningConfigured: Boolean,
    ): InstallUpdateReadinessReport {
        val normalizedVersion = versionName.trim().ifBlank { "unknown" }
        val normalizedBuildType = buildType.trim().ifBlank { "unknown" }
        val normalizedPackageId = packageId.trim().ifBlank { "unknown" }
        val checks = buildList {
            val minor = normalizedVersion.removeSuffix("-debug").removeSuffix("-beta")
                .split('.')
                .getOrNull(1)
                ?.toIntOrNull()
                ?: -1
            if (minor < 16) {
                add(
                    ReleaseReadinessCheck(
                        id = "version.phase16",
                        severity = ReleaseReadinessSeverity.Blocking,
                        title = "Version metadata is stale",
                        detail = "Phase 16 install/update readiness requires a 0.16.x version.",
                    ),
                )
            }
            if (versionCode < 17) {
                add(
                    ReleaseReadinessCheck(
                        id = "version.code",
                        severity = ReleaseReadinessSeverity.Blocking,
                        title = "Version code is not monotonic",
                        detail = "A beta/update APK must advance versionCode to at least 17.",
                    ),
                )
            }
            if (normalizedPackageId != "com.mikeyphw.xdm.android") {
                add(
                    ReleaseReadinessCheck(
                        id = "package.identity",
                        severity = ReleaseReadinessSeverity.Blocking,
                        title = "Unexpected base package identity",
                        detail = "Install/update readiness depends on com.mikeyphw.xdm.android remaining stable.",
                    ),
                )
            }
            if (schemaVersion != 13) {
                add(
                    ReleaseReadinessCheck(
                        id = "database.schema",
                        severity = ReleaseReadinessSeverity.Blocking,
                        title = "Unexpected schema migration",
                        detail = "Phase 16 must not migrate Room; updates must preserve schema v13.",
                    ),
                )
            }
            if (!releaseSafetyComplete) {
                add(
                    ReleaseReadinessCheck(
                        id = "release.safety",
                        severity = ReleaseReadinessSeverity.Blocking,
                        title = "Release safety gate is incomplete",
                        detail = "Privacy-safe diagnostics and release checks must be active before packaging.",
                    ),
                )
            }
            if (!recoverySurfaceReady) {
                add(
                    ReleaseReadinessCheck(
                        id = "recovery.surface",
                        severity = ReleaseReadinessSeverity.Blocking,
                        title = "Recovery surface is not ready",
                        detail = "Interrupted downloads, finalization journals, and orphan artifacts must remain recoverable after update.",
                    ),
                )
            }
            if (!diagnosticsExportRedacted) {
                add(
                    ReleaseReadinessCheck(
                        id = "diagnostics.bundle",
                        severity = ReleaseReadinessSeverity.Blocking,
                        title = "Diagnostic bundle is not redacted",
                        detail = "Install/update triage must not expose cookies, bearer tokens, or signed URLs.",
                    ),
                )
            }
            if (!aria2PayloadGateRetained) {
                add(
                    ReleaseReadinessCheck(
                        id = "aria2.payload",
                        severity = ReleaseReadinessSeverity.Blocking,
                        title = "aria2 payload gate is missing",
                        detail = "Beta packaging must keep the runtime payload verification gate.",
                    ),
                )
            }
            if (!updateKeepsPackageIdentity) {
                add(
                    ReleaseReadinessCheck(
                        id = "update.identity",
                        severity = ReleaseReadinessSeverity.Blocking,
                        title = "Update identity is not stable",
                        detail = "Release updates must keep the base package ID so Android treats them as upgrades.",
                    ),
                )
            }
            if (normalizedBuildType == "release" && !releaseSigningConfigured) {
                add(
                    ReleaseReadinessCheck(
                        id = "signing.release",
                        severity = ReleaseReadinessSeverity.Warning,
                        title = "Release signing not configured",
                        detail = "Local release builds can be unsigned, but publishable APKs must provide release signing values.",
                    ),
                )
            }
            if (isEmpty()) {
                add(
                    ReleaseReadinessCheck(
                        id = "install-update.clean",
                        severity = ReleaseReadinessSeverity.Info,
                        title = "Install/update gate",
                        detail = "Package identity, schema, recovery, diagnostics, and payload checks are ready for beta packaging.",
                    ),
                )
            }
        }
        return InstallUpdateReadinessReport(
            versionName = normalizedVersion,
            versionCode = versionCode,
            packageId = normalizedPackageId,
            schemaVersion = schemaVersion,
            buildType = normalizedBuildType,
            checks = checks,
        )
    }
}
