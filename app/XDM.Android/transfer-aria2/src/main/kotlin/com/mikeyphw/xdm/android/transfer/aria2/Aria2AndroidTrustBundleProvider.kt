package com.mikeyphw.xdm.android.transfer.aria2

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Base64

/**
 * Materializes Android's system trust store as an app-private PEM bundle for aria2/OpenSSL.
 * aria2 remains configured with certificate verification enabled; this file only bridges
 * AndroidCAStore into the filesystem form expected by the native runtime.
 */
fun interface Aria2TrustBundleProvider {
    fun ensure(): File
}

internal class Aria2AndroidTrustBundleProvider(context: Context) : Aria2TrustBundleProvider {
    private val outputFile = File(context.applicationContext.noBackupFilesDir, "aria2/trust/xdm-android-ca.pem")

    @Synchronized
    override fun ensure(): File {
        val store = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
        val aliases = store.aliases().asSequence().toList().sorted()
        require(aliases.isNotEmpty()) { "Android CA store is empty" }
        val encoder = Base64.getMimeEncoder(64, byteArrayOf('\n'.code.toByte()))
        val pem = buildString {
            aliases.forEach { alias ->
                val certificate = store.getCertificate(alias) as? X509Certificate ?: return@forEach
                append("-----BEGIN CERTIFICATE-----\n")
                append(encoder.encodeToString(certificate.encoded))
                append("\n-----END CERTIFICATE-----\n")
            }
        }
        require(pem.contains("-----BEGIN CERTIFICATE-----")) {
            "Android CA store contained no X.509 certificates"
        }
        outputFile.parentFile?.let { parent ->
            check(parent.isDirectory || parent.mkdirs()) { "Unable to create aria2 trust directory" }
            parent.restrictToOwner(directory = true)
        }
        val atomic = AtomicFile(outputFile)
        val output = atomic.startWrite()
        try {
            output.write(pem.toByteArray(Charsets.US_ASCII))
            output.fd.sync()
            atomic.finishWrite(output)
        } catch (error: Throwable) {
            atomic.failWrite(output)
            throw error
        }
        outputFile.restrictToOwner(directory = false)
        require(outputFile.isFile && outputFile.length() > 0L) { "Android CA trust bundle was not written" }
        return outputFile
    }

    private fun File.restrictToOwner(directory: Boolean) {
        setReadable(false, false)
        setWritable(false, false)
        setExecutable(false, false)
        setReadable(true, true)
        setWritable(true, true)
        if (directory) setExecutable(true, true)
    }
}
