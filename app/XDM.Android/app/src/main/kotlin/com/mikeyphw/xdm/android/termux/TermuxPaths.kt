package com.mikeyphw.xdm.android.termux

import android.content.Context
import java.io.File

/** Path normalization for Termux RUN_COMMAND, which requires absolute Termux-side paths. */
object TermuxPaths {
    private const val TermuxPackage: String = "com.termux"

    fun home(context: Context): String = termuxFilesDir(context, "home")

    fun prefix(context: Context): String = termuxFilesDir(context, "usr")

    fun normalizeWorkdir(context: Context, workdir: String?): String {
        val home = home(context)
        var value = workdir?.trim().orEmpty()
        if (value.isEmpty() || value == "~" || value == "~/" || value == "\$HOME" || value == "\${HOME}") {
            return home
        }
        if (value.startsWith("/\$HOME/")) value = value.drop(1)
        if (value.startsWith("/\${HOME}/")) value = value.drop(1)
        if (value.startsWith("/~")) value = value.drop(1)
        return when {
            value.startsWith("~/") -> home + value.drop(1)
            value.startsWith("\$HOME/") -> home + value.drop("\$HOME".length)
            value.startsWith("\${HOME}/") -> home + value.drop("\${HOME}".length)
            value.startsWith(File.separator) -> value
            else -> "$home${File.separator}$value"
        }
    }

    @Suppress("DEPRECATION")
    private fun termuxFilesDir(context: Context, child: String): String {
        val dataDir = runCatching {
            context.packageManager.getApplicationInfo(TermuxPackage, 0).dataDir
        }.getOrNull() ?: buildTermuxDataDirFallback()
        return File(File(dataDir, "files"), child).path
    }

    private fun buildTermuxDataDirFallback(): String = listOf(
        "",
        "data",
        "data",
        TermuxPackage,
    ).joinToString(File.separator)
}
