package com.mikeyphw.xdm.android.transfer.aria2

import java.util.Locale

/**
 * Parses aria2 input/session files as URI records plus their indented option lines.
 *
 * aria2's --save-session output is also valid --input-file syntax. A transfer record is
 * therefore an indivisible unit: one non-indented URI line followed by zero or more
 * indented option lines (including gid=...). Ownership filtering must never retain only
 * the gid line while dropping its URI/options.
 */
internal object Aria2SavedSession {
    data class SanitizeResult(
        val text: String,
        val removedRecords: Int,
        val retainedRecords: Int,
    )

    private data class Record(
        val lines: List<String>,
        val gid: String?,
        val structurallyValid: Boolean,
    )

    private val gidOption = Regex("^gid=([0-9A-Fa-f]{16})$")

    fun retainOwned(text: String, ownedGids: Set<String>): SanitizeResult {
        if (text.isBlank()) return SanitizeResult("", removedRecords = 0, retainedRecords = 0)

        val normalizedOwned = ownedGids.mapTo(linkedSetOf()) { it.lowercase(Locale.ROOT) }
        val records = parse(text)
        val retained = records.filter { record ->
            record.structurallyValid && record.gid?.lowercase(Locale.ROOT) in normalizedOwned
        }
        val rendered = if (retained.isEmpty()) {
            ""
        } else {
            retained.joinToString(separator = "\n\n", postfix = "\n") { record ->
                record.lines.joinToString("\n")
            }
        }
        return SanitizeResult(
            text = rendered,
            removedRecords = records.size - retained.size,
            retainedRecords = retained.size,
        )
    }

    private fun parse(text: String): List<Record> {
        val records = mutableListOf<Record>()
        var current = mutableListOf<String>()
        var hasHeader = false

        fun flush() {
            if (current.isEmpty()) return
            val gid = current.asSequence()
                .drop(1)
                .map(String::trim)
                .mapNotNull { line -> gidOption.matchEntire(line)?.groupValues?.get(1) }
                .singleOrNull()
            records += Record(
                lines = current.toList(),
                gid = gid,
                structurallyValid = hasHeader && gid != null,
            )
            current = mutableListOf()
            hasHeader = false
        }

        text.replace("\r\n", "\n").replace('\r', '\n').split('\n').forEach { line ->
            if (line.isBlank()) {
                flush()
                return@forEach
            }
            // aria2 input files allow comment lines beginning with '#'. They are ignored by
            // aria2 and must not split a URI from the indented option lines that follow it.
            if (line.startsWith("#")) return@forEach
            val indented = line.firstOrNull()?.isWhitespace() == true
            if (!indented) {
                flush()
                current += line
                hasHeader = true
            } else {
                // An option line without a URI header is malformed. Preserve it only inside
                // this parser record so the whole malformed record is discarded atomically.
                current += line
            }
        }
        flush()
        return records
    }
}
