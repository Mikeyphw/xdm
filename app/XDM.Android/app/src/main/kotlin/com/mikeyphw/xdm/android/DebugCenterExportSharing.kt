package com.mikeyphw.xdm.android

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

fun shareDebugCenterZipExport(
    context: Context,
    zip: File,
    subject: String,
    reportText: String,
) {
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
            putExtra(Intent.EXTRA_TEXT, reportText)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share XDM debug ZIP"))
    }.onFailure {
        shareTextReport(
            context = context,
            title = subject,
            value = "Debug ZIP saved in private app storage:\n${zip.absolutePath}\n\n" + reportText,
        )
    }
}
