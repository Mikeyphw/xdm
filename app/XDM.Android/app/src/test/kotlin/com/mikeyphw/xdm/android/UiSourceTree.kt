package com.mikeyphw.xdm.android

import java.io.File
import java.nio.file.Path

internal object UiSourceTree {
    private fun sourceRoot(root: File): File = File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android")

    fun files(root: File): List<File> = buildList {
        val sourceRoot = sourceRoot(root)
        add(File(sourceRoot, "Screens.kt"))
        val uiRoot = File(sourceRoot, "ui")
        if (uiRoot.isDirectory) {
            addAll(uiRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path })
        }
    }

    fun readAll(root: File): String = files(root).joinToString("\n") { it.readText() }
    fun readAll(root: Path): String = readAll(root.toFile())

    fun readUser(root: File): String = files(root)
        .filterNot { it.name == "Screens.kt" || it.invariantSeparatorsPath.contains("/ui/developer/") }
        .joinToString("\n") { it.readText() }

    fun readDeveloper(root: File): String = files(root)
        .filter { it.invariantSeparatorsPath.contains("/ui/developer/") }
        .joinToString("\n") { it.readText() }
}
