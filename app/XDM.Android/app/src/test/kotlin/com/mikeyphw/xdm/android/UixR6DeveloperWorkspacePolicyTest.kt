package com.mikeyphw.xdm.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UixR6DeveloperWorkspacePolicyTest {
    @Test
    fun expensiveDeveloperWorkspaceRequiresBothThePersistedGateAndActivePanel() {
        SettingsPanel.entries.filterNot { it == SettingsPanel.DeveloperTools }.forEach { panel ->
            assertFalse(DeveloperWorkspacePolicy.shouldCompose(true, panel))
        }
        assertFalse(DeveloperWorkspacePolicy.shouldCompose(false, SettingsPanel.DeveloperTools))
        assertTrue(DeveloperWorkspacePolicy.shouldCompose(true, SettingsPanel.DeveloperTools))
    }
}
