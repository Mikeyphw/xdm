package com.mikeyphw.xdm.android.scheduler

object TransferLaunchPolicy {
    fun select(sdkInt: Int, userVisible: Boolean): TransferLaunchMode = when {
        sdkInt >= 34 && userVisible -> TransferLaunchMode.UserInitiatedJob
        userVisible -> TransferLaunchMode.ForegroundService
        else -> TransferLaunchMode.WorkManager
    }
}
