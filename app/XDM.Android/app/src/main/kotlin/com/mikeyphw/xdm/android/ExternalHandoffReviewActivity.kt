package com.mikeyphw.xdm.android

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.mikeyphw.xdm.android.model.AutomationCommandAction
import com.mikeyphw.xdm.android.model.AutomationCommandDraft
import com.mikeyphw.xdm.android.model.AutomationRejectionReason
import com.mikeyphw.xdm.android.model.ExternalCommandAuthorization
import com.mikeyphw.xdm.android.model.ExternalUrlPolicy
import com.mikeyphw.xdm.android.browser.XdmBrowserDeepLinkParser
import com.mikeyphw.xdm.android.browser.XdmBrowserDeepLinkParseResult
import com.mikeyphw.xdm.android.browser.XdmBrowserDeepLinkPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Exported intake boundary for browser, share-sheet, and generic VIEW handoffs.
 * Direct browser media captures route immediately into the internal Media intake path; generic
 * external commands and legacy encrypted capture envelopes retain their existing review boundary.
 */
open class ExternalHandoffReviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val deepLink = XdmBrowserDeepLinkParser.parseDetailed(intent.dataString, BuildConfig.XDM_BROWSER_SCHEME)
        if (deepLink is XdmBrowserDeepLinkParseResult.Accepted && deepLink.payload.hasEncryptedCaptureEnvelope) {
            reviewEncryptedBrowserCapture(deepLink.payload)
            return
        }
        val intake = ExternalIntentDraftFactory.generalIntake(this, intent)
        if (intake.rejectedDraft != null) {
            rejectAndFinish(
                intake.rejectedDraft,
                intake.rejectionReason ?: AutomationRejectionReason.UnsupportedUrl,
                intake.rejectionMessage ?: "External handoff was rejected before review",
            )
            return
        }
        val drafts = intake.drafts
        if (drafts.isEmpty()) {
            finish()
            return
        }
        val draft = drafts.first()
        AlertDialog.Builder(this)
            .setTitle(if (drafts.size > 1) "Open ${drafts.size} links in XDM" else "Open in XDM")
            .setMessage(ExternalIntentDraftFactory.displaySummary(draft, drafts.size))
            .setNegativeButton("Cancel") { _, _ -> drafts.forEach { rejectAndFinish(it) } }
            .setPositiveButton("Continue") { _, _ ->
                dispatchAll(drafts.map {
                    it.approvedForDispatch(
                        authorization = ExternalCommandAuthorization.UserConfirmed,
                        privateNetworkApproved = it.normalizedUrl != null && ExternalUrlPolicy.requiresPrivateNetworkApproval(it.normalizedUrl),
                        cleartextCredentialsApproved = false,
                    )
                })
            }
            .setOnCancelListener { drafts.forEach { rejectAndFinish(it) } }
            .show()
    }


    private fun reviewEncryptedBrowserCapture(payload: XdmBrowserDeepLinkPayload) {
        val sessionLabel = payload.captureSessionId.orEmpty().take(32)
        AlertDialog.Builder(this)
            .setTitle("Import encrypted browser capture")
            .setMessage(
                buildString {
                    append("An encrypted browser media-capture handoff is ready for review")
                    if (sessionLabel.isNotBlank()) append(" ($sessionLabel)")
                    append(". XDM will decrypt it only after you continue; no media URL or request headers are exposed in this review screen.")
                },
            )
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setPositiveButton("Continue") { _, _ ->
                lifecycleScope.launch {
                    val sessionId = runCatching {
                        withContext(Dispatchers.IO) {
                            val journal = (application as XdmApplication).container.browserCaptureImportJournal
                            journal.begin(payload)
                            payload.captureSessionId
                        }
                    }.getOrElse {
                        finish()
                        return@launch
                    }
                    startActivity(
                        Intent(this@ExternalHandoffReviewActivity, MainActivity::class.java)
                            .setAction(MainActivity.ACTION_INTERNAL_BROWSER_CAPTURE_IMPORT)
                            .putExtra(MainActivity.EXTRA_INTERNAL_BROWSER_CAPTURE_SESSION_ID, sessionId)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    )
                    finish()
                }
            }
            .setOnCancelListener { finish() }
            .show()
    }

    protected fun dispatch(draft: AutomationCommandDraft) = dispatchAll(listOf(draft))

    protected fun dispatchAll(drafts: List<AutomationCommandDraft>) {
        lifecycleScope.launch {
            val repository = (application as XdmApplication).container.repository
            val commandIds = withContext(Dispatchers.IO) { drafts.mapNotNull { ExternalAutomationDispatch.persist(repository, it) } }
            if (commandIds.isNotEmpty()) {
                startActivity(
                    Intent(this@ExternalHandoffReviewActivity, MainActivity::class.java)
                        .setAction(MainActivity.ACTION_INTERNAL_AUTOMATION_DISPATCH)
                        .putExtra(MainActivity.EXTRA_INTERNAL_COMMAND_ID, commandIds.first())
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                )
            }
            finish()
        }
    }

    private fun rejectAndFinish(
        draft: AutomationCommandDraft,
        reason: AutomationRejectionReason = AutomationRejectionReason.UserDeclined,
        message: String = "User declined external handoff",
    ) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                ExternalAutomationDispatch.persistRejected(
                    (application as XdmApplication).container.repository,
                    draft,
                    reason,
                    message,
                )
            }
            finish()
        }
    }
}

private fun AutomationCommandAction.requiresUrl(): Boolean = when (this) {
    AutomationCommandAction.PauseAll,
    AutomationCommandAction.ResumeAll,
    -> false
    else -> true
}
