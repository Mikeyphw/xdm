package com.mikeyphw.xdm.android

import org.junit.Assert.assertEquals
import org.junit.Test

class XdmWindowClassTest {
    @Test
    fun explicitBreakpointsMatchTheAdaptiveShellContract() {
        assertEquals(XdmWindowClass.Compact, XdmWindowClass.fromWidthDp(0f))
        assertEquals(XdmWindowClass.Compact, XdmWindowClass.fromWidthDp(599.99f))
        assertEquals(XdmWindowClass.Medium, XdmWindowClass.fromWidthDp(600f))
        assertEquals(XdmWindowClass.Medium, XdmWindowClass.fromWidthDp(839.99f))
        assertEquals(XdmWindowClass.Expanded, XdmWindowClass.fromWidthDp(840f))
        assertEquals(XdmWindowClass.Expanded, XdmWindowClass.fromWidthDp(1600f))
    }

    @Test
    fun onlyExpandedUsesThePersistentSidebar() {
        assertEquals(true, XdmWindowClass.Compact.usesBottomNavigation)
        assertEquals(true, XdmWindowClass.Medium.usesBottomNavigation)
        assertEquals(false, XdmWindowClass.Expanded.usesBottomNavigation)
        assertEquals(false, XdmWindowClass.Compact.usesNavigationSidebar)
        assertEquals(false, XdmWindowClass.Medium.usesNavigationSidebar)
        assertEquals(true, XdmWindowClass.Expanded.usesNavigationSidebar)
    }
}
