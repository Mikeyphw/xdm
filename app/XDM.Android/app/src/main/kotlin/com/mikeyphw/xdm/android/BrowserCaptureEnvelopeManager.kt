package com.mikeyphw.xdm.android

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.mikeyphw.xdm.android.browser.XdmBrowserDeepLinkPayload
import com.mikeyphw.xdm.android.model.ExternalUrlPolicy
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.spec.MGF1ParameterSpec
import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

/**
 * Owns the app-private key used by generated Firefox XPIs to deliver encrypted capture sessions.
 * The public key is exported into the generated extension; the private key never leaves AndroidKeyStore.
 */
class BrowserCaptureEnvelopeManager {
    data class Candidate(
        val url: String,
        val pageUrl: String?,
        val frameUrl: String?,
        val title: String?,
        val mimeType: String?,
        val contentLength: Long?,
        val stableMediaId: String?,
        val sessionRevision: Long,
        val quality: String,
        val reason: String,
        val mediaKind: String,
        val manifest: Boolean,
        val playbackObserved: Boolean,
        val evidence: List<String>,
        val proposedHeaders: Map<String, String>,
        val finalHeaders: Map<String, String>,
    )

    data class DecodedSession(
        val sessionId: String,
        val revision: Long,
        val pageUrl: String?,
        val pageTitle: String,
        val createdAtEpochMs: Long,
        val expiresAtEpochMs: Long,
        val totalCandidateCount: Int,
        val truncated: Boolean,
        val candidates: List<Candidate>,
    )

    val keyId: String
        get() = publicKey().encoded.sha256Hex().take(24)

    val publicKeySpkiBase64Url: String
        get() = base64Url(publicKey().encoded)

