package com.mikeyphw.xdm.android.persistence

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mikeyphw.xdm.android.model.BackendType
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
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val store = RoomBackendOwnershipStore(database) { 100 }
            val first = store.claim("first", "file:/downloads/a.bin", "native-partial", BackendType.Native)
            assertTrue(first is OwnershipClaimResult.Claimed)
            val ownership = (first as OwnershipClaimResult.Claimed).ownership
            val conflict = store.claim("second", "file:/downloads/a.bin", "aria2-partial", BackendType.Aria2)
            assertTrue(conflict is OwnershipClaimResult.Conflict)
            val active = store.attachTask("first", ownership.generation, "native-task")
            assertEquals("native-task", active.backendTaskId)
            assertFalse(store.release("first", ownership.generation + 1))
            assertTrue(store.release("first", ownership.generation))
        } finally {
            database.close()
        }
    }
}
