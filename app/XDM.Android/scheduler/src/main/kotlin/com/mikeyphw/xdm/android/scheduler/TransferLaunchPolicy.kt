package com.mikeyphw.xdm.android.scheduler

object TransferLaunchPolicy {
    fun select(sdkInt: Int, userVisible: Boolean): TransferLaunchMode =
        if (sdkInt >= 34 && userVisible) TransferLaunchMode.UserInitiatedJob else TransferLaunchMode.ForegroundService
}
