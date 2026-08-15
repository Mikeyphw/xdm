package com.mikeyphw.xdm.android

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.mikeyphw.xdm.android.browser.XdmBrowserDeepLinkPayload
import com.mikeyphw.xdm.android.model.ExternalUrlPolicy
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.GeneralSecurityException
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
        val requestFingerprint: String,
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

    /** WebCrypto hash that must be used by the generated Firefox extension for RSA-OAEP. */
    val captureOaepHash: String
        get() = oaepDigest

    /** RSA ciphertext size expected for this app-private capture key. */
    val expectedWrappedKeyBytes: Int
        get() = ((publicKey() as java.security.interfaces.RSAPublicKey).modulus.bitLength() + 7) / 8

    /** Read-only provider smoke test used by Debug Center; no capture data or network access is involved. */
    fun selfTestKeyWrap(): Result<Unit> = runCatching {
        val clearKey = ByteArray(32).also(java.security.SecureRandom()::nextBytes)
        val spec = OAEPParameterSpec(oaepDigest, "MGF1", mgf1ParameterSpec, PSource.PSpecified.DEFAULT)
        val encrypt = Cipher.getInstance(RSA_TRANSFORMATION)
        encrypt.init(Cipher.ENCRYPT_MODE, publicKey(), spec)
        val wrapped = encrypt.doFinal(clearKey)
        check(wrapped.size == expectedWrappedKeyBytes) { "RSA-OAEP produced an unexpected ciphertext size" }
        val decrypt = Cipher.getInstance(RSA_TRANSFORMATION)
        decrypt.init(Cipher.DECRYPT_MODE, privateKey(), spec)
        check(decrypt.doFinal(wrapped).contentEquals(clearKey)) { "RSA-OAEP round trip did not reproduce the session key" }
    }

    fun decrypt(payload: XdmBrowserDeepLinkPayload, nowEpochMs: Long = System.currentTimeMillis()): Result<DecodedSession> = runCatching {
        require(payload.hasEncryptedCaptureEnvelope) { "Encrypted capture envelope is incomplete" }
        require(payload.captureKeyId == keyId) { "Browser capture key is stale; regenerate the XPI" }
        val wrappedKey = decodeBase64Url(requireNotNull(payload.wrappedKey))
        val iv = decodeBase64Url(requireNotNull(payload.envelopeIv))
        val ciphertext = decodeBase64Url(requireNotNull(payload.envelopeCiphertext))
        require(iv.size == 12) { "Encrypted capture IV has an invalid size" }
        val expectedWrappedBytes = expectedWrappedKeyBytes
        require(wrappedKey.size == expectedWrappedBytes) {
            "Browser secure handoff has an invalid encrypted-key size; expected $expectedWrappedBytes bytes but received ${wrappedKey.size}. Regenerate the XPI and capture the page again."
        }
        require(ciphertext.size in 17..MAX_CIPHERTEXT_BYTES) { "Encrypted capture payload is outside the accepted size" }

        val aesKey = try {
            val rsa = Cipher.getInstance(RSA_TRANSFORMATION)
            val oaep = OAEPParameterSpec(
                oaepDigest,
                "MGF1",
                mgf1ParameterSpec,
                PSource.PSpecified.DEFAULT,
            )
            rsa.init(Cipher.DECRYPT_MODE, privateKey(), oaep)
            rsa.doFinal(wrappedKey)
        } catch (error: GeneralSecurityException) {
            throw IllegalArgumentException(
                "Browser secure handoff could not decrypt the session key. Regenerate the XPI in XDM and capture the page again.",
                error,
            )
        }
        require(aesKey.size == 32) { "Encrypted capture session key has an invalid size" }

        val aad = aad(requireNotNull(payload.captureSessionId), requireNotNull(payload.captureKeyId))
        val clear = try {
            val aes = Cipher.getInstance(AES_TRANSFORMATION)
            aes.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(128, iv))
            aes.updateAAD(aad.toByteArray(StandardCharsets.UTF_8))
            aes.doFinal(ciphertext)
        } catch (error: GeneralSecurityException) {
            throw IllegalArgumentException(
                "Browser secure handoff payload authentication failed. Capture the page again with the current XPI.",
                error,
            )
        }
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
        require(nowEpochMs >= createdAt - CLOCK_SKEW_MS && nowEpochMs <= expiresAt) { "Browser capture session expired; capture the page again" }

        val pageUrl = ExternalUrlPolicy.normalizedUrl(json.optString("pageUrl").takeIf(String::isNotBlank))
        val title = json.optString("title").sanitizeText(240).ifBlank { "Browser capture" }
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
                val requestFingerprint = item.optString("requestFingerprint").safeToken(96)
                    ?: "legacy-" + "$sessionId|$candidateRevision|$index|$url"
                        .toByteArray(StandardCharsets.UTF_8).sha256Hex().take(32)
                add(
                    Candidate(
                        url = url,
                        pageUrl = candidatePage,
                        frameUrl = frameUrl,
                        title = item.optString("title").sanitizeText(240).takeIf(String::isNotBlank) ?: title,
                        mimeType = item.optString("contentType").sanitizeMime(),
                        contentLength = item.optLong("contentLength", 0L).takeIf { it > 0L },
                        stableMediaId = stableId,
                        requestFingerprint = requestFingerprint,
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
        }.distinctBy(Candidate::requestFingerprint)
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

    private val oaepDigest: String
        get() = if (Build.VERSION.SDK_INT >= 35) KeyProperties.DIGEST_SHA256 else KeyProperties.DIGEST_SHA1

    private val mgf1ParameterSpec: MGF1ParameterSpec
        get() = if (Build.VERSION.SDK_INT >= 35) MGF1ParameterSpec.SHA256 else MGF1ParameterSpec.SHA1

    private val keyAlias: String
        get() = if (Build.VERSION.SDK_INT >= 35) KEY_ALIAS_SHA256 else KEY_ALIAS_SHA1

    private fun publicKey() = keyStore().getCertificate(keyAlias)?.publicKey ?: generateKeyPair().public
    private fun privateKey() = (keyStore().getKey(keyAlias, null) as? java.security.PrivateKey) ?: generateKeyPair().private

    private fun generateKeyPair(): java.security.KeyPair {
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEYSTORE)
        val builder = KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(3072)
            .setDigests(oaepDigest)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
            .setUserAuthenticationRequired(false)
        if (Build.VERSION.SDK_INT >= 35) {
            builder.setMgf1Digests(KeyProperties.DIGEST_SHA256)
        }
        generator.initialize(builder.build())
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
        private const val KEY_ALIAS_SHA1 = "xdm_browser_capture_v3_rsa_sha1"
        private const val KEY_ALIAS_SHA256 = "xdm_browser_capture_v3_rsa_sha256"
        private const val RSA_TRANSFORMATION = "RSA/ECB/OAEPPadding"
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
