package com.mikeyphw.xdm.android.termux

object TermuxShellTemplates {
    fun scriptFor(command: XdmTermuxCommand): String = when (command) {
        XdmTermuxCommand.ProbeAllTools -> probeAllToolsScript()
        is XdmTermuxCommand.ProbeTool -> probeToolScript(command.tool)
        is XdmTermuxCommand.Aria2Download -> aria2DownloadScript(command)
        is XdmTermuxCommand.YtDlpMetadata -> ytdlpMetadataScript(command.url)
        is XdmTermuxCommand.YtDlpDownload -> ytdlpDownloadScript(command)
        is XdmTermuxCommand.FfprobeInspect -> ffprobeInspectScript(command.path)
        is XdmTermuxCommand.FfmpegConvert -> ffmpegConvertScript(command)
        is XdmTermuxCommand.Aria2StartDaemon -> aria2StartDaemonScript(command.config)
        is XdmTermuxCommand.Aria2StopDaemon -> aria2RpcScript(command.config, "aria2.shutdown", "XDM_ARIA2_DAEMON\tstopping")
        is XdmTermuxCommand.Aria2ProbeDaemon -> aria2ProbeDaemonScript(command.config)
        is XdmTermuxCommand.Aria2SaveSession -> aria2RpcScript(command.config, "aria2.saveSession", "XDM_ARIA2_SESSION\tsaved")
        is XdmTermuxCommand.Aria2TellActive -> aria2RpcScript(command.config, "aria2.tellActive", "XDM_ARIA2_TASKS\tactive")
        is XdmTermuxCommand.Aria2PauseAll -> aria2RpcScript(command.config, "aria2.pauseAll", "XDM_ARIA2_TASKS\tpaused")
        is XdmTermuxCommand.Aria2ResumeAll -> aria2RpcScript(command.config, "aria2.unpauseAll", "XDM_ARIA2_TASKS\tresumed")
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

    private fun ytdlpMetadataScript(url: String): String = buildString {
        appendLine("set -e")
        appendLine("if command -v yt-dlp >/dev/null 2>&1; then :; else printf 'XDM_MEDIA\tytdlp_metadata\tmissing\tyt-dlp not found\n'; exit 127; fi")
        appendLine("printf 'XDM_MEDIA\tytdlp_metadata\tstarted\n'")
        appendLine("yt-dlp --dump-single-json --no-warnings ${shellQuote(url)}")
    }

    private fun ytdlpDownloadScript(command: XdmTermuxCommand.YtDlpDownload): String = buildString {
        appendLine("set -e")
        appendLine("if command -v yt-dlp >/dev/null 2>&1; then :; else printf 'XDM_MEDIA\tytdlp_download\tmissing\tyt-dlp not found\n'; exit 127; fi")
        appendLine("mkdir -p ${shellQuote(command.destination)}")
        append("yt-dlp --no-part --newline --paths ${shellQuote(command.destination)} --output ${shellQuote(command.outputTemplate)} ")
        command.format?.trim()?.takeIf { it.isNotBlank() }?.let { append("--format ${shellQuote(it)} ") }
        appendLine(shellQuote(command.url))
    }

    private fun ffprobeInspectScript(path: String): String = buildString {
        appendLine("set -e")
        appendLine("if command -v ffprobe >/dev/null 2>&1; then :; else printf 'XDM_MEDIA\tffprobe\tmissing\tffprobe not found\n'; exit 127; fi")
        appendLine("ffprobe -hide_banner -show_format -show_streams -print_format json ${shellQuote(path)}")
    }

    private fun ffmpegConvertScript(command: XdmTermuxCommand.FfmpegConvert): String = buildString {
        appendLine("set -e")
        appendLine("if command -v ffmpeg >/dev/null 2>&1; then :; else printf 'XDM_MEDIA\tffmpeg\tmissing\tffmpeg not found\n'; exit 127; fi")
        appendLine("mkdir -p ${shellQuote(command.output.substringBeforeLast('/', missingDelimiterValue = "."))}")
        append("ffmpeg -hide_banner -y -i ${shellQuote(command.input)} ")
        append(when (command.preset.lowercase()) {
            "audio" -> "-vn -c:a copy "
            "faststart" -> "-c copy -movflags +faststart "
            "remux" -> "-c copy "
            else -> "-c:v copy -c:a copy "
        })
        append(shellQuote(command.output))
    }


    private fun aria2StartDaemonScript(config: TermuxAria2RpcConfig): String = buildString {
        appendLine("set -e")
        appendLine("mkdir -p ${shellQuote(config.downloadDir)} ${shellQuote(config.sessionFile.substringBeforeLast('/'))} ${shellQuote(config.logFile.substringBeforeLast('/'))}")
        appendLine("touch ${shellQuote(config.sessionFile)}")
        appendLine("if command -v aria2c >/dev/null 2>&1; then :; else printf 'XDM_ARIA2_DAEMON\\tmissing\\taria2c not found\\n'; exit 127; fi")
        appendLine("if pgrep -f ${shellQuote("aria2c.*--rpc-listen-port=${config.port}")} >/dev/null 2>&1; then printf 'XDM_ARIA2_DAEMON\\trunning\\t${config.redactedEndpoint}\\n'; exit 0; fi")
        append("nohup aria2c --enable-rpc=true --rpc-listen-all=false ")
        append("--rpc-listen-port ${config.port} --rpc-secret ${shellQuote(config.secret)} ")
        append("--continue=true --auto-file-renaming=true --allow-overwrite=false ")
        append("--input-file ${shellQuote(config.sessionFile)} --save-session ${shellQuote(config.sessionFile)} --save-session-interval=30 ")
        append("--dir ${shellQuote(config.downloadDir)} ")
        appendLine(">> ${shellQuote(config.logFile)} 2>&1 &")
        appendLine("XDM_ARIA2_PID=${'$'}!")
        appendLine("sleep 1")
        appendLine("printf 'XDM_ARIA2_DAEMON\\tstarted\\t${config.redactedEndpoint}\\tpid=%s\\n' \"${'$'}XDM_ARIA2_PID\"")
    }

    private fun aria2ProbeDaemonScript(config: TermuxAria2RpcConfig): String = buildString {
        appendLine("set +e")
        appendLine("if pgrep -f ${shellQuote("aria2c.*--rpc-listen-port=${config.port}")} >/dev/null 2>&1; then printf 'XDM_ARIA2_DAEMON\\trunning\\t${config.redactedEndpoint}\\n'; else printf 'XDM_ARIA2_DAEMON\\tstopped\\t${config.redactedEndpoint}\\n'; fi")
        append(aria2RpcBody(config, "aria2.getVersion", "XDM_ARIA2_RPC\\tversion"))
    }

    private fun aria2RpcScript(config: TermuxAria2RpcConfig, method: String, marker: String): String = buildString {
        appendLine("set +e")
        append(aria2RpcBody(config, method, marker))
    }

    private fun aria2RpcBody(config: TermuxAria2RpcConfig, method: String, marker: String): String {
        val payload = "{\"jsonrpc\":\"2.0\",\"id\":\"xdm\",\"method\":\"$method\",\"params\":[\"token:${config.secret}\"]}"
        return """
            if command -v curl >/dev/null 2>&1; then
              XDM_ARIA2_RESPONSE="${'$'}(curl -fsS --max-time 5 -H 'Content-Type: application/json' -d ${shellQuote(payload)} ${shellQuote(config.endpoint)} 2>&1)"
              XDM_ARIA2_EXIT=${'$'}?
              if [ "${'$'}XDM_ARIA2_EXIT" -eq 0 ]; then
                printf '$marker\t%s\n' "${'$'}XDM_ARIA2_RESPONSE"
              else
                printf 'XDM_ARIA2_RPC\tfailed\t%s\n' "${'$'}XDM_ARIA2_RESPONSE"
                exit "${'$'}XDM_ARIA2_EXIT"
              fi
            else
              printf 'XDM_ARIA2_RPC\tmissing\tcurl not found\n'
              exit 127
            fi
        """.trimIndent() + "\n"
    }

    fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
