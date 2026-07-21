package com.mikeyphw.xdm.android.termux

object TermuxShellTemplates {
    fun scriptFor(command: XdmTermuxCommand): String = when (command) {
        XdmTermuxCommand.ProbeAllTools -> probeAllToolsScript()
        is XdmTermuxCommand.ProbeTool -> probeToolScript(command.tool)
        is XdmTermuxCommand.Aria2Download -> aria2DownloadScript(command)
        is XdmTermuxCommand.YtDlpMetadata -> ytdlpMetadataScript(command.url)
        is XdmTermuxCommand.FfprobeInspect -> ffprobeInspectScript(command.path)
        is XdmTermuxCommand.FfmpegConvert -> ffmpegConvertScript(command)
    }

    private fun probeAllToolsScript(): String = buildString {
        appendLine("set +e")
        ExternalTool.entries.forEach { tool -> appendLine(probeToolBody(tool)) }
        appendLine("if command -v su >/dev/null 2>&1; then printf 'XDM_ROOT\\tavailable\\n'; else printf 'XDM_ROOT\\tmissing\\n'; fi")
    }

    private fun probeToolScript(tool: ExternalTool): String = "set +e\n" + probeToolBody(tool)

    private fun probeToolBody(tool: ExternalTool): String {
        val binary = shellQuote(tool.binaryName)
        val versionArgs = tool.versionArguments.joinToString(" ") { shellQuote(it) }
        return """
            if command -v $binary >/dev/null 2>&1; then
              XDM_TOOL_PATH="${'$'}(command -v $binary)"
              XDM_TOOL_VERSION="${'$'}($binary $versionArgs 2>&1 | head -n 1)"
              printf 'XDM_TOOL\t%s\tavailable\t%s\t%s\n' $binary "${'$'}XDM_TOOL_PATH" "${'$'}XDM_TOOL_VERSION"
            else
              printf 'XDM_TOOL\t%s\tmissing\t\t\n' $binary
            fi
        """.trimIndent()
    }

    private fun aria2DownloadScript(command: XdmTermuxCommand.Aria2Download): String = buildString {
        appendLine("set -e")
        appendLine("mkdir -p ${shellQuote(command.destination)}")
        append("aria2c --continue=true --allow-overwrite=false --auto-file-renaming=true ")
        command.fileName?.trim()?.takeIf { it.isNotBlank() }?.let { append("--out ${shellQuote(it)} ") }
        appendLine("--dir ${shellQuote(command.destination)} ${shellQuote(command.url)}")
    }

    private fun ytdlpMetadataScript(url: String): String = "yt-dlp --dump-single-json --no-warnings ${shellQuote(url)}"

    private fun ffprobeInspectScript(path: String): String = "ffprobe -hide_banner -show_format -show_streams -print_format json ${shellQuote(path)}"

    private fun ffmpegConvertScript(command: XdmTermuxCommand.FfmpegConvert): String = buildString {
        append("ffmpeg -hide_banner -y -i ${shellQuote(command.input)} ")
        append(when (command.preset.lowercase()) {
            "audio" -> "-vn -c:a copy "
            "remux" -> "-c copy "
            else -> "-c:v copy -c:a copy "
        })
        append(shellQuote(command.output))
    }

    fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
