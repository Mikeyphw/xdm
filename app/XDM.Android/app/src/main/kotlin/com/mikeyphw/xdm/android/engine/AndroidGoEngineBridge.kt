package com.mikeyphw.xdm.android.engine

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Narrow JNI bridge for the Go-owned engine ABI.
 *
 * This layer owns native handles and byte-buffer lifetime only. Protocol/domain
 * errors remain encoded frames from Go; Kotlin maps only transport/ABI failures
 * into narrow bridge exceptions.
 */
class AndroidGoEngineBridge(
    private val native: NativeXdmCore = JniNativeXdmCore,
) : AutoCloseable {
    private var handle: Long = 0L
    private val closed = AtomicBoolean(false)

    fun create(config: ByteArray) {
        check(handle == 0L) { "engine already created" }
        handle = native.create(config)
    }

    fun command(message: ByteArray) {
        ensureOpen()
        native.command(handle, message)
    }

    fun nextFrame(timeoutMillis: Int): ByteArray? {
        ensureOpen()
        val buffer = native.nextFrame(handle, timeoutMillis) ?: return null
        return buffer.use { it.bytes() }
    }

    fun platformReply(message: ByteArray) {
        ensureOpen()
        native.platformReply(handle, message)
    }

    fun metadata(): ByteArray {
        ensureOpen()
        return native.metadata(handle).use { it.bytes() }
    }

    override fun close() {
        if (closed.compareAndSet(false, true) && handle != 0L) {
            native.shutdown(handle)
            handle = 0L
        }
    }

    private fun ensureOpen() {
        check(!closed.get() && handle != 0L) { "engine bridge is not open" }
    }
}

interface NativeXdmCore {
    fun create(config: ByteArray): Long
    fun command(handle: Long, message: ByteArray)
    fun nextFrame(handle: Long, timeoutMillis: Int): NativeBufferLease?
    fun platformReply(handle: Long, message: ByteArray)
    fun metadata(handle: Long): NativeBufferLease
    fun shutdown(handle: Long)
}

object JniNativeXdmCore : NativeXdmCore {
    private const val XDM_OK = 0
    private const val XDM_E_INVALID = 1
    private const val XDM_E_NOT_FOUND = 2
    private const val XDM_E_PROTOCOL = 3
    private const val XDM_E_TIMEOUT = 4
    private const val XDM_E_STOPPED = 5

    init {
        System.loadLibrary("xdmcore")
    }

    override fun create(config: ByteArray): Long {
        val outHandle = LongArray(1)
        requireStatus(nativeCreate(config, outHandle))
        return outHandle[0].takeIf { it != 0L } ?: throw BridgeProtocolException.NativeInternal()
    }

    override fun command(handle: Long, message: ByteArray) {
        requireStatus(nativeCommand(handle, message))
    }

    override fun nextFrame(handle: Long, timeoutMillis: Int): NativeBufferLease? {
        val result = nativeNextFrame(handle, timeoutMillis)
            ?: throw BridgeProtocolException.NativeInternal()
        if (result.status == XDM_E_TIMEOUT) {
            return null
        }
        requireStatus(result.status)
        val data = result.data ?: throw BridgeProtocolException.NativeInternal()
        return NativeBufferLease(data, result.token)
    }

    override fun platformReply(handle: Long, message: ByteArray) {
        requireStatus(nativePlatformReply(handle, message))
    }

    override fun metadata(handle: Long): NativeBufferLease {
        val result = nativeMetadata(handle) ?: throw BridgeProtocolException.NativeInternal()
        requireStatus(result.status)
        val data = result.data ?: throw BridgeProtocolException.NativeInternal()
        return NativeBufferLease(data, result.token)
    }

    override fun shutdown(handle: Long) {
        requireStatus(nativeShutdown(handle))
    }

    private fun requireStatus(status: Int) {
        when (status) {
            XDM_OK -> Unit
            XDM_E_INVALID -> throw BridgeProtocolException.InvalidBytes()
            XDM_E_NOT_FOUND, XDM_E_STOPPED -> throw BridgeProtocolException.NativeStopped()
            XDM_E_PROTOCOL -> throw BridgeProtocolException.ProtocolMismatch()
            XDM_E_TIMEOUT -> throw BridgeProtocolException.NativeTimeout()
            else -> throw BridgeProtocolException.NativeInternal()
        }
    }

    private external fun nativeCreate(config: ByteArray, outHandle: LongArray): Int
    private external fun nativeCommand(handle: Long, message: ByteArray): Int
    private external fun nativeNextFrame(handle: Long, timeoutMillis: Int): NativeCallBuffer?
    private external fun nativePlatformReply(handle: Long, message: ByteArray): Int
    private external fun nativeMetadata(handle: Long): NativeCallBuffer?
    private external fun nativeShutdown(handle: Long): Int
    external fun releaseBuffer(token: Long)
}

internal class NativeCallBuffer(
    val status: Int,
    val data: ByteBuffer?,
    val token: Long,
)

class NativeBufferLease internal constructor(
    private val data: ByteBuffer,
    private val token: Long,
    private val releaser: (Long) -> Unit = JniNativeXdmCore::releaseBuffer,
) : AutoCloseable {
    private val released = AtomicBoolean(false)

    fun bytes(): ByteArray {
        check(!released.get()) { "native buffer released" }
        val copy = ByteArray(data.remaining())
        data.asReadOnlyBuffer().get(copy)
        return copy
    }

    override fun close() {
        if (released.compareAndSet(false, true)) {
            releaser(token)
        }
    }
}

sealed class BridgeProtocolException(message: String) : RuntimeException(message) {
    class InvalidBytes : BridgeProtocolException("invalid native bytes")
    class ProtocolMismatch : BridgeProtocolException("native protocol mismatch")
    class NativeStopped : BridgeProtocolException("native engine stopped")
    class NativeTimeout : BridgeProtocolException("native call timed out")
    class NativeInternal : BridgeProtocolException("native bridge failure")
}
