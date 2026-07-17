package com.mikeyphw.xdm.android.transfer.aria2

import android.content.Context
import android.os.Build
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

class AndroidAria2CapabilityProbe(
    private val context: Context,
    private val sessionStore: Aria2SessionStore,
    private val binaryName: String = ARIA2_PACKAGED_BINARY_NAME,
) : Aria2CapabilityProbe {
    override fun probe(): Aria2CapabilityReport {
        if (ARIA2_PRIMARY_ABI !in Build.SUPPORTED_ABIS) {
            return Aria2CapabilityReport(
                Aria2Availability.UnsupportedAbi,
                "Embedded aria2 currently requires an ARM64 device.",
            )
        }
        val nativeDirectory = context.applicationInfo.nativeLibraryDir
            ?.takeIf(String::isNotBlank)
            ?.let(::File)
            ?: return Aria2CapabilityReport(
                Aria2Availability.NativeLibraryDirectoryMissing,
                "Android did not expose an executable native-library directory.",
            )
        val binary = File(nativeDirectory, binaryName)
        if (!binary.isFile) {
            return Aria2CapabilityReport(
                Aria2Availability.BinaryMissing,
                "The ARM64 aria2 runtime is not included in this installation.",
            )
        }
        if (!binary.hasArm64ElfHeader()) {
            return Aria2CapabilityReport(
                Aria2Availability.BinaryInvalid,
                "The packaged aria2 runtime is not a valid ARM64 ELF executable.",
            )
        }
        if (!binary.canExecute()) {
            return Aria2CapabilityReport(
                Aria2Availability.BinaryNotExecutable,
                "Android installed the aria2 runtime without execute permission.",
            )
        }
        if (runCatching { sessionStore.prepare() }.isFailure) {
            return Aria2CapabilityReport(
                Aria2Availability.RuntimeDirectoryUnavailable,
                "XDM cannot prepare its private aria2 session directory.",
            )
        }
        return Aria2CapabilityReport(
            availability = Aria2Availability.Available,
            summary = "ARM64 aria2 runtime is packaged and ready for an authenticated loopback probe.",
            binary = Aria2BinaryDescriptor(binary.canonicalFile, ARIA2_PRIMARY_ABI, binary.sha256()),
        )
    }
}

class AppPrivateAria2SecretProvider(context: Context) : Aria2SecretProvider {
    private val preferences = context.getSharedPreferences("aria2-runtime", Context.MODE_PRIVATE)
    private val random = SecureRandom()

    override fun getOrCreate(): Aria2RpcSecret = synchronized(this) {
        preferences.getString(KEY, null)
            ?.takeIf { it.length >= 32 }
            ?.let(Aria2RpcSecret::from)
            ?: ByteArray(32).also(random::nextBytes).let { bytes ->
                Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).also { encoded ->
                    check(preferences.edit().putString(KEY, encoded).commit()) {
                        "Unable to persist the app-private aria2 RPC secret"
                    }
                }.let(Aria2RpcSecret::from)
            }
    }

    private companion object {
        const val KEY = "rpc-secret-v1"
    }
}

class LoopbackAria2PortAllocator : Aria2PortAllocator {
    override fun allocate(): Int = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { socket ->
        socket.localPort.coerceAtLeast(1024)
    }
}

private fun File.hasArm64ElfHeader(): Boolean = runCatching {
    inputStream().use { stream ->
        val header = ByteArray(20)
        if (stream.read(header) != header.size) return@use false
        val elf = header[0] == 0x7f.toByte() && header[1] == 'E'.code.toByte() && header[2] == 'L'.code.toByte() && header[3] == 'F'.code.toByte()
        val is64Bit = header[4] == 2.toByte()
        val littleEndian = header[5] == 1.toByte()
        val machine = (header[18].toInt() and 0xff) or ((header[19].toInt() and 0xff) shl 8)
        elf && is64Bit && littleEndian && machine == 183
    }
}.getOrDefault(false)

private fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
