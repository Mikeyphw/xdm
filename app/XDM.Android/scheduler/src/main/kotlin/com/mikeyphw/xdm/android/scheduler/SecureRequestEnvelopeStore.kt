package com.mikeyphw.xdm.android.scheduler

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.mikeyphw.xdm.android.model.ExternalUrlPolicy
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

/** Sensitive browser/request material that must survive process death without entering Room,
 * diagnostics, backend sidecars, or user-visible copies in clear text. */
data class SecureRequestEnvelope(
    val subjectId: String,
    val exactUrl: String? = null,
    val boundHost: String? = null,
    val pageUrl: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val redactedSummary: String = "",
    val isExpiringUrl: Boolean = false,
    val expiresAtEpochMs: Long = Long.MAX_VALUE,
    val attemptGeneration: Long = 0L,
    val privateNetworkApproved: Boolean = false,
    val cleartextCredentialsApproved: Boolean = false,
    val cleanupActions: List<String> = emptyList(),
    val tempCookieFileName: String? = null,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
)

interface SecureRequestEnvelopeStore {
    fun put(envelope: SecureRequestEnvelope)
    fun get(subjectId: String, nowEpochMs: Long = System.currentTimeMillis()): SecureRequestEnvelope?
    fun delete(subjectId: String)
    fun deleteExpired(nowEpochMs: Long = System.currentTimeMillis())
}

class InMemorySecureRequestEnvelopeStore : SecureRequestEnvelopeStore {
    private val values = linkedMapOf<String, SecureRequestEnvelope>()

    @Synchronized
    override fun put(envelope: SecureRequestEnvelope) {
        require(envelope.subjectId.isNotBlank()) { "Secure request subject must not be blank" }
        require(envelope.boundHost == ExternalUrlPolicy.originHost(envelope.exactUrl)) {
            "Secure request envelope host binding does not match its exact URL"
        }
        values[envelope.subjectId] = envelope
    }

    @Synchronized
    override fun get(subjectId: String, nowEpochMs: Long): SecureRequestEnvelope? {
        val value = values[subjectId] ?: return null
        if (value.expiresAtEpochMs <= nowEpochMs) {
            values.remove(subjectId)
            return null
        }
        return value
    }

    @Synchronized
    override fun delete(subjectId: String) {
        values.remove(subjectId)
    }

    @Synchronized
    override fun deleteExpired(nowEpochMs: Long) {
        values.entries.removeAll { it.value.expiresAtEpochMs <= nowEpochMs }
    }
}

/** Android Keystore-backed envelope store. Ciphertext lives under noBackupFilesDir so raw
 * request credentials and signed URLs are excluded from cloud and device-transfer backup. */
