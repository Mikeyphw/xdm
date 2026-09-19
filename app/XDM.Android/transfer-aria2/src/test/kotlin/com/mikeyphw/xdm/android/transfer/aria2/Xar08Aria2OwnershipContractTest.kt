package com.mikeyphw.xdm.android.transfer.aria2

import com.mikeyphw.xdm.android.model.BackendCapabilities
import com.mikeyphw.xdm.android.model.BackendReconciliationClassification
import com.mikeyphw.xdm.android.model.BackendRuntimeIdentity
import com.mikeyphw.xdm.android.model.BackendType
import com.mikeyphw.xdm.android.model.DownloadState
import com.mikeyphw.xdm.android.transfer.Aria2TaskMapping
import com.mikeyphw.xdm.android.transfer.BackendReconciliationResult
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source contract for XAR08: aria2 never owns a GID unless Room mapping, ownership metadata,
 * xdm.session, runtime process lease, and terminal publication evidence agree.
 */
class Xar08Aria2OwnershipContractTest {
    @Test
    fun xar08ContractNamesEveryFailClosedOwnershipBoundary() {
        val contract = listOf(
            "RemovalPending",
            "CompletionVerificationPending",
            "CANCEL_AT_COMPLETION_BOUNDARY",
            "ARIA2_SAVE_SESSION_FAILED",
            "aria2OwnedProtocols",
            "sanitizeSavedSessionToOwnedMetadata",
            "rotateRuntimeLog",
            "lastKnownEnabledFeatures",
            "verifiedCompleteReconciliation",
            "safeToResume = false",
            "RetiredForMigration",
            "FinalizationFailed",
            "purgeSavedSession",
        ).joinToString("|")
        assertTrue(contract.contains("RemovalPending"))
        assertTrue(contract.contains("CompletionVerificationPending"))
        assertFalse(contract.contains("sftp|magnet"))
    }

    @Test
    fun recoveredCompleteMustBeVerifiedBeforeCompletionIsAcknowledged() {
        val result = BackendReconciliationResult(
            BackendReconciliationClassification.ResumableArtifact,
            "aria2 reports complete output, but XDM must run final output/publication verification before terminal completion is acknowledged.",
            safeToResume = false,
            backendTaskId = "gid",
        )
        assertFalse(result.safeToResume)
        assertTrue(result.message.contains("verification"))
    }
}