    fun decrypt(payload: XdmBrowserDeepLinkPayload, nowEpochMs: Long = System.currentTimeMillis()): Result<DecodedSession> = runCatching {
        require(payload.hasEncryptedCaptureEnvelope) { "Encrypted capture envelope is incomplete" }
        require(payload.captureKeyId == keyId) { "Firefox capture key is stale; regenerate the XPI" }
        val wrappedKey = decodeBase64Url(requireNotNull(payload.wrappedKey))
        val iv = decodeBase64Url(requireNotNull(payload.envelopeIv))
        val ciphertext = decodeBase64Url(requireNotNull(payload.envelopeCiphertext))
        require(iv.size == 12) { "Encrypted capture IV has an invalid size" }
        require(wrappedKey.size in 256..512) { "Encrypted capture key has an invalid size" }
        require(ciphertext.size in 17..MAX_CIPHERTEXT_BYTES) { "Encrypted capture payload is outside the accepted size" }

        val rsa = Cipher.getInstance(RSA_TRANSFORMATION)
        val oaep = OAEPParameterSpec(
            "SHA-256",
            "MGF1",
            MGF1ParameterSpec.SHA256,
            PSource.PSpecified.DEFAULT,
        )
        rsa.init(Cipher.DECRYPT_MODE, privateKey(), oaep)
        val aesKey = rsa.doFinal(wrappedKey)
        require(aesKey.size == 32) { "Encrypted capture session key has an invalid size" }

        val aad = aad(requireNotNull(payload.captureSessionId), requireNotNull(payload.captureKeyId))
        val aes = Cipher.getInstance(AES_TRANSFORMATION)
        aes.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(128, iv))
        aes.updateAAD(aad.toByteArray(StandardCharsets.UTF_8))
        val clear = aes.doFinal(ciphertext)
        require(clear.size <= MAX_CLEAR_BYTES) { "Decrypted capture payload is too large" }
        parseSession(String(clear, StandardCharsets.UTF_8), payload.captureSessionId, nowEpochMs)
    }

    private fun parseSession(rawJson: String, expectedSessionId: String?, nowEpochMs: Long): DecodedSession {
        val json = JSONObject(rawJson)
        require(json.optInt("v", -1) == ENVELOPE_FORMAT_VERSION) { "Unsupported encrypted capture format" }
        val sessionId = json.optString("sid").safeToken(96)
        require(sessionId != null && sessionId == expectedSessionId) { "Encrypted capture session identity mismatch" }
        val revision = json.optLong("revision", -1L).takeIf { it > 0L } ?: error("Encrypted capture revision is missing")
        val createdAt = json.optLong("createdAt", -1L).takeIf { it > 0L } ?: error("Encrypted capture timestamp is missing")
        val expiresAt = json.optLong("expiresAt", -1L).takeIf { it > createdAt } ?: error("Encrypted capture expiry is missing")
        require(expiresAt - createdAt <= MAX_ENVELOPE_LIFETIME_MS) { "Encrypted capture lifetime is too long" }
        require(nowEpochMs >= createdAt - CLOCK_SKEW_MS && nowEpochMs <= expiresAt) { "Firefox capture session expired; capture the page again" }

        val pageUrl = ExternalUrlPolicy.normalizedUrl(json.optString("pageUrl").takeIf(String::isNotBlank))
        val title = json.optString("title").sanitizeText(240).ifBlank { "Firefox capture" }
        val totalCandidateCount = json.optInt("totalCandidateCount", 0).coerceAtLeast(0)
        val truncated = json.optBoolean("truncated", false)
        val array = json.optJSONArray("candidates") ?: JSONArray()
        require(array.length() in 1..MAX_CANDIDATES) { "Encrypted capture candidate count is invalid" }
        val candidates = buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val url = ExternalUrlPolicy.normalizedUrl(item.optString("url")) ?: continue
                val frameUrl = ExternalUrlPolicy.normalizedUrl(item.optString("frameUrl").takeIf(String::isNotBlank))
                val candidatePage = ExternalUrlPolicy.normalizedUrl(item.optString("pageUrl").takeIf(String::isNotBlank)) ?: pageUrl
                val stableId = item.optString("stableMediaId").safeToken(160)
                val candidateRevision = item.optLong("sessionRevision", revision).takeIf { it > 0L } ?: revision
                add(
                    Candidate(
                        url = url,
                        pageUrl = candidatePage,
                        frameUrl = frameUrl,
                        title = item.optString("title").sanitizeText(240).takeIf(String::isNotBlank) ?: title,
                        mimeType = item.optString("contentType").sanitizeMime(),
                        contentLength = item.optLong("contentLength", 0L).takeIf { it > 0L },
                        stableMediaId = stableId,
                        sessionRevision = candidateRevision,
                        quality = item.optString("quality", "strong").sanitizeToken(24, "strong"),
                        reason = item.optString("reason", "browser-media").sanitizeText(96).ifBlank { "browser-media" },
                        mediaKind = item.optString("streamKind", "media").sanitizeToken(24, "media"),
                        manifest = item.optBoolean("manifest", false),
                        playbackObserved = item.optBoolean("playbackObserved", false),
                        evidence = item.optJSONArray("evidence").stringList(8, 48),
                        proposedHeaders = item.optJSONObject("proposedHeaders").headerMap(),
                        finalHeaders = item.optJSONObject("finalHeaders").headerMap(),
                    ),
                )
            }
        }.distinctBy { it.stableMediaId ?: it.url }
        require(candidates.isNotEmpty()) { "Encrypted capture did not contain a usable media candidate" }
        return DecodedSession(
            sessionId = sessionId,
            revision = revision,
            pageUrl = pageUrl,
            pageTitle = title,
            createdAtEpochMs = createdAt,
            expiresAtEpochMs = expiresAt,
            totalCandidateCount = maxOf(totalCandidateCount, candidates.size),
            truncated = truncated || totalCandidateCount > candidates.size,
            candidates = candidates,
        )
    }

    private fun publicKey() = keyStore().getCertificate(KEY_ALIAS)?.publicKey ?: generateKeyPair().public
    private fun privateKey() = (keyStore().getKey(KEY_ALIAS, null) as? java.security.PrivateKey) ?: generateKeyPair().private

    private fun generateKeyPair(): java.security.KeyPair {
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEYSTORE)
        generator.initialize(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(3072)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKeyPair()
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun JSONObject?.headerMap(): Map<String, String> {
        if (this == null) return emptyMap()
        return keys().asSequence().mapNotNull { rawName ->
            val name = rawName.trim().lowercase()
            if (name !in HEADER_ALLOWLIST) return@mapNotNull null
            val value = optString(rawName).replace(Regex("[\\r\\n]+"), " ").trim().take(8192)
            if (value.isBlank()) null else name to value
        }.toMap()
    }

    private fun JSONArray?.stringList(maxItems: Int, maxChars: Int): List<String> {
        if (this == null) return emptyList()
        return (0 until minOf(length(), maxItems)).mapNotNull { index ->
            optString(index).sanitizeText(maxChars).takeIf(String::isNotBlank)
        }.distinct()
    }

    private fun String?.safeToken(maxChars: Int): String? = this
        ?.trim()
        ?.take(maxChars)
        ?.takeIf { it.matches(Regex("[A-Za-z0-9._:-]+")) }

    private fun String.sanitizeText(maxChars: Int): String = replace(Regex("[\\u0000-\\u001F\\u007F]"), " ")
        .replace(Regex("\\s+"), " ").trim().take(maxChars)

    private fun String.sanitizeToken(maxChars: Int, fallback: String): String = trim().lowercase()
        .take(maxChars).takeIf { it.matches(Regex("[a-z0-9._:-]+")) } ?: fallback

    private fun String.sanitizeMime(): String? = substringBefore(';').trim().lowercase().take(120)
        .takeIf { it.matches(Regex("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+")) }

    private fun ByteArray.sha256Hex(): String = MessageDigest.getInstance("SHA-256")
        .digest(this).joinToString("") { "%02x".format(it) }

    private fun base64Url(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    private fun decodeBase64Url(value: String): ByteArray = Base64.decode(value, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    private fun aad(sessionId: String, keyId: String): String = "xdm-capture-v2|$sessionId|$keyId"

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "xdm_browser_capture_v2_rsa"
        private const val RSA_TRANSFORMATION = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"
        private const val AES_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val ENVELOPE_FORMAT_VERSION = 1
        private const val MAX_CANDIDATES = 24
        private const val MAX_CIPHERTEXT_BYTES = 60 * 1024
        private const val MAX_CLEAR_BYTES = 56 * 1024
        private const val MAX_ENVELOPE_LIFETIME_MS = 10 * 60 * 1000L
        private const val CLOCK_SKEW_MS = 2 * 60 * 1000L
        private val HEADER_ALLOWLIST = setOf("authorization", "cookie", "referer", "user-agent", "origin", "accept", "range")
    }
}
