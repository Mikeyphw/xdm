package com.mikeyphw.xdm.android

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.mikeyphw.xdm.android.model.DebugRedactor
import com.mikeyphw.xdm.android.model.DiagnosticExportIntegrity
import java.io.File

fun shareDebugCenterZipExport(
    context: Context,
    zip: File,
    subject: String,
    reportText: String,
) {
    val scan = DiagnosticExportIntegrity.scanZip(zip)
    if (!scan.safe) {
        shareTextReport(
            context = context,
            title = subject,
            value = "Debug ZIP export was blocked by the final privacy/integrity scanner.\n${scan.summary}",
        )
        return
    }
    val redactedPreview = reportText.lineSequence()
        .joinToString("\n") { DebugRedactor.redactExportLine(it) }
        .take(32_000)
    runCatching {
        val uri = FileProvider.getUriForFile(
            context,
            context.packageName + ".debugcenter.fileprovider",
            zip,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, redactedPreview)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share XDM debug ZIP"))
    }.onFailure {
        shareTextReport(
            context = context,
            title = subject,
            value = "Debug ZIP export could not be attached. Share this redacted text report instead.\n\n" + redactedPreview,
        )
    }
}
