package com.mikeyphw.xdm.android.transfer.nativeengine

import java.util.ArrayDeque

/** Task-wide rolling throughput. Segment starts and retries never reset the byte baseline. */
internal class NativeRollingSpeedMeter(
    initialBytes: Long,
    initialTimeMillis: Long,
    private val windowMillis: Long = 1_500L,
) {
    private data class Sample(val timeMillis: Long, val bytes: Long)
    private val samples = ArrayDeque<Sample>().apply { addLast(Sample(initialTimeMillis, initialBytes)) }

    fun record(totalBytes: Long, nowMillis: Long): Long {
        val now = nowMillis.coerceAtLeast(samples.last().timeMillis)
        val bytes = totalBytes.coerceAtLeast(samples.last().bytes)
        samples.addLast(Sample(now, bytes))
        while (samples.size > 2 && now - samples.elementAt(1).timeMillis >= windowMillis) {
            samples.removeFirst()
        }
        val first = samples.first()
        val elapsed = now - first.timeMillis
        if (elapsed <= 0L) return 0L
        return ((bytes - first.bytes).coerceAtLeast(0L) * 1000L) / elapsed
    }
}
