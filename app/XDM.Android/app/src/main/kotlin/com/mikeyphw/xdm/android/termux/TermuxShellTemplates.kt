package com.mikeyphw.xdm.android.termux

object TermuxShellTemplates {
    fun scriptFor(command: XdmTermuxCommand, owner: TermuxRunOwner? = null): String = when {
        owner == null -> rawScriptFor(command)
        command is XdmTermuxCommand.OwnedProcessControl -> rawScriptFor(command)
        else -> managedLauncher(owner, managedPayload(command, owner.runtime))
    }

    private fun rawScriptFor(command: XdmTermuxCommand): String = when (command) {
        XdmTermuxCommand.ProbeAllTools -> probeAllToolsScript()
        is XdmTermuxCommand.PrivacyAudit -> privacyAuditScript(command.sharedStagingPath)
        is XdmTermuxCommand.ProbeTool -> probeToolScript(command.tool)
        is XdmTermuxCommand.Aria2Download -> aria2DownloadScript(command)
        is XdmTermuxCommand.YtDlpMetadata -> managedYtDlpRequiredScript("metadata")
        is XdmTermuxCommand.YtDlpDownload -> managedYtDlpRequiredScript("download")
        is XdmTermuxCommand.FfprobeInspect -> ffprobeInspectScript(command.path)
        is XdmTermuxCommand.FfmpegConvert -> ffmpegConvertScript(command)
        is XdmTermuxCommand.StoragePathProbe -> storagePathProbeScript(command.path)
        is XdmTermuxCommand.PostProcess -> if (command.plan.kind in setOf(PostProcessingActionKind.YtDlpMetadata, PostProcessingActionKind.YtDlpDownload)) {
            managedYtDlpRequiredScript(if (command.plan.kind == PostProcessingActionKind.YtDlpMetadata) "metadata" else "download")
        } else {
            postProcessScript(command.plan)
        }
        is XdmTermuxCommand.OwnedProcessControl -> controlOwnedProcessScript(command)
        is XdmTermuxCommand.Aria2StartDaemon -> aria2StartDaemonScript(command.config)
        is XdmTermuxCommand.Aria2StopDaemon -> aria2RpcScript(command.config, "aria2.shutdown", "XDM_ARIA2_DAEMON\tstopping")
        is XdmTermuxCommand.Aria2ProbeDaemon -> aria2ProbeDaemonScript(command.config)
        is XdmTermuxCommand.Aria2SaveSession -> aria2RpcScript(command.config, "aria2.saveSession", "XDM_ARIA2_SESSION\tsaved")
        is XdmTermuxCommand.Aria2TellActive -> aria2RpcScript(command.config, "aria2.tellActive", "XDM_ARIA2_TASKS\tactive")
        is XdmTermuxCommand.Aria2PauseAll -> aria2RpcScript(command.config, "aria2.pauseAll", "XDM_ARIA2_TASKS\tpaused")
        is XdmTermuxCommand.Aria2ResumeAll -> aria2RpcScript(command.config, "aria2.unpauseAll", "XDM_ARIA2_TASKS\tresumed")
        XdmTermuxCommand.RootProbe -> rootProbeScript()
        is XdmTermuxCommand.RootAction -> rootActionScript(command.action)
    }

