package com.mikeyphw.xdm.android.termux

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * Canonical Android-side boundary for every root filesystem action.
 *
 * Shell templates deliberately do not infer ownership from path substrings. A path may cross the
 * root boundary only if it is under an explicit shared-media/app root or was independently verified
 * by the Android artifact bridge as an exact XDM-owned artifact.
 */
object TermuxRootActionAuthorizer {
    fun authorize(
        context: Context,
        action: XdmRootAction,
        verifiedOwnedPaths: Set<String> = emptySet(),
    ): Result<XdmRootAction> = runCatching {
        val exactOwned = verifiedOwnedPaths.map(::canonical).toSet()
        when (action) {
            is XdmRootAction.FixFilePermissions -> action.copy(path = authorizePath(context, action.path, exactOwned))
            is XdmRootAction.MoveCompletedFile -> action.copy(
                from = authorizePath(context, action.from, exactOwned),
                to = authorizePath(context, action.to, exactOwned),
            )
            is XdmRootAction.KillOwnedProcess -> error("PID-only root process ownership is not authorized; use the token-bound managed process controller.")
            else -> action
        }
    }

    private fun authorizePath(context: Context, raw: String, exactOwned: Set<String>): String {
        val normalized = normalizeLegacySharedPath(raw)
        val target = canonical(normalized)
        require(target != File.separator) { "Filesystem root is never an authorized XDM target." }
        if (target in exactOwned) return target
        val allowedRoots = buildSet {
            context.getExternalFilesDirs(null).filterNotNull().forEach { add(canonical(it.path)) }
            @Suppress("DEPRECATION")
            listOf(
                Environment.DIRECTORY_DOWNLOADS,
                Environment.DIRECTORY_MOVIES,
                Environment.DIRECTORY_MUSIC,
            ).forEach { directory ->
                add(canonical(File(Environment.getExternalStoragePublicDirectory(directory), "XDM").path))
            }
        }
        require(allowedRoots.any { root -> target == root || target.startsWith(root + File.separator) }) {
            "Path is outside app-specific storage or explicit XDM shared-media roots."
        }
        return target
    }

    private fun normalizeLegacySharedPath(raw: String): String {
        val value = raw.trim()
        if (value.equals("storage/downloads/XDM", ignoreCase = true)) {
            @Suppress("DEPRECATION")
            return File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "XDM").path
        }
        return value
    }

    private fun canonical(path: String): String = File(path).canonicalFile.path
}
