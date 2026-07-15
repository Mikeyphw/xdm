package com.mikeyphw.xdm.android.util

import java.util.Locale

fun Long.formatBytes(): String {
    if (this < 1024) return "$this B"
    val units = arrayOf("KiB", "MiB", "GiB", "TiB")
    var value = toDouble()
    var index = -1
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    return String.format(Locale.ROOT, "%.1f %s", value, units[index])
}

fun Long.formatSpeed(): String = "${formatBytes()}/s"