    private fun privacyAuditScript(sharedStagingPath: String): String = buildString {
        appendLine("set -eu")
        appendLine("ROOT=\"${'$'}{TMPDIR:-${'$'}PREFIX/tmp}/xdm-post\"")
        appendLine("SHARED=${shellQuote(sharedStagingPath)}")
        appendLine("PATTERN='(^|[[:space:]])(Cookie|Authorization|Proxy-Authorization|X-Api-Key):|([?&](token|access_token|signature|sig|auth|api_key|x-amz-signature)=)'")
        appendLine("FILES=0; FINDINGS=0; STALE=0; NODES=0; SHARED_FILES=0; SHARED_FINDINGS=0; SHARED_STALE=0; INACCESSIBLE=0")
        appendLine("if [ -d \"${'$'}ROOT\" ]; then")
        appendLine("  FILES=${'$'}(find \"${'$'}ROOT\" -type f 2>/dev/null | wc -l | tr -d ' ')")
        appendLine("  FINDINGS=${'$'}(find \"${'$'}ROOT\" -type f -exec grep -IliE \"${'$'}PATTERN\" {} + 2>/dev/null | wc -l | tr -d ' ')")
        appendLine("  STALE_FILES=${'$'}(find \"${'$'}ROOT\" -type f \\( -name 'yt-dlp-session.conf' -o -name 'yt-dlp-urls.txt' -o -name 'payload.sh' \\) -mmin +120 2>/dev/null | wc -l | tr -d ' ')")
        appendLine("  STALE_NODES=${'$'}(find \"${'$'}ROOT\" -mindepth 1 \\( -type p -o -type l -o -type s \\) -mmin +120 2>/dev/null | wc -l | tr -d ' ')")
        appendLine("  STALE_DIRS=${'$'}(find \"${'$'}ROOT\" -mindepth 1 -maxdepth 1 -type d -mmin +120 2>/dev/null | wc -l | tr -d ' ')")
        appendLine("  NODES=${'$'}(find \"${'$'}ROOT\" -mindepth 1 \\( -type p -o -type l -o -type s \\) 2>/dev/null | wc -l | tr -d ' ')")
        appendLine("  STALE=${'$'}((STALE_FILES + STALE_NODES + STALE_DIRS))")
        appendLine("fi")
        appendLine("if [ -e \"${'$'}SHARED\" ] && [ ! -r \"${'$'}SHARED\" ]; then INACCESSIBLE=1; fi")
        appendLine("if [ -d \"${'$'}SHARED\" ] && [ -r \"${'$'}SHARED\" ]; then")
        appendLine("  SHARED_FILES=${'$'}(find \"${'$'}SHARED\" -maxdepth 1 -type f -name '.xdm-*' 2>/dev/null | wc -l | tr -d ' ')")
        appendLine("  SHARED_FINDINGS=${'$'}(find \"${'$'}SHARED\" -maxdepth 1 -type f -name '.xdm-*' -exec grep -IliE \"${'$'}PATTERN\" {} + 2>/dev/null | wc -l | tr -d ' ')")
        appendLine("  SHARED_STALE=${'$'}(find \"${'$'}SHARED\" -maxdepth 1 -type f -name '.xdm-*' -mmin +120 2>/dev/null | wc -l | tr -d ' ')")
        appendLine("fi")
        appendLine("TOTAL_FINDINGS=${'$'}((FINDINGS + SHARED_FINDINGS + INACCESSIBLE))")
        appendLine("TOTAL_STALE=${'$'}((STALE + SHARED_STALE))")
        appendLine("TOTAL_FILES=${'$'}((FILES + SHARED_FILES))")
        appendLine("if [ \"${'$'}TOTAL_FINDINGS\" -eq 0 ] && [ \"${'$'}TOTAL_STALE\" -eq 0 ]; then printf 'XDM_PRIVACY_AUDIT\\tpass\\tfiles=%s\\tfindings=0\\tstale=0\\tnodes=%s\\n' \"${'$'}TOTAL_FILES\" \"${'$'}NODES\"; else printf 'XDM_PRIVACY_AUDIT\\tfail\\tfiles=%s\\tfindings=%s\\tstale=%s\\tnodes=%s\\n' \"${'$'}TOTAL_FILES\" \"${'$'}TOTAL_FINDINGS\" \"${'$'}TOTAL_STALE\" \"${'$'}NODES\"; exit 23; fi")
    }
    private fun storagePathProbeScript(path: String): String = buildString {
        appendLine("set -eu")
        appendLine("TARGET=${shellQuote(path)}")
        appendLine("mkdir -p \"${'$'}TARGET\"")
        appendLine("test -d \"${'$'}TARGET\" && test -w \"${'$'}TARGET\"")
        appendLine("BASE=\"${'$'}TARGET/.xdm-termux-storage-probe-${'$'}${'$'}\"")
        appendLine("cleanup() { rm -f -- \"${'$'}BASE.ytdlp\" \"${'$'}BASE.ffmpeg\"; }")
        appendLine("trap cleanup EXIT INT TERM HUP")
        appendLine("command -v yt-dlp >/dev/null 2>&1 || { printf 'missing yt-dlp\\n' >&2; exit 127; }")
        appendLine("yt-dlp --version > \"${'$'}BASE.ytdlp\"")
        appendLine("test -s \"${'$'}BASE.ytdlp\"")
        appendLine("command -v ffmpeg >/dev/null 2>&1 || { printf 'missing ffmpeg\\n' >&2; exit 127; }")
        appendLine("ffmpeg -version > \"${'$'}BASE.ffmpeg\" 2>&1")
        appendLine("test -s \"${'$'}BASE.ffmpeg\"")
        appendLine("sync \"${'$'}BASE.ytdlp\" \"${'$'}BASE.ffmpeg\" 2>/dev/null || sync")
        appendLine("printf 'XDM_STORAGE_PROBE\\tpass\\t%s\\n' \"${'$'}TARGET\"")
    }

    private fun managedPayload(command: XdmTermuxCommand, runtime: TermuxRuntimeArtifacts): String = when (command) {
        is XdmTermuxCommand.PostProcess -> managedPostProcessScript(command.plan, runtime)
        is XdmTermuxCommand.YtDlpMetadata -> managedYtDlpRequiredScript("metadata")
        is XdmTermuxCommand.YtDlpDownload -> managedYtDlpRequiredScript("download")
        is XdmTermuxCommand.FfprobeInspect -> managedPostProcessScript(
            TermuxPostProcessingPlan(PostProcessingActionKind.FfprobeInspect, command.path), runtime,
        )
        is XdmTermuxCommand.FfmpegConvert -> managedPostProcessScript(
            TermuxPostProcessingPlan(
                kind = if (command.preset.equals("audio", true)) PostProcessingActionKind.ExtractAudio else if (command.preset.equals("faststart", true)) PostProcessingActionKind.RemuxFastStart else PostProcessingActionKind.FfmpegRemux,
                inputPath = command.input,
                outputPath = command.output,
            ), runtime,
        )
        else -> rawScriptFor(command)
    }

