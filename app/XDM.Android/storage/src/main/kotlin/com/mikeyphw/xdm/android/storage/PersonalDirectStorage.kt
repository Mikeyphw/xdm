package com.mikeyphw.xdm.android.storage

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import java.io.File
import java.net.URI

object PersonalDirectStorage {
    fun isGranted(context: Context): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Environment.isExternalStorageManager()
        else -> context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    fun permissionIntent(context: Context): Intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val appSpecific = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )
        if (appSpecific.resolveActivity(context.packageManager) != null) {
            appSpecific
        } else {
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        }
    } else {
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        )
    }

    @Suppress("DEPRECATION")
    fun downloadsDirectory(): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        "XDM",
    )

    @Suppress("DEPRECATION")
    fun customDirectoryUri(path: String): String {
        val trimmed = path.trim()
        val rawDirectory = File(trimmed)
        require(trimmed.isNotBlank() && rawDirectory.isAbsolute) { "Enter an absolute shared-storage path." }
        val sharedRoot = Environment.getExternalStorageDirectory().canonicalFile
        val directory = rawDirectory.canonicalFile
        require(directory.toPath().startsWith(sharedRoot.toPath())) { "Direct folders must stay inside shared storage (${sharedRoot.absolutePath})." }
        val relative = directory.relativeTo(sharedRoot).invariantSeparatorsPath.lowercase()
        require(relative != "android/data" && !relative.startsWith("android/data/") && relative != "android/obb" && !relative.startsWith("android/obb/")) {
            "Android does not grant broad access to other apps' Android/data or Android/obb trees."
        }
        return directory.toURI().toString().trimEnd('/') + "/"
    }


    @Suppress("DEPRECATION")
    fun directoryForDestination(destinationUri: String): File? {
        if (destinationUri == DestinationUris.DIRECT_DOWNLOADS) return downloadsDirectory().canonicalFile
        val uri = runCatching { URI(destinationUri) }.getOrNull() ?: return null
        if (!uri.scheme.equals("file", ignoreCase = true)) return null
        val directory = runCatching { File(uri).canonicalFile }.getOrNull() ?: return null
        val sharedRoot = Environment.getExternalStorageDirectory().canonicalFile
        if (!directory.toPath().startsWith(sharedRoot.toPath())) return null
        val relative = directory.relativeTo(sharedRoot).invariantSeparatorsPath.lowercase()
        if (relative == "android/data" || relative.startsWith("android/data/") || relative == "android/obb" || relative.startsWith("android/obb/")) return null
        return if (destinationUri.endsWith('/')) directory else directory.parentFile
    }

    @Suppress("DEPRECATION")
    fun requiresAllFilesAccess(destinationUri: String): Boolean {
        if (destinationUri == DestinationUris.DIRECT_DOWNLOADS) return true
        val uri = runCatching { URI(destinationUri) }.getOrNull() ?: return false
        if (!uri.scheme.equals("file", ignoreCase = true)) return false
        val sharedRoot = Environment.getExternalStorageDirectory().canonicalFile.toPath()
        val target = runCatching { File(uri).canonicalFile.toPath() }.getOrNull() ?: return false
        return target.startsWith(sharedRoot)
    }
}
