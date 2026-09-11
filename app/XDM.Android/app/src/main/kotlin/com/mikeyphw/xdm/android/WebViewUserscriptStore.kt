package com.mikeyphw.xdm.android

import android.content.Context
import org.json.JSONArray

/**
 * Parity04 lightweight userscript support for the built-in media locator.
 *
 * This is intentionally smaller than a full Tampermonkey clone: users paste/load one or more
 * user scripts, XDM honours common @match/@include metadata for WebView injection, and the source
 * stays local to the app. It never grants extension APIs, native file access, or privileged XDM
 * internals to the page. The goal is compatibility with simple page helpers that expose hidden
 * media elements, not browser-extension execution inside Android WebView.
 */
data class WebViewUserscript(
    val enabled: Boolean,
    val scriptSource: String,
) {
    val metadata: UserscriptMetadata get() = UserscriptMetadata.parse(scriptSource)
}

data class UserscriptMetadata(
    val name: String,
    val matches: List<String>,
    val includes: List<String>,
    val runAtDocumentStart: Boolean,
) {
    fun appliesTo(pageUrl: String?): Boolean {
        if (pageUrl.isNullOrBlank()) return true
        val patterns = (matches + includes).ifEmpty { listOf("*") }
        return patterns.any { pattern -> UserscriptPattern.matches(pattern, pageUrl) }
    }

    companion object {
        fun parse(source: String): UserscriptMetadata {
            val lines = source.lines()
            val name = lines.firstNotNullOfOrNull { line ->
                USER_SCRIPT_NAME.matchEntire(line.trim())?.groupValues?.getOrNull(1)?.trim()?.takeIf(String::isNotBlank)
            } ?: "Custom userscript"
            val matches = lines.mapNotNull { line -> USER_SCRIPT_MATCH.matchEntire(line.trim())?.groupValues?.getOrNull(1)?.trim()?.takeIf(String::isNotBlank) }
            val includes = lines.mapNotNull { line -> USER_SCRIPT_INCLUDE.matchEntire(line.trim())?.groupValues?.getOrNull(1)?.trim()?.takeIf(String::isNotBlank) }
            val runAt = lines.firstNotNullOfOrNull { line -> USER_SCRIPT_RUN_AT.matchEntire(line.trim())?.groupValues?.getOrNull(1)?.trim() }
            return UserscriptMetadata(
                name = name,
                matches = matches,
                includes = includes,
                runAtDocumentStart = runAt.equals("document-start", ignoreCase = true),
            )
        }

        private val USER_SCRIPT_NAME = Regex("//\\s*@name\\s+(.+)")
        private val USER_SCRIPT_MATCH = Regex("//\\s*@match\\s+(.+)")
        private val USER_SCRIPT_INCLUDE = Regex("//\\s*@include\\s+(.+)")
        private val USER_SCRIPT_RUN_AT = Regex("//\\s*@run-at\\s+(.+)")
    }
}

data class UserscriptValidationResult(
    val accepted: Boolean,
    val message: String,
)

object WebViewUserscriptPolicy {
    private const val MaxSourceBytes = 256 * 1024

    fun validate(script: WebViewUserscript): UserscriptValidationResult {
        if (!script.enabled && script.scriptSource.isBlank()) return UserscriptValidationResult(true, "Userscripts disabled")
        val source = script.scriptSource.trim()
        if (source.isBlank()) return UserscriptValidationResult(false, "Paste a userscript or disable userscripts")
        if (source.toByteArray(Charsets.UTF_8).size > MaxSourceBytes) {
            return UserscriptValidationResult(false, "Userscript is too large for the lightweight WebView loader")
        }
        if (source.contains("chrome.runtime", ignoreCase = true) || source.contains("browser.runtime", ignoreCase = true)) {
            return UserscriptValidationResult(false, "Browser-extension APIs are not available inside Android WebView")
        }
        if (source.contains("@grant", ignoreCase = true) && !source.contains("@grant none", ignoreCase = true)) {
            return UserscriptValidationResult(false, "Only @grant none scripts are supported by the lightweight WebView loader")
        }
        return UserscriptValidationResult(true, "Userscript saved for matching pages")
    }

    fun wrapForInjection(source: String): String {
        val body = source.substringAfter("// ==/UserScript==", source).trim()
        val escaped = JSONArray().put(body).toString().removePrefix("[").removeSuffix("]")
        return """
            (function(){
              try {
                if (window.__xdmUserscriptRan) return;
                window.__xdmUserscriptRan = true;
                var script = document.createElement('script');
                script.textContent = $escaped;
                (document.documentElement || document.head || document.body).appendChild(script);
                script.remove();
              } catch (error) {
                console.warn('XDM userscript injection failed', error && error.message ? error.message : error);
              }
            })();
        """.trimIndent()
    }
}

class WebViewUserscriptStore(context: Context) {
    private val prefs = context.getSharedPreferences("xdm-webview-userscripts", Context.MODE_PRIVATE)

    fun snapshot(): WebViewUserscript = WebViewUserscript(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        scriptSource = prefs.getString(KEY_SOURCE, "").orEmpty(),
    )

    fun save(script: WebViewUserscript) {
        prefs.edit()
            .putBoolean(KEY_ENABLED, script.enabled)
            .putString(KEY_SOURCE, script.scriptSource)
            .apply()
    }

    fun documentStartScriptFor(pageUrl: String?): String {
        val script = snapshot()
        if (!script.enabled || script.scriptSource.isBlank()) return ""
        if (!script.metadata.appliesTo(pageUrl)) return ""
        return WebViewUserscriptPolicy.wrapForInjection(script.scriptSource)
    }

    companion object {
        private const val KEY_ENABLED = "enabled"
        private const val KEY_SOURCE = "source"
    }
}

private object UserscriptPattern {
    fun matches(pattern: String, url: String): Boolean {
        val normalized = pattern.trim().ifBlank { "*" }
        if (normalized == "*" || normalized == "*://*/*") return true
        val regex = Regex.escape(normalized)
            .replace("\\*", ".*")
            .replace("\\?", ".")
        return runCatching { Regex("^$regex$").containsMatchIn(url) }.getOrDefault(false)
    }
}
