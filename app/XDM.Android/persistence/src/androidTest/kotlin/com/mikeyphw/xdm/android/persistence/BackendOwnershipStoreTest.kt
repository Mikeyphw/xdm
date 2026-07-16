package com.mikeyphw.xdm.android.persistence

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mikeyphw.xdm.android.model.BackendArtifactIdentity
import com.mikeyphw.xdm.android.model.BackendOwnershipStatus
import com.mikeyphw.xdm.android.model.BackendReconciliationClassification
import com.mikeyphw.xdm.android.model.BackendRuntimeIdentity
import com.mikeyphw.xdm.android.model.BackendType
import com.mikeyphw.xdm.android.transfer.BackendReconciliationResult
import com.mikeyphw.xdm.android.transfer.OwnershipClaimResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackendOwnershipStoreTest {
    @Test
    fun destinationClaimIsExclusiveAndGenerationSafe() = runBlocking {
        withStore { store ->
            val nativeArtifacts = artifacts("native")
            val first = store.claim("first", "file:/downloads/a.bin", nativeArtifacts, BackendType.Native, runtime("native", "one"))
            assertTrue(first is OwnershipClaimResult.Claimed)
            val ownership = (first as OwnershipClaimResult.Claimed).ownership
            val conflict = store.claim("second", "file:/downloads/a.bin", artifacts("aria2"), BackendType.Aria2, runtime("aria2", "one"))
            assertTrue(conflict is OwnershipClaimResult.Conflict)
            val active = store.attachTask("first", ownership.generation, "native-task")
            assertEquals("native-task", active.backendTaskId)
            assertEquals(nativeArtifacts, active.artifacts)
            assertFalse(store.release("first", ownership.generation + 1))
            assertTrue(store.release("first", ownership.generation))
        }
    }

    @Test
    fun staleSessionMustBeReconciledBeforeAdoption() = runBlocking {
        withStore { store ->
            val artifactSet = artifacts("native")
            val claimed = store.claim("download", "file:/downloads/a.bin", artifactSet, BackendType.Native, runtime("native", "old"))
            val original = (claimed as OwnershipClaimResult.Claimed).ownership
            store.attachTask("download", original.generation, "old-task")

            val premature = store.adopt(
                "download",
                original.generation,
                original.destinationKey,
                artifactSet,
                BackendType.Native,
                runtime("native", "new"),
            )
            assertTrue(premature is OwnershipClaimResult.Conflict)

            store.markReconciling("download", original.generation)
            val reconciled = store.recordReconciliation(
                "download",
                original.generation,
                BackendReconciliationResult(
                    BackendReconciliationClassification.ResumableArtifact,
                    "Artifacts survived process death.",
                    safeToResume = true,
                ),
            )
            assertEquals(BackendOwnershipStatus.Reconciled, reconciled.status)

            val adopted = store.adopt(
                "download",
                original.generation,
                original.destinationKey,
                artifactSet,
                BackendType.Native,
                runtime("native", "new"),
            ) as OwnershipClaimResult.Claimed
            assertTrue(adopted.ownership.generation > original.generation)
            assertEquals("new", adopted.ownership.runtimeIdentity.sessionId)
            assertEquals(BackendReconciliationClassification.Pending, adopted.ownership.reconciliation)
        }
    }

    @Test
    fun conflictingArtifactIdentityCannotBeAdopted() = runBlocking {
        withStore { store ->
            val claimed = store.claim("download", "file:/downloads/a.bin", artifacts("native"), BackendType.Native, runtime("native", "old"))
            val original = (claimed as OwnershipClaimResult.Claimed).ownership
            store.markReconciling("download", original.generation)
            store.recordReconciliation(
                "download",
                original.generation,
                BackendReconciliationResult(
                    BackendReconciliationClassification.ResumableArtifact,
                    "Ready.",
                    safeToResume = true,
                ),
            )
            val result = store.adopt(
                "download",
                original.generation,
                original.destinationKey,
                artifacts("different"),
                BackendType.Native,
                runtime("native", "new"),
            )
            assertTrue(result is OwnershipClaimResult.Conflict)
        }
    }

    private suspend fun withStore(block: suspend (RoomBackendOwnershipStore) -> Unit) {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            block(RoomBackendOwnershipStore(database) { 100 })
        } finally {
            database.close()
        }
    }

    private fun artifacts(prefix: String) = BackendArtifactIdentity(
        format = "$prefix-v1",
        primary = "file:/downloads/a.bin.$prefix.part",
        companions = listOf("file:/downloads/a.bin.$prefix.checkpoint"),
    )

    private fun runtime(backend: String, session: String) = BackendRuntimeIdentity("$backend-instance", session)
}