    private fun managedLauncher(owner: TermuxRunOwner, payload: String): String {
        val ownerPath = shellQuote(owner.runtime.ownerShellPath)
        val progressPath = shellQuote(owner.runtime.progressShellPath)
        val metadataPath = shellQuote(owner.runtime.metadataShellPath)
        val jobId = shellQuote(owner.jobId)
        val token = shellQuote(owner.processToken)
        val delimiter = "XDM_PAYLOAD_${owner.processToken.uppercase()}"
        require(payload.lineSequence().none { it == delimiter }) { "Managed payload delimiter collision" }
        return buildString {
            appendLine("set -eu")
            appendLine("XDM_JOB_ID=$jobId")
            appendLine("XDM_PROCESS_TOKEN=$token")
            appendLine("XDM_OWNER_FILE=$ownerPath")
            appendLine("XDM_PROGRESS_FILE=$progressPath")
            appendLine("XDM_METADATA_FILE=$metadataPath")
            appendLine("XDM_PRIVATE_ROOT=\"${'$'}{TMPDIR:-${'$'}PREFIX/tmp}/xdm-post/${'$'}XDM_JOB_ID-${'$'}XDM_PROCESS_TOKEN\"")
            appendLine("XDM_PAYLOAD_FIFO=\"${'$'}XDM_PRIVATE_ROOT/payload.pipe\"")
            appendLine("export XDM_JOB_ID XDM_PROCESS_TOKEN XDM_OWNER_FILE XDM_PROGRESS_FILE XDM_METADATA_FILE XDM_PRIVATE_ROOT")
            appendLine("umask 077")
            appendLine("mkdir -p \"${'$'}XDM_PRIVATE_ROOT\"")
            appendLine("chmod 700 \"${'$'}XDM_PRIVATE_ROOT\"")
            appendLine("rm -f -- \"${'$'}XDM_PAYLOAD_FIFO\"")
            appendLine("mkfifo \"${'$'}XDM_PAYLOAD_FIFO\"")
            appendLine("chmod 600 \"${'$'}XDM_PAYLOAD_FIFO\"")
            appendLine("printf 'state=preparing\\njobId=%s\\ntoken=%s\\nwrapperPid=%s\\npayload=stdin-fifo\\nsetsid=0\\n' \"${'$'}XDM_JOB_ID\" \"${'$'}XDM_PROCESS_TOKEN\" \"${'$'}${'$'}\" > \"${'$'}XDM_OWNER_FILE\"")
            appendLine("printf 'phase=preparing\\npercent=0\\nbytes=0\\nmessage=Preparing managed Termux job\\n' > \"${'$'}XDM_PROGRESS_FILE\"")
            appendLine("XDM_SETSID=0")
            appendLine("if command -v setsid >/dev/null 2>&1; then XDM_SETSID=1; setsid sh -s < \"${'$'}XDM_PAYLOAD_FIFO\" & else sh -s < \"${'$'}XDM_PAYLOAD_FIFO\" & fi")
            appendLine("XDM_CHILD_PID=${'$'}!")
            appendLine("cat > \"${'$'}XDM_PAYLOAD_FIFO\" <<'$delimiter")
            append(payload.trimEnd()).appendLine()
            appendLine(delimiter)
            appendLine("rm -f -- \"${'$'}XDM_PAYLOAD_FIFO\"")
            appendLine("XDM_START_TICKS=${'$'}(awk '{print ${'$'}22}' /proc/\"${'$'}XDM_CHILD_PID\"/stat 2>/dev/null || true)")
            appendLine("XDM_GROUP=; if [ \"${'$'}XDM_SETSID\" = 1 ]; then XDM_GROUP=${'$'}XDM_CHILD_PID; fi")
            appendLine("printf 'state=running\\njobId=%s\\ntoken=%s\\nwrapperPid=%s\\npid=%s\\nprocessGroup=%s\\nprocessStartTicks=%s\\nsetsid=%s\\npayload=stdin-fifo\\n' \"${'$'}XDM_JOB_ID\" \"${'$'}XDM_PROCESS_TOKEN\" \"${'$'}${'$'}\" \"${'$'}XDM_CHILD_PID\" \"${'$'}XDM_GROUP\" \"${'$'}XDM_START_TICKS\" \"${'$'}XDM_SETSID\" > \"${'$'}XDM_OWNER_FILE\"")
            appendLine("trap 'if [ \"${'$'}XDM_SETSID\" = 1 ]; then kill -TERM -- -\"${'$'}XDM_CHILD_PID\" 2>/dev/null || true; else kill -TERM \"${'$'}XDM_CHILD_PID\" 2>/dev/null || true; fi; rm -f -- \"${'$'}XDM_PAYLOAD_FIFO\"' TERM INT HUP")
            appendLine("set +e")
            appendLine("wait \"${'$'}XDM_CHILD_PID\"")
            appendLine("XDM_EXIT=${'$'}?")
            appendLine("set -e")
            appendLine("printf 'state=finished\\njobId=%s\\ntoken=%s\\nwrapperPid=%s\\npid=%s\\nprocessGroup=%s\\nprocessStartTicks=%s\\nsetsid=%s\\nexitCode=%s\\npayload=stdin-fifo\\n' \"${'$'}XDM_JOB_ID\" \"${'$'}XDM_PROCESS_TOKEN\" \"${'$'}${'$'}\" \"${'$'}XDM_CHILD_PID\" \"${'$'}XDM_GROUP\" \"${'$'}XDM_START_TICKS\" \"${'$'}XDM_SETSID\" \"${'$'}XDM_EXIT\" > \"${'$'}XDM_OWNER_FILE\"")
            appendLine("rm -f -- \"${'$'}XDM_PAYLOAD_FIFO\"")
            appendLine("rmdir \"${'$'}XDM_PRIVATE_ROOT\" 2>/dev/null || true")
            appendLine("exit \"${'$'}XDM_EXIT\"")
        }
    }

    private fun StringBuilder.appendYtDlpExtraArguments(arguments: List<String>) {
        arguments.forEach { argument ->
            append(shellQuote(argument))
            append(' ')
        }
    }

