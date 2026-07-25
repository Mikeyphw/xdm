package com.mikeyphw.xdm.android

/** Stable subsections inside the single Activity top-level destination. */
enum class ActivityPanel(val label: String) {
    Overview("Overview"),
    Timeline("Timeline"),
    Attention("Attention"),
    Decisions("Decisions"),
    Queues("Queues"),
    Schedule("Schedule"),
    Recovery("Recovery"),
    Diagnostics("Diagnostics"),
}
