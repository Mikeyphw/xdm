package com.mikeyphw.xdm.android.scheduler

object TransferLaunchPolicy {
    /**
     * User-visible transfers use XDM's dataSync foreground service on every supported Android
     * release. UIDT remains available as an execution primitive, but it is not the primary path:
     * field testing on Android 16 showed a scheduled/running UIDT with no drawer download
     * notification. The FGS owns one stable ongoing notification and falls back to WorkManager
     * when Android rejects a background FGS start.
     */
    fun select(sdkInt: Int, userVisible: Boolean): TransferLaunchMode = when {
        userVisible -> TransferLaunchMode.ForegroundService
        else -> TransferLaunchMode.WorkManager
    }
}
