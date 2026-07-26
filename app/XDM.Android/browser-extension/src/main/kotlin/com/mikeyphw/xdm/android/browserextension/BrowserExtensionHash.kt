package com.mikeyphw.xdm.android.browserextension

import java.io.InputStream
import java.security.MessageDigest

object BrowserExtensionHash {
    fun sha256(bytes: ByteArray): String = digest(bytes.inputStream())

    fun digest(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}
