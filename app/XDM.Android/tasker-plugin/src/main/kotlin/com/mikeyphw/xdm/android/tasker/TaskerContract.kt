package com.mikeyphw.xdm.android.tasker

import com.mikeyphw.xdm.android.model.AutomationCommandAction
import com.mikeyphw.xdm.android.model.AutomationCommandDraft
import com.mikeyphw.xdm.android.model.AutomationCommandSource

object TaskerContract {
    const val AddUrlAction = "com.mikeyphw.xdm.android.ADD_URL"
    const val CaptureMediaAction = "com.mikeyphw.xdm.android.CAPTURE_MEDIA"
    const val PauseAllAction = "com.mikeyphw.xdm.android.PAUSE_ALL"
    const val ResumeAllAction = "com.mikeyphw.xdm.android.RESUME_ALL"

    const val ExtraUrl = "com.mikeyphw.xdm.android.extra.URL"
    const val ExtraFileName = "com.mikeyphw.xdm.android.extra.FILE_NAME"
    const val ExtraPageTitle = "com.mikeyphw.xdm.android.extra.PAGE_TITLE"
    const val ExtraPageUrl = "com.mikeyphw.xdm.android.extra.PAGE_URL"
    const val ExtraIdempotencyKey = "com.mikeyphw.xdm.android.extra.IDEMPOTENCY_KEY"

    fun draftFor(
        actionName: String?,
        url: String?,
        fileName: String? = null,
        pageTitle: String? = null,
        pageUrl: String? = null,
        idempotencyKey: String? = null,
    ): AutomationCommandDraft? {
        val action = when (actionName) {
            AddUrlAction -> AutomationCommandAction.EnqueueDownload
            CaptureMediaAction -> AutomationCommandAction.CaptureMedia
            PauseAllAction -> AutomationCommandAction.PauseAll
            ResumeAllAction -> AutomationCommandAction.ResumeAll
            else -> return null
        }
        return AutomationCommandDraft(
            source = AutomationCommandSource.Tasker,
            action = action,
            url = url,
            fileName = fileName,
            pageTitle = pageTitle,
            pageUrl = pageUrl,
            explicitIdempotencyKey = idempotencyKey,
        )
    }
}
