package com.mikeyphw.xdm.android.util

import org.junit.Assert.assertEquals
import org.junit.Test

class SafeTextTest {
    @Test fun fileNamesAreSanitizedWithoutRuntimeRegexPatterns() {
        assertEquals("bad_name_.zip", sanitizeFileName("bad/name\u0001.zip"))
        assertEquals("download.bin", sanitizeFileName(".   ."))
        assertEquals("pipe_name.txt", sanitizeFileName("pipe|name.txt"))
    }

    @Test fun notificationsDoNotExposeInternalRegexExceptions() {
        val fallback = "Download could not continue. Open XDM for details."
        val raw = "java.util.regex.PatternSyntaxException: Unknown character property name {Cntrl} near index 15"
        assertEquals(fallback, sanitizeNotificationText(raw, fallback))
    }
}
