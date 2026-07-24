package com.mikeyphw.xdm.android

import android.content.Intent

class BrowserActivity : MainActivity() {
    override fun initialRoute(intent: Intent?): AppRoute? = AppRoute.Browser

    override fun shouldHandleExternalIntent(intent: Intent): Boolean = false
}
