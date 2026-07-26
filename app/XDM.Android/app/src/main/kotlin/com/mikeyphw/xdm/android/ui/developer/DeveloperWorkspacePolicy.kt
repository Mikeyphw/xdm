package com.mikeyphw.xdm.android

/**
 * A small, pure gate around the expensive developer workspace.
 *
 * Normal Media, Library, Activity, and Settings composition must never construct the
 * diagnostic planners. They are reachable only when the persisted developer switch is
 * enabled and the developer panel is the active Settings destination.
 */
internal object DeveloperWorkspacePolicy {
    fun shouldCompose(
        developerOptionsEnabled: Boolean,
        settingsPanel: SettingsPanel,
    ): Boolean = developerOptionsEnabled && settingsPanel == SettingsPanel.DeveloperTools
}
