package com.mikeyphw.xdm.android.browser

object SharedLinkParser {
    private val urlPattern = Regex("""https?://[^\s]+""", RegexOption.IGNORE_CASE)
    fun parse(text: String): List<String> = urlPattern.findAll(text).map { it.value }.distinct().toList()
}