    private fun managedPostProcessScript(plan: TermuxPostProcessingPlan, runtime: TermuxRuntimeArtifacts): String = buildString {
        appendLine("set -eu")
        appendLine("XDM_PROGRESS=${shellQuote(runtime.progressShellPath)}")
        appendLine("XDM_METADATA=${shellQuote(runtime.metadataShellPath)}")
        appendLine("printf 'phase=running\\npercent=0\\nbytes=0\\nmessage=${shellMarker(plan.kind.label)} started\\n' > \"${'$'}XDM_PROGRESS\"")
        val transientYtDlp = plan.kind in setOf(PostProcessingActionKind.YtDlpMetadata, PostProcessingActionKind.YtDlpDownload) && !plan.ytDlpUrl.isNullOrBlank()
        if (transientYtDlp) {
            appendLine("XDM_YTDLP_CONFIG=\"${'$'}XDM_PRIVATE_ROOT/yt-dlp-session.conf\"")
            appendLine("XDM_YTDLP_URLS=\"${'$'}XDM_PRIVATE_ROOT/yt-dlp-urls.txt\"")
            appendLine("umask 077")
            appendLine("printf '%s\\n' --ignore-config > \"${'$'}XDM_YTDLP_CONFIG\"")
            plan.ytDlpConfigLines.forEach { line -> appendLine("printf '%s\\n' ${shellQuote(line)} >> \"${'$'}XDM_YTDLP_CONFIG\"") }
            appendLine("printf '%s\\n' ${shellQuote(plan.ytDlpUrl.orEmpty())} > \"${'$'}XDM_YTDLP_URLS\"")
            appendLine("chmod 600 \"${'$'}XDM_YTDLP_CONFIG\" \"${'$'}XDM_YTDLP_URLS\"")
            appendLine("trap 'rm -f -- \"${'$'}XDM_YTDLP_CONFIG\" \"${'$'}XDM_YTDLP_URLS\"' EXIT HUP INT TERM")
        }
        val requiredTools = when (plan.kind) {
            PostProcessingActionKind.FfprobeInspect -> listOf(ExternalTool.Ffprobe)
            PostProcessingActionKind.RemuxFastStart, PostProcessingActionKind.ExtractAudio, PostProcessingActionKind.FfmpegRemux -> listOf(ExternalTool.Ffmpeg, ExternalTool.Ffprobe)
            PostProcessingActionKind.YtDlpMetadata -> listOf(ExternalTool.YtDlp)
            PostProcessingActionKind.YtDlpDownload -> listOf(ExternalTool.YtDlp, ExternalTool.Ffmpeg, ExternalTool.Ffprobe)
            else -> emptyList()
        }
        requiredTools.forEach { tool ->
            val binary = shellQuote(tool.binaryName)
            val versionArgs = tool.versionArguments.joinToString(" ") { shellQuote(it) }
            appendLine("command -v $binary >/dev/null 2>&1 || { printf 'missing ${tool.binaryName}\n' >&2; exit 127; }")
            appendLine("printf 'XDM_TOOL_VERSION\t${tool.binaryName}\t%s\n' \"${'$'}($binary $versionArgs 2>&1 | head -n1)\"")
        }
        when (plan.kind) {
            PostProcessingActionKind.MoveToFolder, PostProcessingActionKind.RenameByPattern -> {
                appendLine("test -f ${shellQuote(plan.inputPath)}")
                appendLine("cp -f ${shellQuote(plan.inputPath)} ${shellQuote(plan.outputPath)}")
            }
            PostProcessingActionKind.VerifySha256 -> {
                appendLine("command -v sha256sum >/dev/null 2>&1 || { printf 'missing sha256sum\\n' >&2; exit 127; }")
                appendLine("ACTUAL=${'$'}(sha256sum ${shellQuote(plan.inputPath)} | awk '{print ${'$'}1}')")
                appendLine("printf '{\"sha256\":\"%s\"}\\n' \"${'$'}ACTUAL\" > \"${'$'}XDM_METADATA\"")
                appendLine("test \"${'$'}ACTUAL\" = ${shellQuote(plan.expectedSha256.lowercase())}")
            }
            PostProcessingActionKind.FfprobeInspect -> {
                appendLine("command -v ffprobe >/dev/null 2>&1 || { printf 'missing ffprobe\\n' >&2; exit 127; }")
                appendLine("ffprobe -v error -show_entries format=format_name,duration:stream=codec_name -print_format json ${shellQuote(plan.inputPath)} > \"${'$'}XDM_METADATA\"")
            }
            PostProcessingActionKind.RemuxFastStart -> {
                appendLine("command -v ffmpeg >/dev/null 2>&1 || { printf 'missing ffmpeg\\n' >&2; exit 127; }")
                appendLine("printf 'phase=preflight\\npercent=0\\nmessage=Validating media streams and MP4 compatibility\\n' > \"${'$'}XDM_PROGRESS\"")
                appendLine("ffprobe -v error -show_entries stream=codec_type -of csv=p=0 ${shellQuote(plan.inputPath)} | grep -Eq '^(video|audio)$' || { printf 'input has no remuxable media stream\\n' >&2; exit 65; }")
                appendLine("ffmpeg -hide_banner -nostdin -nostats -loglevel warning -progress \"${'$'}XDM_PROGRESS\" -y -i ${shellQuote(plan.inputPath)} -map 0 -c copy -movflags +faststart ${shellQuote(plan.outputPath)}")
                appendLine("ffprobe -v error -show_entries format=format_name,duration:stream=codec_name -print_format json ${shellQuote(plan.outputPath)} > \"${'$'}XDM_METADATA\" 2>/dev/null || true")
            }
            PostProcessingActionKind.ExtractAudio -> {
                appendLine("command -v ffmpeg >/dev/null 2>&1 || { printf 'missing ffmpeg\\n' >&2; exit 127; }")
                appendLine("printf 'phase=preflight\\npercent=0\\nmessage=Validating the source audio stream\\n' > \"${'$'}XDM_PROGRESS\"")
                appendLine("ffprobe -v error -select_streams a:0 -show_entries stream=codec_name -of csv=p=0 ${shellQuote(plan.inputPath)} | grep -q . || { printf 'input has no audio stream\\n' >&2; exit 65; }")
                appendLine("ffmpeg -hide_banner -nostdin -nostats -loglevel warning -progress \"${'$'}XDM_PROGRESS\" -y -i ${shellQuote(plan.inputPath)} -vn -c:a copy ${shellQuote(plan.outputPath)}")
                appendLine("ffprobe -v error -show_entries format=format_name,duration:stream=codec_name -print_format json ${shellQuote(plan.outputPath)} > \"${'$'}XDM_METADATA\" 2>/dev/null || true")
            }
            PostProcessingActionKind.FfmpegRemux -> {
                appendLine("command -v ffmpeg >/dev/null 2>&1 || { printf 'missing ffmpeg\\n' >&2; exit 127; }")
                appendLine("printf 'phase=preflight\\npercent=0\\nmessage=Validating media streams and output container\\n' > \"${'$'}XDM_PROGRESS\"")
                appendLine("ffprobe -v error -show_entries stream=codec_type -of csv=p=0 ${shellQuote(plan.inputPath)} | grep -Eq '^(video|audio)$' || { printf 'input has no remuxable media stream\\n' >&2; exit 65; }")
                appendLine("ffmpeg -hide_banner -nostdin -nostats -loglevel warning -progress \"${'$'}XDM_PROGRESS\" -y -i ${shellQuote(plan.inputPath)} -map 0 -c copy ${shellQuote(plan.outputPath)}")
                appendLine("ffprobe -v error -show_entries format=format_name,duration:stream=codec_name -print_format json ${shellQuote(plan.outputPath)} > \"${'$'}XDM_METADATA\" 2>/dev/null || true")
            }
            PostProcessingActionKind.YtDlpMetadata -> {
                appendLine("command -v yt-dlp >/dev/null 2>&1 || { printf 'missing yt-dlp\\n' >&2; exit 127; }")
                check(transientYtDlp) { "Managed yt-dlp metadata requires a transient encrypted session" }
                append("yt-dlp --config-locations \"${'$'}XDM_YTDLP_CONFIG\" --batch-file \"${'$'}XDM_YTDLP_URLS\" --no-warnings ")
                appendYtDlpExtraArguments(plan.extraArguments)
                appendLine("--print \"%(.{title,ext,duration,is_live,vcodec,acodec,formats.:.{format_id,format_note,vcodec,acodec,mime_type,width,height,tbr,language}})#j\" > \"${'$'}XDM_METADATA\"")
            }
            PostProcessingActionKind.YtDlpDownload -> {
                appendLine("command -v yt-dlp >/dev/null 2>&1 || { printf 'missing yt-dlp\\n' >&2; exit 127; }")
                check(transientYtDlp) { "Managed yt-dlp download requires a transient encrypted session" }
                appendLine("printf 'phase=preflight\\npercent=0\\nmessage=Resolving requested yt-dlp format\\n' > \"${'$'}XDM_PROGRESS\"")
                append("yt-dlp --config-locations \"${'$'}XDM_YTDLP_CONFIG\" --batch-file \"${'$'}XDM_YTDLP_URLS\" --simulate --no-warnings --no-playlist ")
                plan.formatSelector.takeIf(String::isNotBlank)?.let { append("-f ${shellQuote(it)} ") }
                appendYtDlpExtraArguments(plan.extraArguments)
                appendLine(">/dev/null")
                append("yt-dlp --config-locations \"${'$'}XDM_YTDLP_CONFIG\" --batch-file \"${'$'}XDM_YTDLP_URLS\" --force-overwrites --no-part --newline --progress-template ")
                append(shellQuote("download:XDM_YTDLP\\t%(progress._percent_str)s\\t%(progress.downloaded_bytes)s\\t%(progress.total_bytes_estimate)s"))
                append(" -o ${shellQuote(plan.outputPath)} ")
                plan.formatSelector.takeIf(String::isNotBlank)?.let { append("-f ${shellQuote(it)} ") }
                appendYtDlpExtraArguments(plan.extraArguments)
                appendLine("> \"${'$'}XDM_PROGRESS\"")
                append("yt-dlp --config-locations \"${'$'}XDM_YTDLP_CONFIG\" --batch-file \"${'$'}XDM_YTDLP_URLS\" --no-warnings ")
                appendYtDlpExtraArguments(plan.extraArguments)
                appendLine("--print \"%(.{title,ext,duration,is_live,vcodec,acodec,formats.:.{format_id,format_note,vcodec,acodec,mime_type,width,height,tbr,language}})#j\" > \"${'$'}XDM_METADATA\" || true")
            }
            PostProcessingActionKind.CleanupPartials -> {
                appendLine("TARGET=${shellQuote(plan.inputPath)}")
                appendLine("test -f \"${'$'}TARGET\" || test -f \"${'$'}TARGET.part\" || test -f \"${'$'}TARGET.aria2\"")
                appendLine("rm -f -- \"${'$'}TARGET.part\" \"${'$'}TARGET.aria2\" \"${'$'}TARGET.tmp\"")
            }
            PostProcessingActionKind.FixPermissionsWithRoot -> {
                appendLine("printf 'typed root action required\\n' >&2")
                appendLine("exit 3")
            }
        }
        appendLine("printf 'phase=finished\\npercent=100\\nmessage=${shellMarker(plan.kind.label)} finished\\n' >> \"${'$'}XDM_PROGRESS\"")
    }

