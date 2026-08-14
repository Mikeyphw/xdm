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
import com.mikeyphw.xdm.android.tasker.TaskerContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Exported Tasker/integration surface with narrowly scoped token authority. */
class ExternalAutomationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val draft = ExternalIntentDraftFactory.tasker(this, intent)
        val suppliedSecret = intent.getStringExtra(TaskerContract.ExtraIntegrationSecret)
        intent.removeExtra(TaskerContract.ExtraIntegrationSecret)
        if (draft == null) {
            finish()
            return
        }
        val tokenValid = ExternalAutomationTrustStore(this).verify(suppliedSecret)
        val privateTarget = draft.normalizedUrl != null && ExternalUrlPolicy.requiresPrivateNetworkApproval(draft.normalizedUrl)
        val tokenMayAutoExecute = tokenValid && !privateTarget && draft.action == AutomationCommandAction.EnqueueDownload
        if (tokenMayAutoExecute) {
            dispatch(
                draft.approvedForDispatch(
                    authorization = ExternalCommandAuthorization.IntegrationToken,
                    privateNetworkApproved = false,
                    cleartextCredentialsApproved = false,
                    verifiedIntegrationId = "external-automation-token-v1",
                ),
            )
            return
        }
        AlertDialog.Builder(this)
            .setTitle(
                when {
                    privateTarget -> "Approve local-network action"
                    tokenValid -> "Confirm privileged automation action"
                    else -> "Approve external automation"
                },
            )
            .setMessage(ExternalIntentDraftFactory.displaySummary(draft))
            .setNegativeButton("Cancel") { _, _ -> rejectAndFinish(draft, tokenValid) }
            .setPositiveButton("Continue") { _, _ ->
                dispatch(
                    draft.approvedForDispatch(
                        authorization = ExternalCommandAuthorization.UserConfirmed,
                        privateNetworkApproved = privateTarget,
                        cleartextCredentialsApproved = false,
                        verifiedIntegrationId = if (tokenValid) "external-automation-token-v1" else null,
                    ),
                )
            }
            .setOnCancelListener { rejectAndFinish(draft, tokenValid) }
            .show()
    }

    private fun dispatch(draft: AutomationCommandDraft) {
        lifecycleScope.launch {
            val repository = (application as XdmApplication).container.repository
            val commandId = withContext(Dispatchers.IO) { ExternalAutomationDispatch.persist(repository, draft) }
            if (commandId != null) {
                startActivity(
                    Intent(this@ExternalAutomationActivity, MainActivity::class.java)
                        .setAction(MainActivity.ACTION_INTERNAL_AUTOMATION_DISPATCH)
                        .putExtra(MainActivity.EXTRA_INTERNAL_COMMAND_ID, commandId)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                )
            }
            finish()
        }
    }

    private fun rejectAndFinish(draft: AutomationCommandDraft, tokenValid: Boolean) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                ExternalAutomationDispatch.persistRejected(
                    (application as XdmApplication).container.repository,
                    draft.copy(verifiedIntegrationId = if (tokenValid) "external-automation-token-v1" else null),
                    AutomationRejectionReason.UserDeclined,
                    "User declined external automation",
                )
            }
            finish()
        }
    }
}
