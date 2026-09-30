package com.mikeyphw.xdm.android.transfer.aria2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Aria2SavedSessionTest {
    @Test
    fun retainsWholeOwnedRecordAndDropsWholeForeignRecord() {
        val owned = "0123456789abcdef"
        val foreign = "fedcba9876543210"
        val input = """
            https://example.test/owned.iso
              gid=$owned
              dir=/data/user/0/xdm/files/aria2/staging
              out=owned.iso
              continue=true

            https://example.test/foreign.iso
              gid=$foreign
              dir=/data/user/0/xdm/files/aria2/staging
              out=foreign.iso
        """.trimIndent() + "\n"

        val result = Aria2SavedSession.retainOwned(input, setOf(owned))

        assertEquals(1, result.retainedRecords)
        assertEquals(1, result.removedRecords)
        assertTrue(result.text.contains("https://example.test/owned.iso"))
        assertTrue(result.text.contains("gid=$owned"))
        assertTrue(result.text.contains("dir=/data/user/0/xdm/files/aria2/staging"))
        assertTrue(result.text.contains("out=owned.iso"))
        assertTrue(result.text.contains("continue=true"))
        assertFalse(result.text.contains("foreign.iso"))
        assertFalse(result.text.contains(foreign))
    }

    @Test
    fun emptyOwnershipDropsRecordsWithoutLeavingOrphanOptionLines() {
        val input = """
            https://example.test/file.iso
              gid=0123456789abcdef
              out=file.iso
        """.trimIndent() + "\n"

        val result = Aria2SavedSession.retainOwned(input, emptySet())

        assertEquals(1, result.removedRecords)
        assertEquals(0, result.retainedRecords)
        assertEquals("", result.text)
    }

    @Test
    fun malformedOrGidlessBlocksAreDroppedAtomically() {
        val owned = "0123456789abcdef"
        val input = """
              gid=$owned
              out=orphan.iso

            https://example.test/no-gid.iso
              out=no-gid.iso

            https://example.test/owned.iso
              gid=$owned
              out=owned.iso
        """.trimIndent() + "\n"

        val result = Aria2SavedSession.retainOwned(input, setOf(owned))

        assertEquals(1, result.retainedRecords)
        assertEquals(2, result.removedRecords)
        assertTrue(result.text.startsWith("https://example.test/owned.iso\n"))
        assertTrue(result.text.contains("  gid=$owned"))
        assertFalse(result.text.contains("orphan.iso"))
        assertFalse(result.text.contains("no-gid.iso"))
    }


    @Test
    fun commentsDoNotSplitUriFromItsOwnedOptions() {
        val owned = "0123456789abcdef"
        val input = """
            # session comment
            https://example.test/owned.iso
            # comment between URI and options
              gid=$owned
              out=owned.iso
        """.trimIndent() + "\n"

        val result = Aria2SavedSession.retainOwned(input, setOf(owned))

        assertEquals(1, result.retainedRecords)
        assertEquals(0, result.removedRecords)
        assertTrue(result.text.contains("https://example.test/owned.iso"))
        assertTrue(result.text.contains("  gid=$owned"))
        assertFalse(result.text.contains("session comment"))
    }

    @Test
    fun ownershipMatchesOnlyTheGidOptionNotArbitraryText() {
        val owned = "0123456789abcdef"
        val input = """
            https://example.test/$owned.iso
              gid=fedcba9876543210
              out=foreign.iso
        """.trimIndent() + "\n"

        val result = Aria2SavedSession.retainOwned(input, setOf(owned))

        assertEquals("", result.text)
        assertEquals(1, result.removedRecords)
    }
}
