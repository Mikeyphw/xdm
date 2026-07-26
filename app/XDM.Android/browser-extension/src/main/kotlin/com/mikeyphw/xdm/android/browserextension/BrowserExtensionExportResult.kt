package com.mikeyphw.xdm.android.browserextension

data class BrowserExtensionExportResult(
    val fileName: String,
    val byteCount: Long,
    val sha256: String,
    val archiveBytes: ByteArray,
)
