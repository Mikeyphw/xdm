package com.mikeyphw.xdm.android.transfer.aria2

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class Aria2EventPoller(
    private val processManager: Aria2ProcessManager,
    private val pollIntervalMillis: Long = 750,
) : Aria2TaskEventSource {
    override fun observe(gid: String): Flow<Aria2TaskStatus> = flow {
        var last: Aria2TaskStatus? = null
        while (true) {
            val status = processManager.rpc().tellStatus(gid)
            if (status != last) emit(status)
            last = status
            if (status.status in TERMINAL) return@flow
            delay(pollIntervalMillis)
        }
    }

    private companion object {
        val TERMINAL = setOf(Aria2TaskStatusValue.Complete, Aria2TaskStatusValue.Error, Aria2TaskStatusValue.Removed)
    }
}