class AndroidSecureRequestEnvelopeStore(
    context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
) : SecureRequestEnvelopeStore {
    private val root = File(context.noBackupFilesDir, "secure-request-envelopes-v1").apply { mkdirs() }
    private val lock = Any()

    override fun put(envelope: SecureRequestEnvelope) = synchronized(lock) {
        require(envelope.subjectId.isNotBlank()) { "Secure request subject must not be blank" }
        val exactHost = ExternalUrlPolicy.originHost(envelope.exactUrl)
        require(envelope.boundHost == exactHost) { "Secure request envelope host binding does not match its exact URL" }
        val plaintext = envelope.toJson().toString().toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        cipher.updateAAD(envelope.subjectId.toByteArray(Charsets.UTF_8))
        val payload = JSONObject()
            .put("version", FORMAT_VERSION)
            .put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .put("ciphertext", Base64.encodeToString(cipher.doFinal(plaintext), Base64.NO_WRAP))
            .put("expiresAtEpochMs", envelope.expiresAtEpochMs)
            .toString()
            .toByteArray(Charsets.UTF_8)
        atomicWrite(fileFor(envelope.subjectId), payload)
    }

    override fun get(subjectId: String, nowEpochMs: Long): SecureRequestEnvelope? = synchronized(lock) {
        if (subjectId.isBlank()) return@synchronized null
        val file = fileFor(subjectId)
        if (!file.isFile) return@synchronized null
        val envelope = runCatching {
            val payload = JSONObject(file.readText(Charsets.UTF_8))
            require(payload.optInt("version") == FORMAT_VERSION) { "Unsupported secure envelope format" }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val iv = Base64.decode(payload.getString("iv"), Base64.NO_WRAP)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(subjectId.toByteArray(Charsets.UTF_8))
            val plaintext = cipher.doFinal(Base64.decode(payload.getString("ciphertext"), Base64.NO_WRAP))
            secureRequestEnvelopeFromJson(JSONObject(String(plaintext, Charsets.UTF_8)))
        }.getOrElse {
            file.delete()
            return@synchronized null
        }
        val exactHost = ExternalUrlPolicy.originHost(envelope.exactUrl)
        if (
            envelope.subjectId != subjectId ||
            envelope.boundHost != exactHost ||
            envelope.expiresAtEpochMs <= nowEpochMs
        ) {
            file.delete()
            return@synchronized null
        }
        envelope
    }

    override fun delete(subjectId: String) = synchronized(lock) {
        if (subjectId.isNotBlank()) fileFor(subjectId).delete()
    }

    override fun deleteExpired(nowEpochMs: Long) = synchronized(lock) {
        root.listFiles { file -> file.isFile && file.name.endsWith(FILE_SUFFIX) }
            .orEmpty()
            .forEach { file ->
                val expired = runCatching {
                    JSONObject(file.readText(Charsets.UTF_8))
                        .optLong("expiresAtEpochMs", Long.MIN_VALUE) <= nowEpochMs
                }.getOrDefault(false)
                if (expired || nowEpochMs - file.lastModified() > MAX_FILE_AGE_MS) file.delete()
            }
    }

    private fun fileFor(subjectId: String): File = File(root, sha256(subjectId) + FILE_SUFFIX)

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        root.mkdirs()
        val temporary = File(root, target.name + ".tmp-" + randomSuffix())
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            runCatching {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            }.getOrElse {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }

    private fun randomSuffix(): String = ByteArray(8).also(SecureRandom()::nextBytes)
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "xdm.secure.request.envelope.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val FORMAT_VERSION = 1
        const val FILE_SUFFIX = ".xdm-secure"
        const val MAX_FILE_AGE_MS = 30L * 24L * 60L * 60L * 1000L
    }
}

private fun SecureRequestEnvelope.toJson(): JSONObject = JSONObject()
    .put("subjectId", subjectId)
    .put("exactUrl", exactUrl)
    .put("boundHost", boundHost)
    .put("pageUrl", pageUrl)
    .put("headers", JSONObject(headers))
    .put("redactedSummary", redactedSummary)
    .put("isExpiringUrl", isExpiringUrl)
    .put("expiresAtEpochMs", expiresAtEpochMs)
    .put("attemptGeneration", attemptGeneration)
    .put("privateNetworkApproved", privateNetworkApproved)
    .put("cleartextCredentialsApproved", cleartextCredentialsApproved)
    .put("cleanupActions", JSONArray(cleanupActions))
    .put("tempCookieFileName", tempCookieFileName)
    .put("createdAtEpochMs", createdAtEpochMs)

private fun secureRequestEnvelopeFromJson(json: JSONObject): SecureRequestEnvelope = SecureRequestEnvelope(
    subjectId = json.getString("subjectId"),
    exactUrl = json.optString("exactUrl").takeIf(String::isNotBlank),
    boundHost = json.optString("boundHost").takeIf(String::isNotBlank),
    pageUrl = json.optString("pageUrl").takeIf(String::isNotBlank),
    headers = json.optJSONObject("headers")?.let { objectJson ->
        objectJson.keys().asSequence().associateWith { key -> objectJson.optString(key) }
    }.orEmpty(),
    redactedSummary = json.optString("redactedSummary"),
    isExpiringUrl = json.optBoolean("isExpiringUrl"),
    expiresAtEpochMs = json.optLong("expiresAtEpochMs", Long.MAX_VALUE),
    attemptGeneration = json.optLong("attemptGeneration", 0L),
    privateNetworkApproved = json.optBoolean("privateNetworkApproved"),
    cleartextCredentialsApproved = json.optBoolean("cleartextCredentialsApproved"),
    cleanupActions = json.optJSONArray("cleanupActions")?.let { array ->
        (0 until array.length()).mapNotNull { index -> array.optString(index).takeIf(String::isNotBlank) }
    }.orEmpty(),
    tempCookieFileName = json.optString("tempCookieFileName").takeIf(String::isNotBlank),
    createdAtEpochMs = json.optLong("createdAtEpochMs", System.currentTimeMillis()),
)
