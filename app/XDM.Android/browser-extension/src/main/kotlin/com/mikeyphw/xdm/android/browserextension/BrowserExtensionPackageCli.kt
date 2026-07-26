package com.mikeyphw.xdm.android.browserextension

import java.io.File

object BrowserExtensionPackageCli {
    @JvmStatic
    fun main(args: Array<String>) {
        val options = parse(args)
        val output = File(required(options, "output"))
        val config = BrowserExtensionBuildConfig(
            extensionVersion = options["extension-version"] ?: BrowserExtensionSourceContract.DevelopmentVersion,
            appVersion = required(options, "app-version"),
            applicationId = required(options, "application-id"),
            channel = enumValue(options["channel"] ?: "release", BrowserExtensionSourceContract.Channel.entries) { it.wireValue },
            xdmScheme = required(options, "xdm-scheme"),
            defaultTarget = enumValue(options["default-target"] ?: "xdm", BrowserExtensionSourceContract.Target.entries) { it.wireValue },
            themeMode = enumValue(options["theme"] ?: "dark", BrowserExtensionSourceContract.ThemeMode.entries) { it.wireValue },
        )
        val result = BrowserExtensionPackageGenerator().generateToFile(config, output)
        println("${result.fileName}\t${result.byteCount}\t${result.sha256}\t${output.absolutePath}")
    }

    private fun parse(args: Array<String>): Map<String, String> {
        require(args.size % 2 == 0) { "Arguments must be --name value pairs" }
        return args.asList().chunked(2).associate { pair ->
            val key = pair[0].removePrefix("--")
            require(pair[0].startsWith("--") && key.isNotBlank()) { "Invalid argument ${pair[0]}" }
            key to pair[1]
        }
    }

    private fun required(options: Map<String, String>, name: String): String =
        requireNotNull(options[name]?.takeIf { it.isNotBlank() }) { "Missing --$name" }

    private fun <T> enumValue(value: String, values: List<T>, wire: (T) -> String): T =
        requireNotNull(values.firstOrNull { wire(it) == value }) { "Unsupported value: $value" }
}
