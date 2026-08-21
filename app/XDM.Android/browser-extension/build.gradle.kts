plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(21) }

sourceSets {
    main { resources.srcDir("src/main/extension") }
}

dependencies { testImplementation(libs.junit) }

val extensionSource = layout.projectDirectory.dir("src/main/extension/xdm-firefox")
val unpackedOutput = layout.buildDirectory.dir("firefox/unpacked")
val xpiOutput = layout.buildDirectory.dir("outputs/xpi")
val extensionVersion = "1.3.0"
val androidAppVersion = "0.21.0"

val prepareFirefoxExtension = tasks.register<Exec>("prepareFirefoxExtension") {
    group = "browser extension"
    description = "Render the development Firefox extension into build/firefox/unpacked."
    inputs.dir(extensionSource)
    outputs.dir(unpackedOutput)
    commandLine(
        "python3",
        layout.projectDirectory.file("tools/prepare_extension.py").asFile.absolutePath,
        "--source", extensionSource.asFile.absolutePath,
        "--output", unpackedOutput.get().asFile.absolutePath,
        "--extension-version", extensionVersion,
        "--app-version", androidAppVersion,
        "--application-id", "com.mikeyphw.xdm.android",
        "--channel", "debug",
        "--xdm-scheme", "xdmdownload",
        "--default-target", "ask",
        "--theme", "dark",
    )
}

val jsTest = tasks.register<Exec>("jsTest") {
    group = "verification"
    description = "Run detector, media-locator, handoff, FAB, and background JavaScript tests."
    commandLine(
        "bash", "-lc",
        """
        set -euo pipefail
        if [[ -n "${'$'}{XDM_NODE:-}" && -x "${'$'}XDM_NODE" ]]; then
            NODE_RUNTIME="${'$'}XDM_NODE"
        elif command -v node >/dev/null 2>&1; then
            NODE_RUNTIME="${'$'}(command -v node)"
        elif command -v nodejs >/dev/null 2>&1; then
            NODE_RUNTIME="${'$'}(command -v nodejs)"
        elif [[ -x /home/linuxbrew/.linuxbrew/opt/node@22/bin/node ]]; then
            NODE_RUNTIME=/home/linuxbrew/.linuxbrew/opt/node@22/bin/node
        else
            echo "ERROR: Node.js is required for :browser-extension:jsTest." >&2
            exit 127
        fi
        echo "Using Node.js runtime: ${'$'}NODE_RUNTIME"
        "${'$'}NODE_RUNTIME" --version
        "${'$'}NODE_RUNTIME" tests/test_detector.js
        "${'$'}NODE_RUNTIME" tests/test_handoff.js
        "${'$'}NODE_RUNTIME" tests/test_secure_handoff.js
        "${'$'}NODE_RUNTIME" tests/test_fab.js
        "${'$'}NODE_RUNTIME" tests/test_phase43a_bridge.js
        "${'$'}NODE_RUNTIME" tests/test_background.js
        "${'$'}NODE_RUNTIME" tests/test_release_gate.js
        """.trimIndent(),
    )
}

val validateFirefoxExtension = tasks.register<Exec>("validateFirefoxExtension") {
    group = "verification"
    description = "Validate the rendered unpacked Firefox extension and source contracts."
    dependsOn(prepareFirefoxExtension, jsTest, tasks.named("test"))
    inputs.dir(unpackedOutput)
    commandLine("python3", layout.projectDirectory.file("tools/validate_extension.py").asFile.absolutePath, unpackedOutput.get().asFile.absolutePath)
}

fun registerXpiTask(name: String, theme: String) = tasks.register<JavaExec>(name) {
    group = "browser extension"
    description = "Build the deterministic $theme Firefox XPI with the shared runtime generator."
    dependsOn(tasks.named("classes"), validateFirefoxExtension)
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.mikeyphw.xdm.android.browserextension.BrowserExtensionPackageCli")
    val outputFile = xpiOutput.map { it.file("XDM-Android-Firefox-$extensionVersion-release-$theme.xpi") }
    outputs.file(outputFile)
    args(
        "--output", outputFile.get().asFile.absolutePath,
        "--extension-version", extensionVersion,
        "--app-version", androidAppVersion,
        "--application-id", "com.mikeyphw.xdm.android",
        "--channel", "release",
        "--xdm-scheme", "xdmdownload",
        "--default-target", "xdm",
        "--theme", theme,
    )
}

val packageFirefoxExtensionDark = registerXpiTask("packageFirefoxExtensionDark", "dark")
val packageFirefoxExtensionAmoled = registerXpiTask("packageFirefoxExtensionAmoled", "amoled")
val packageFirefoxExtension = tasks.register("packageFirefoxExtension") {
    group = "browser extension"
    description = "Build the default deterministic dark Firefox XPI."
    dependsOn(packageFirefoxExtensionDark)
}

val verifyFirefoxExtensionReleaseArtifacts = tasks.register<Exec>("verifyFirefoxExtensionReleaseArtifacts") {
    group = "verification"
    description = "Verify deterministic Dark and AMOLED release XPIs and write release-artifacts.json."
    dependsOn(packageFirefoxExtensionDark, packageFirefoxExtensionAmoled)
    val metadataFile = xpiOutput.map { it.file("release-artifacts.json") }
    inputs.files(
        xpiOutput.map { it.file("XDM-Android-Firefox-$extensionVersion-release-dark.xpi") },
        xpiOutput.map { it.file("XDM-Android-Firefox-$extensionVersion-release-amoled.xpi") },
    )
    outputs.file(metadataFile)
    commandLine(
        "python3",
        layout.projectDirectory.file("tools/verify_release_artifacts.py").asFile.absolutePath,
        "--output-dir", xpiOutput.get().asFile.absolutePath,
        "--metadata", metadataFile.get().asFile.absolutePath,
        "--extension-version", extensionVersion,
        "--app-version", androidAppVersion,
    )
}

val browserExtensionReleaseGate = tasks.register("browserExtensionReleaseGate") {
    group = "verification"
    description = "Build and verify deterministic Firefox release XPIs using the keyless v3 locator contract."
    dependsOn(verifyFirefoxExtensionReleaseArtifacts)
}

tasks.named("check") { dependsOn(validateFirefoxExtension) }
