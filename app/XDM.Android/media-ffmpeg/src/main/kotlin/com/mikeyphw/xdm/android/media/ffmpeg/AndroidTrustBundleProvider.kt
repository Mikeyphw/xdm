package com.mikeyphw.xdm.android.media.ffmpeg

import android.content.Context
import java.io.File
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Base64

/**
 * Materializes Android's trusted CA store as an app-private PEM bundle for the embedded OpenSSL runtime.
 * FFmpeg is always invoked with tls_verify=1 for HTTPS inputs and with this bundle as ca_file.
 */
class AndroidTrustBundleProvider(context: Context) {
    private val appContext = context.applicationContext
    private val outputFile = File(appContext.noBackupFilesDir, "ffmpeg/trust/xdm-android-ca.pem")

    @Synchronized
    fun ensure(): Result<File> = runCatching {
        val store = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
        val encoder = Base64.getMimeEncoder(64, byteArrayOf('\n'.code.toByte()))
        val aliases = store.aliases().asSequence().toList().sorted()
        require(aliases.isNotEmpty()) { "Android CA store is empty" }
        val pem = buildString {
            aliases.forEach { alias ->
                val certificate = store.getCertificate(alias) as? X509Certificate ?: return@forEach
                append("-----BEGIN CERTIFICATE-----\n")
                append(encoder.encodeToString(certificate.encoded))
                append("\n-----END CERTIFICATE-----\n")
            }
        }
        require(pem.contains("-----BEGIN CERTIFICATE-----")) { "Android CA store contained no X.509 certificates" }
        outputFile.parentFile?.mkdirs()
        val temp = File(outputFile.parentFile, "${outputFile.name}.part")
        temp.writeText(pem, Charsets.US_ASCII)
        temp.setReadable(false, false)
        temp.setReadable(true, true)
        temp.setWritable(false, false)
        temp.setWritable(true, true)
        if (!temp.renameTo(outputFile)) {
            outputFile.delete()
            check(temp.renameTo(outputFile)) { "Could not publish Android CA trust bundle" }
        }
        outputFile.setReadable(false, false)
        outputFile.setReadable(true, true)
        outputFile.setWritable(false, false)
        outputFile.setWritable(true, true)
        require(outputFile.isFile && outputFile.length() > 0L) { "Android CA trust bundle was not written" }
        outputFile
    }
}