    private fun controlOwnedProcessScript(command: XdmTermuxCommand.OwnedProcessControl): String = buildString {
        appendLine("set -eu")
        appendLine("OWNER=${shellQuote(command.ownerFilePath)}")
        appendLine("EXPECTED_JOB=${shellQuote(command.jobId)}")
        appendLine("EXPECTED_TOKEN=${shellQuote(command.processToken)}")
        appendLine("test -f \"${'$'}OWNER\" || { printf 'XDM_CONTROL\\tmissing_owner\\n'; exit 4; }")
        appendLine("STATE=${'$'}(sed -n 's/^state=//p' \"${'$'}OWNER\" | head -n1)")
        appendLine("JOB=${'$'}(sed -n 's/^jobId=//p' \"${'$'}OWNER\" | head -n1)")
        appendLine("TOKEN=${'$'}(sed -n 's/^token=//p' \"${'$'}OWNER\" | head -n1)")
        appendLine("PID=${'$'}(sed -n 's/^pid=//p' \"${'$'}OWNER\" | head -n1)")
        appendLine("GROUP=${'$'}(sed -n 's/^processGroup=//p' \"${'$'}OWNER\" | head -n1)")
        appendLine("START_TICKS=${'$'}(sed -n 's/^processStartTicks=//p' \"${'$'}OWNER\" | head -n1)")
        appendLine("SETSID=${'$'}(sed -n 's/^setsid=//p' \"${'$'}OWNER\" | head -n1)")
        appendLine("EXIT_CODE=${'$'}(sed -n 's/^exitCode=//p' \"${'$'}OWNER\" | head -n1)")
        appendLine("PAYLOAD=${'$'}(sed -n 's/^payload=//p' \"${'$'}OWNER\" | head -n1)")
        appendLine("test \"${'$'}JOB\" = \"${'$'}EXPECTED_JOB\" && test \"${'$'}TOKEN\" = \"${'$'}EXPECTED_TOKEN\" || { printf 'XDM_CONTROL\\tdenied\\n'; exit 3; }")
        appendLine("if [ \"${'$'}STATE\" = finished ]; then printf 'XDM_CONTROL\\tprobe\\tfinished\\texit=%s\\n' \"${'$'}{EXIT_CODE:-1}\"; exit 0; fi")
        appendLine("case \"${'$'}PID\" in ''|*[!0-9]*) printf 'XDM_CONTROL\\tinvalid_pid\\n'; exit 4 ;; esac")
        appendLine("if ! test -d /proc/\"${'$'}PID\"; then printf 'XDM_CONTROL\\tprobe\\tstopped_without_result\\n'; exit 76; fi")
        appendLine("CURRENT_TICKS=${'$'}(awk '{print ${'$'}22}' /proc/\"${'$'}PID\"/stat 2>/dev/null || true)")
        appendLine("test -n \"${'$'}START_TICKS\" && test \"${'$'}CURRENT_TICKS\" = \"${'$'}START_TICKS\" || { printf 'XDM_CONTROL\\towner_mismatch\\tpid_reused\\n'; exit 3; }")
        appendLine("CMDLINE=${'$'}(tr '\\0' ' ' < /proc/\"${'$'}PID\"/cmdline 2>/dev/null || true)")
        appendLine("case \"${'$'}CMDLINE\" in *\"${'$'}PAYLOAD\"*) : ;; *) printf 'XDM_CONTROL\\towner_mismatch\\tcmdline\\n'; exit 3 ;; esac")
        val signal = when (command.action) {
            TermuxProcessControlAction.Pause -> "STOP"
            TermuxProcessControlAction.Resume -> "CONT"
            TermuxProcessControlAction.Cancel -> "TERM"
            TermuxProcessControlAction.ForceCancel -> "KILL"
            TermuxProcessControlAction.Probe -> "0"
        }
        if (command.action == TermuxProcessControlAction.Probe) {
            appendLine("kill -0 \"${'$'}PID\" 2>/dev/null")
            appendLine("printf 'XDM_CONTROL\\tprobe\\talive\\tpid=%s\\tstartTicks=%s\\n' \"${'$'}PID\" \"${'$'}START_TICKS\"")
        } else {
            appendLine("xdm_start_ticks() { awk '{print ${'$'}22}' /proc/\"${'$'}1\"/stat 2>/dev/null || true; }")
            appendLine("xdm_children() { for status in /proc/[0-9]*/status; do child=${'$'}{status#/proc/}; child=${'$'}{child%/status}; ppid=${'$'}(sed -n 's/^PPid:[[:space:]]*//p' \"${'$'}status\" | head -n1); test \"${'$'}ppid\" = \"${'$'}1\" && printf '%s\\n' \"${'$'}child\"; done; }")
            appendLine("xdm_signal_tree() { parent=${'$'}1; sig=${'$'}2; for child in ${'$'}(xdm_children \"${'$'}parent\"); do xdm_signal_tree \"${'$'}child\" \"${'$'}sig\"; done; before=${'$'}(xdm_start_ticks \"${'$'}parent\"); test -n \"${'$'}before\" || return 0; current=${'$'}(xdm_start_ticks \"${'$'}parent\"); test \"${'$'}before\" = \"${'$'}current\" || return 0; kill -\"${'$'}sig\" \"${'$'}parent\" 2>/dev/null || true; }")
            appendLine("if [ \"${'$'}SETSID\" = 1 ] && [ -n \"${'$'}GROUP\" ]; then kill -$signal -- -\"${'$'}GROUP\" 2>/dev/null || kill -$signal \"${'$'}PID\"; else xdm_signal_tree \"${'$'}PID\" $signal; fi")
            if (command.action == TermuxProcessControlAction.Cancel) {
                appendLine("for _ in 1 2 3 4 5 6 7 8 9 10; do test ! -d /proc/\"${'$'}PID\" && break; sleep 1; done")
                appendLine("if test -d /proc/\"${'$'}PID\"; then printf 'XDM_CONTROL\\tforce_required\\tpid=%s\\n' \"${'$'}PID\"; exit 75; fi")
            }
            appendLine("printf 'XDM_CONTROL\\t${command.action.name.lowercase()}\\taccepted\\tpid=%s\\n' \"${'$'}PID\"")
        }
    }

