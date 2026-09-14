package com.mikeyphw.xdm.android

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections

/**
 * In-process approval gate for the legacy internal direct-capture MainActivity route.
 *
 * MainActivity is exported because it is the launcher. Therefore an ACTION_INTERNAL_* intent and
 * extras are forgeable by other apps. The exported review boundary must first parse the canonical
 * Firefox direct-capture URI and mint a one-use token here; MainActivity then consumes that token
 * before invoking ingestDirectBrowserCaptureSession. Tokens never leave process memory except as
 * the internal extra on the activity hop, are not persisted, and are single-use.
 */
internal object InternalBrowserCaptureApprovalGate {
    private const val MAX_TOKENS = 16
    private val random = SecureRandom()
    private val approved = Collections.synchronizedMap(LinkedHashMap<String, Long>())

    fun approve(rawDeepLink: String?): String? {
        val material = rawDeepLink?.trim()?.takeIf(String::isNotBlank) ?: return null
        val nonce = ByteArray(24).also(random::nextBytes).joinToString("") { byte -> "%02x".format(byte) }
        val token = sha256("xfe-direct-capture-v1|$material|$nonce")
        synchronized(approved) {
            while (approved.size >= MAX_TOKENS) {
                val first = approved.keys.firstOrNull() ?: break
                approved.remove(first)
            }
            approved[token] = System.currentTimeMillis()
        }
        return token
    }

    fun consume(rawDeepLink: String?, routeToken: String?): Boolean {
        if (rawDeepLink.isNullOrBlank() || routeToken.isNullOrBlank()) return false
        val acceptedAt = synchronized(approved) { approved.remove(routeToken) } ?: return false
        return System.currentTimeMillis() - acceptedAt <= 30_000L
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}
