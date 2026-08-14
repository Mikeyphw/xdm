package com.mikeyphw.xdm.android.scheduler

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-local stale-callback guard layered on top of the durable Room queue claim.
 *
 * Room remains the authority for whether a transfer may start. This registry only serializes
 * Android execution-owner callbacks inside one process so an old Worker/FGS/UIDT stop callback
 * cannot pause a replacement owner that already claimed the same download.
 */
internal object AndroidExecutionClaimRegistry {
    private data class Claim(val queueClaimToken: Long, val attemptGeneration: Long? = null)

    private val claims = ConcurrentHashMap<String, Claim>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    private fun lockFor(downloadId: String): Mutex = locks.computeIfAbsent(downloadId) { Mutex() }

    suspend fun install(downloadId: String, queueClaimToken: Long) {
        require(queueClaimToken > 0L) { "Android execution ownership requires a positive durable queue claim token" }
        lockFor(downloadId).withLock {
            val current = claims[downloadId]
            claims[downloadId] = if (current?.queueClaimToken == queueClaimToken) current else Claim(queueClaimToken)
        }
    }

    suspend fun bindAttemptGeneration(downloadId: String, queueClaimToken: Long, attemptGeneration: Long) {
        if (queueClaimToken <= 0L || attemptGeneration <= 0L) return
        lockFor(downloadId).withLock {
            val current = claims[downloadId]?.takeIf { it.queueClaimToken == queueClaimToken } ?: return@withLock
            claims[downloadId] = current.copy(attemptGeneration = attemptGeneration)
        }
    }

    fun attemptGeneration(downloadId: String, queueClaimToken: Long): Long? =
        claims[downloadId]?.takeIf { it.queueClaimToken == queueClaimToken }?.attemptGeneration

    suspend fun release(downloadId: String, queueClaimToken: Long) {
        lockFor(downloadId).withLock {
            if (claims[downloadId]?.queueClaimToken == queueClaimToken) claims.remove(downloadId)
        }
    }

    suspend fun <T> withCurrentClaim(
        downloadId: String,
        queueClaimToken: Long,
        block: suspend () -> T,
    ): T? = lockFor(downloadId).withLock {
        if (queueClaimToken <= 0L || claims[downloadId]?.queueClaimToken != queueClaimToken) null else block()
    }
}
