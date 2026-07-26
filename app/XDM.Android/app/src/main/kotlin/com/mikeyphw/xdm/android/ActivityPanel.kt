package com.mikeyphw.xdm.android

/** Stable subsections inside the single Activity top-level destination. */
enum class ActivityPanel(val label: String) {
    /** Legacy value mapped to Needs attention. */
    Overview("Needs attention"),
    Timeline("Recent"),
    Attention("Needs attention"),
    Decisions("Queue decisions"),
    Queues("Queues"),
    Schedule("Schedules"),
    Recovery("Recovery"),
    /** Legacy diagnostics entry now maps to Settings > Developer tools. */
    Diagnostics("Developer tools"),
    ;

    val isPrimary: Boolean
        get() = this == Attention || this == Timeline || this == Overview

    val isManage: Boolean
        get() = this == Decisions || this == Queues || this == Schedule || this == Recovery

    fun normalized(developerOptionsEnabled: Boolean): ActivityPanel = when (this) {
        Overview -> Attention
        Diagnostics -> if (developerOptionsEnabled) Diagnostics else Attention
        else -> this
    }

    companion object {
        val primaryPanels = listOf(Attention, Timeline)
        val managePanels = listOf(Decisions, Queues, Schedule, Recovery)
    }
}
