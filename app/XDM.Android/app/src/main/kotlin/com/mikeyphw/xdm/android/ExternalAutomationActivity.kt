package com.mikeyphw.xdm.android

import android.app.AlertDialog
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.mikeyphw.xdm.android.model.ExternalCommandAuthorization
import com.mikeyphw.xdm.android.model.ExternalUrlPolicy
import com.mikeyphw.xdm.android.tasker.TaskerContract

/** Exported Tasker/integration surface. A valid user-created secret permits public actions without
 * a dialog; untrusted actions and every private-network target remain user-mediated. */
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
        if (tokenValid && !privateTarget) {
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
            .setTitle(if (tokenValid) "Approve local-network action" else "Approve external automation")
            .setMessage(ExternalIntentDraftFactory.displaySummary(draft))
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setPositiveButton("Continue") { _, _ ->
                dispatch(
                    draft.approvedForDispatch(
                        authorization = if (tokenValid) ExternalCommandAuthorization.IntegrationToken else ExternalCommandAuthorization.UserConfirmed,
                        privateNetworkApproved = privateTarget,
                        cleartextCredentialsApproved = false,
                        verifiedIntegrationId = if (tokenValid) "external-automation-token-v1" else null,
                    ),
                )
            }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun dispatch(draft: com.mikeyphw.xdm.android.model.AutomationCommandDraft) {
        val nonce = InternalAutomationDispatchStore.issue(draft)
        startActivity(
            android.content.Intent(this, MainActivity::class.java)
                .setAction(MainActivity.ACTION_INTERNAL_AUTOMATION_DISPATCH)
                .putExtra(MainActivity.EXTRA_INTERNAL_DISPATCH_NONCE, nonce)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }
}
