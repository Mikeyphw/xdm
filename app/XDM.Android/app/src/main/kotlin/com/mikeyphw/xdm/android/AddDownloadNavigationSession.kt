package com.mikeyphw.xdm.android

import java.util.UUID

/**
 * One authoritative in-memory owner for the Add Download route.  The shell must
 * not infer Add's return route from composition-local rememberSaveable state and
 * an external handoff must not restore into a later manual Add sheet.
 */
enum class AddDownloadSessionKind {
    Manual,
    External,
}

data class AddDownloadNavigationSession(
    val sessionId: String,
    val kind: AddDownloadSessionKind,
    val returnRoute: AppRoute,
    val ownerDraftId: String? = null,
    val openedAtEpochMs: Long = System.currentTimeMillis(),
    val formRevision: Long = 0L,
    val duplicateDecisionUrl: String? = null,
) {
    val isExternal: Boolean get() = kind == AddDownloadSessionKind.External
    val isManual: Boolean get() = kind == AddDownloadSessionKind.Manual

    fun ownsDraft(draftId: String?): Boolean =
        isExternal && ownerDraftId != null && ownerDraftId == draftId?.trim()?.takeIf(String::isNotBlank)

    fun externalDraftStillMatches(draftId: String?, url: String): Boolean =
        ownsDraft(draftId) && duplicateDecisionUrl?.let { it == url.trim() } ?: true

    fun recordDuplicateDecision(url: String): AddDownloadNavigationSession =
        copy(duplicateDecisionUrl = url.trim(), formRevision = formRevision + 1)
}

object AddDownloadNavigationPolicy {
    private val RestorablePrimaryRoutes = setOf(AppRoute.Downloads, AppRoute.Media, AppRoute.Library, AppRoute.Activity, AppRoute.Settings)

    fun sanitizeReturnRoute(candidate: AppRoute?): AppRoute =
        candidate?.takeIf { it in RestorablePrimaryRoutes } ?: AppRoute.Downloads

    fun visibleRoute(route: AppRoute, session: AddDownloadNavigationSession?): AppRoute =
        if (route == AppRoute.Add) sanitizeReturnRoute(session?.returnRoute) else route

    fun manualSession(
        currentPrimaryRoute: AppRoute?,
        nowEpochMs: Long = System.currentTimeMillis(),
        idFactory: () -> String = { "manual-add-${UUID.randomUUID()}" },
    ): AddDownloadNavigationSession = AddDownloadNavigationSession(
        sessionId = idFactory(),
        kind = AddDownloadSessionKind.Manual,
        returnRoute = sanitizeReturnRoute(currentPrimaryRoute),
        openedAtEpochMs = nowEpochMs,
    )

    fun externalSession(
        draftId: String,
        currentPrimaryRoute: AppRoute?,
        nowEpochMs: Long = System.currentTimeMillis(),
        idFactory: () -> String = { "external-add-${UUID.randomUUID()}" },
    ): AddDownloadNavigationSession = AddDownloadNavigationSession(
        sessionId = idFactory(),
        kind = AddDownloadSessionKind.External,
        returnRoute = sanitizeReturnRoute(currentPrimaryRoute),
        ownerDraftId = draftId.trim().takeIf(String::isNotBlank),
        openedAtEpochMs = nowEpochMs,
    )
}
