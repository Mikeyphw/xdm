package com.mikeyphw.xdm.android

/** Stable subsections inside the single Activity top-level destination. */
enum class ActivityPanel(val label: String) {
    /** Legacy value mapped to the primary Needs action panel. */
    Overview("Needs action"),
    Timeline("Recent"),
    Attention("Needs action"),
    Decisions("Queue holds"),
    Queues("Queues"),
    Schedule("Schedules"),
    Recovery("Recovery"),
    /** Legacy diagnostics entry now maps to Settings > Developer Center. */
    Diagnostics("Developer Center"),
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