    private fun probeAllToolsScript(): String = buildString {
        appendLine("set +e")
        ExternalTool.entries.forEach { tool -> appendLine(probeToolBody(tool)) }
        appendLine("if command -v ffmpeg >/dev/null 2>&1; then")
        appendLine("  ffmpeg -hide_banner -muxers 2>/dev/null | awk 'NR>4 && ${'$'}1 ~ /E/ {print \"XDM_FFMPEG_MUXER\\t\" ${'$'}2}'")
        appendLine("  ffmpeg -hide_banner -encoders 2>/dev/null | awk 'NR>10 && ${'$'}1 ~ /[VAS]/ {print \"XDM_FFMPEG_ENCODER\\t\" ${'$'}2}'")
        appendLine("  ffmpeg -hide_banner -decoders 2>/dev/null | awk 'NR>10 && ${'$'}1 ~ /[VAS]/ {print \"XDM_FFMPEG_DECODER\\t\" ${'$'}2}'")
        appendLine("fi")
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

    private fun managedYtDlpRequiredScript(kind: String): String = buildString {
        require(kind in setOf("metadata", "download")) { "Unsupported raw yt-dlp command kind" }
        appendLine("set -eu")
        appendLine("printf 'XDM_MEDIA\tytdlp_${kind}\tdenied\tmanaged transient session required\n' >&2")
        appendLine("exit 3")
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



    private fun postProcessScript(plan: TermuxPostProcessingPlan): String = buildString {
        appendLine("set -e")
        appendLine("printf 'XDM_POST_PROCESS\tstarted\t${shellMarker(plan.kind.label)}\n'")
        when (plan.kind) {
            PostProcessingActionKind.MoveToFolder -> {
                appendLine("mkdir -p ${shellQuote(plan.outputPath)}")
                appendLine("mv -n ${shellQuote(plan.inputPath)} ${shellQuote(plan.outputPath)}")
            }
            PostProcessingActionKind.RenameByPattern -> {
                appendLine("mkdir -p ${shellQuote(plan.outputPath.substringBeforeLast('/', missingDelimiterValue = "."))}")
                appendLine("mv -n ${shellQuote(plan.inputPath)} ${shellQuote(plan.outputPath)}")
            }
            PostProcessingActionKind.VerifySha256 -> {
                appendLine("if command -v sha256sum >/dev/null 2>&1; then :; else printf 'XDM_POST_PROCESS\tmissing\tsha256sum not found\n'; exit 127; fi")
                appendLine("ACTUAL=${'$'}(sha256sum ${shellQuote(plan.inputPath)} | awk '{print ${'$'}1}')")
                appendLine("printf 'XDM_POST_PROCESS\tsha256\t%s\n' \"${'$'}ACTUAL\"")
                if (plan.expectedSha256.isNotBlank()) {
                    appendLine("test \"${'$'}ACTUAL\" = ${shellQuote(plan.expectedSha256.lowercase())}")
                }
            }
            PostProcessingActionKind.FfprobeInspect -> {
                appendLine("if command -v ffprobe >/dev/null 2>&1; then :; else printf 'XDM_POST_PROCESS\tmissing\tffprobe not found\n'; exit 127; fi")
                appendLine("ffprobe -hide_banner -show_format -show_streams -print_format json ${shellQuote(plan.inputPath)}")
            }
            PostProcessingActionKind.RemuxFastStart -> {
                appendLine("if command -v ffmpeg >/dev/null 2>&1; then :; else printf 'XDM_POST_PROCESS\tmissing\tffmpeg not found\n'; exit 127; fi")
                appendLine("mkdir -p ${shellQuote(plan.outputPath.substringBeforeLast('/', missingDelimiterValue = "."))}")
                appendLine("ffmpeg -hide_banner -y -i ${shellQuote(plan.inputPath)} -c copy -movflags +faststart ${shellQuote(plan.outputPath)}")
            }
            PostProcessingActionKind.ExtractAudio -> {
                appendLine("if command -v ffmpeg >/dev/null 2>&1; then :; else printf 'XDM_POST_PROCESS\tmissing\tffmpeg not found\n'; exit 127; fi")
                appendLine("mkdir -p ${shellQuote(plan.outputPath.substringBeforeLast('/', missingDelimiterValue = "."))}")
                appendLine("ffmpeg -hide_banner -y -i ${shellQuote(plan.inputPath)} -vn -c:a copy ${shellQuote(plan.outputPath)}")
            }
            PostProcessingActionKind.CleanupPartials -> {
                appendLine("printf 'XDM_POST_PROCESS\tdenied\tmanaged Android cleanup required\n' >&2")
                appendLine("exit 3")
            }
            PostProcessingActionKind.FixPermissionsWithRoot -> {
                appendLine("printf 'XDM_POST_PROCESS\troot_required\tuse typed root action\n'")
            }
            PostProcessingActionKind.YtDlpMetadata -> {
                appendLine("printf 'managed transient yt-dlp session required\n' >&2")
                appendLine("exit 3")
            }
            PostProcessingActionKind.YtDlpDownload -> {
                appendLine("printf 'managed transient yt-dlp session required\n' >&2")
                appendLine("exit 3")
            }
            PostProcessingActionKind.FfmpegRemux -> appendLine("ffmpeg -hide_banner -y -i ${shellQuote(plan.inputPath)} -map 0 -c copy ${shellQuote(plan.outputPath)}")
        }
        appendLine("printf 'XDM_POST_PROCESS\tfinished\t${shellMarker(plan.kind.label)}\n'")
    }



    private fun rootProbeScript(): String = buildString {
        appendLine("set +e")
        appendLine("if command -v su >/dev/null 2>&1; then printf 'XDM_ROOT\\tavailable\\n'; else printf 'XDM_ROOT\\tmissing\\n'; exit 127; fi")
        appendLine("su -c ${shellQuote("id -u; id; printf 'XDM_ROOT_PROBE\\tready\\n'")} 2>&1")
    }

    private fun rootActionScript(action: XdmRootAction): String = buildString {
        appendLine("set +e")
        appendLine("if command -v su >/dev/null 2>&1; then :; else printf 'XDM_ROOT_ACTION\\tmissing\\tsu not found\\n'; exit 127; fi")
        appendLine("printf 'XDM_ROOT_ACTION\\tstarted\\t${shellMarker(action.label)}\\n'")
        appendLine("su -c ${shellQuote(rootInnerScript(action))} 2>&1")
        appendLine("XDM_ROOT_EXIT=${'$'}?")
        appendLine("if [ \"${'$'}XDM_ROOT_EXIT\" -eq 0 ]; then printf 'XDM_ROOT_ACTION\\tfinished\\t${shellMarker(action.label)}\\n'; else printf 'XDM_ROOT_ACTION\\tfailed\\t${shellMarker(action.label)}\\t%s\\n' \"${'$'}XDM_ROOT_EXIT\"; fi")
        appendLine("exit \"${'$'}XDM_ROOT_EXIT\"")
    }

    private fun rootInnerScript(action: XdmRootAction): String = when (action) {
        is XdmRootAction.CollectProcessDiagnostics -> rootCollectProcessDiagnosticsScript(action.packageName)
        is XdmRootAction.KillTermuxAria2Daemon -> rootKillTermuxAria2Script(action.port)
        is XdmRootAction.KillOwnedProcess -> rootKillOwnedProcessScript(action.pid)
        is XdmRootAction.FixFilePermissions -> rootFixPermissionsScript(action.path)
        is XdmRootAction.MoveCompletedFile -> rootMoveCompletedFileScript(action.from, action.to)
    }

    private fun rootCollectProcessDiagnosticsScript(packageName: String): String = buildString {
        appendLine("printf 'XDM_ROOT_DIAGNOSTICS\\tidentity\\t'")
        appendLine("id")
        appendLine("printf 'XDM_ROOT_DIAGNOSTICS\\tprocesses\\n'")
        appendLine("ps -A 2>/dev/null | grep -E ${shellQuote("aria2c|ffmpeg|ffprobe|yt-dlp|${packageName}|com.termux")} | head -n 80 || true")
    }

    private fun rootKillTermuxAria2Script(port: Int): String = buildString {
        appendLine("TARGET_PORT=${port.coerceIn(1024, 65535)}")
        appendLine("MATCHES=${'$'}(for pid in ${'$'}(pidof aria2c 2>/dev/null); do cmd=${'$'}(tr '\\0' ' ' < /proc/${'$'}pid/cmdline 2>/dev/null); case \"${'$'}cmd\" in *--rpc-listen-port=${'$'}TARGET_PORT*) printf '%s ' \"${'$'}pid\" ;; esac; done)")
        appendLine("if [ -z \"${'$'}MATCHES\" ]; then printf 'XDM_ROOT_ACTION\\tnoop\\tno matching XDM aria2 daemon\\n'; exit 0; fi")
        appendLine("for pid in ${'$'}MATCHES; do kill -TERM \"${'$'}pid\"; printf 'XDM_ROOT_ACTION\\tkilled\\taria2c pid=%s\\n' \"${'$'}pid\"; done")
    }

    private fun rootKillOwnedProcessScript(@Suppress("UNUSED_PARAMETER") pid: Int): String = buildString {
        appendLine("printf 'XDM_ROOT_ACTION\\tdenied\\tPID-only ownership is not accepted; use the token-bound managed process controller\\n'")
        appendLine("exit 3")
    }

    private fun rootFixPermissionsScript(path: String): String = buildString {
        appendLine("TARGET=${shellQuote(path)}")
        appendLine("chmod -R u+rwX,g+rwX \"${'$'}TARGET\"")
        appendLine("printf 'XDM_ROOT_ACTION\\tpermissions_fixed\\t%s\\n' \"${'$'}TARGET\"")
    }

    private fun rootMoveCompletedFileScript(from: String, to: String): String = buildString {
        appendLine("FROM=${shellQuote(from)}")
        appendLine("TO=${shellQuote(to)}")
        appendLine("mkdir -p \"${'$'}(dirname \"${'$'}TO\")\"")
        appendLine("mv -n \"${'$'}FROM\" \"${'$'}TO\"")
        appendLine("printf 'XDM_ROOT_ACTION\\tmoved\\t%s\\t%s\\n' \"${'$'}FROM\" \"${'$'}TO\"")
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

    private fun shellMarker(value: String): String = value.replace('\t', ' ').replace('\n', ' ').take(80)

    fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
