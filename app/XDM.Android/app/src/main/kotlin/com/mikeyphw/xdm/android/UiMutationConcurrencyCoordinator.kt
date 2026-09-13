package com.mikeyphw.xdm.android

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns UI-originated mutation ordering that must survive coroutine scheduling differences.
 *
 * Tokens are allocated synchronously at user intent time. A slow earlier coroutine may finish,
 * but it cannot publish or persist over a later intent. Diagnostic gates are compare-and-set
 * latches so two clicks cannot both observe an idle MutableStateFlow before either coroutine runs.
 */
internal class UiMutationConcurrencyCoordinator {
    private val destinationIntent = AtomicLong(0L)

    val aria2Diagnostics = ExclusiveOperationGate()
    val ffmpegDiagnostics = ExclusiveOperationGate()
    val storageDoctor = ExclusiveOperationGate()

    fun nextDestinationIntent(): Long = destinationIntent.incrementAndGet()
    fun isCurrentDestinationIntent(token: Long): Boolean = destinationIntent.get() == token
}

internal class ExclusiveOperationGate {
    private val running = AtomicBoolean(false)

    fun tryAcquire(): Boolean = running.compareAndSet(false, true)
    fun release() { running.set(false) }
    fun isHeld(): Boolean = running.get()
}
