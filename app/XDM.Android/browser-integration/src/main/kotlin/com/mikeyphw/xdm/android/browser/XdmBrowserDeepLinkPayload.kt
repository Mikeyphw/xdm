package com.mikeyphw.xdm.android.browser

import com.mikeyphw.xdm.android.model.AutomationCommandAction
import com.mikeyphw.xdm.android.model.AutomationCommandDraft
import com.mikeyphw.xdm.android.model.AutomationCommandSource

/** Sanitized data extracted from an XDM browser custom-scheme link. */
data class XdmBrowserDeepLinkPayload(
    val version: Int,
    val action: AutomationCommandAction,
    val url: String,
    val pageUrl: String? = null,
    val pageTitle: String? = null,
    val fileName: String? = null,
    val mimeType: String? = null,
    val mediaKind: String? = null,
) {
    fun toAutomationCommandDraft(originPackage: String? = null): AutomationCommandDraft = AutomationCommandDraft(
        source = AutomationCommandSource.BrowserExtension,
        action = action,
        url = url,
        fileName = fileName,
        pageTitle = pageTitle,
        pageUrl = pageUrl,
        originPackage = originPackage,
        mimeType = mimeType,
    )
}
