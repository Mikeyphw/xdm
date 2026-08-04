package com.mikeyphw.xdm.android

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.mikeyphw.xdm.android.model.ExternalCommandAuthorization
import com.mikeyphw.xdm.android.model.ExternalUrlPolicy

/** Exported review-only surface for browser, share-sheet, and generic VIEW handoffs. */
open class ExternalHandoffReviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val draft = ExternalIntentDraftFactory.general(this, intent)
        if (draft == null || (draft.normalizedUrl == null && draft.action.requiresUrl())) {
            finish()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Open in XDM")
            .setMessage(ExternalIntentDraftFactory.displaySummary(draft))
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setPositiveButton("Continue") { _, _ ->
                val approved = draft.approvedForDispatch(
                    authorization = ExternalCommandAuthorization.UserConfirmed,
                    privateNetworkApproved = draft.normalizedUrl != null && ExternalUrlPolicy.requiresPrivateNetworkApproval(draft.normalizedUrl),
                    cleartextCredentialsApproved = false,
                )
                dispatch(approved)
            }
            .setOnCancelListener { finish() }
            .show()
    }

    protected fun dispatch(draft: com.mikeyphw.xdm.android.model.AutomationCommandDraft) {
        val nonce = InternalAutomationDispatchStore.issue(draft)
        startActivity(
            Intent(this, MainActivity::class.java)
                .setAction(MainActivity.ACTION_INTERNAL_AUTOMATION_DISPATCH)
                .putExtra(MainActivity.EXTRA_INTERNAL_DISPATCH_NONCE, nonce)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }
}

private fun com.mikeyphw.xdm.android.model.AutomationCommandAction.requiresUrl(): Boolean = when (this) {
    com.mikeyphw.xdm.android.model.AutomationCommandAction.PauseAll,
    com.mikeyphw.xdm.android.model.AutomationCommandAction.ResumeAll,
    -> false
    else -> true
}
