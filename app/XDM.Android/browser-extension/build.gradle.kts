plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

sourceSets {
    main {
        resources.srcDir("src/main/extension")
    }
}

dependencies {
    testImplementation(libs.junit)
}

val extensionSource = layout.projectDirectory.dir("src/main/extension/xdm-firefox")
val unpackedOutput = layout.buildDirectory.dir("firefox/unpacked")
val xpiOutput = layout.buildDirectory.dir("outputs/xpi")

val prepareFirefoxExtension by tasks.registering(Exec::class) {
    group = "browser extension"
    description = "Render the development Firefox extension into build/firefox/unpacked."
    inputs.dir(extensionSource)
    outputs.dir(unpackedOutput)
    commandLine(
        "python3",
        layout.projectDirectory.file("tools/prepare_extension.py").asFile.absolutePath,
        "--source", extensionSource.asFile.absolutePath,
        "--output", unpackedOutput.get().asFile.absolutePath,
        "--extension-version", "1.2.0",
        "--app-version", "0.20.0-rc08",
        "--application-id", "com.mikeyphw.xdm.android",
        "--channel", "release",
        "--xdm-scheme", "xdmdownload",
        "--default-target", "xdm",
        "--theme", "dark",
    )
}

val jsTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Run the repository-owned detector, candidate-store, and handoff JavaScript tests."
    commandLine(
        "bash", "-lc",
        "node tests/test_detector.js && node tests/test_handoff.js && node tests/test_secure_handoff.js && node tests/test_fab.js && node tests/test_phase43a_bridge.js && node tests/test_background.js && node tests/test_release_gate.js",
    )
}

val validateFirefoxExtension by tasks.registering(Exec::class) {
    group = "verification"
    description = "Validate the rendered unpacked Firefox extension and source contracts."
    dependsOn(prepareFirefoxExtension, jsTest, tasks.named("test"))
    inputs.dir(unpackedOutput)
    commandLine(
        "python3",
        layout.projectDirectory.file("tools/validate_extension.py").asFile.absolutePath,
        unpackedOutput.get().asFile.absolutePath,
    )
}

fun registerXpiTask(
    name: String,
    theme: String,
) = tasks.register<JavaExec>(name) {
    group = "browser extension"
    description = "Build the deterministic $theme Firefox XPI with the shared runtime generator."
    dependsOn(tasks.named("classes"), validateFirefoxExtension)
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.mikeyphw.xdm.android.browserextension.BrowserExtensionPackageCli")
    val outputFile = xpiOutput.map { it.file("XDM-Android-Firefox-1.2.0-release-$theme.xpi") }
    outputs.file(outputFile)
    args(
        "--output", outputFile.get().asFile.absolutePath,
        "--extension-version", "1.2.0",
        "--app-version", "0.20.0-rc08",
        "--application-id", "com.mikeyphw.xdm.android",
        "--channel", "release",
        "--xdm-scheme", "xdmdownload",
        "--default-target", "xdm",
        "--theme", theme,
    )
}

val packageFirefoxExtensionDark = registerXpiTask("packageFirefoxExtensionDark", "dark")
val packageFirefoxExtensionAmoled = registerXpiTask("packageFirefoxExtensionAmoled", "amoled")

val packageFirefoxExtension by tasks.registering {
    group = "browser extension"
    description = "Build the default deterministic dark Firefox XPI."
    dependsOn(packageFirefoxExtensionDark)
}

val verifyFirefoxExtensionReleaseArtifacts by tasks.registering(Exec::class) {
    group = "verification"
    description = "Verify deterministic Dark and AMOLED release XPIs and write release-artifacts.json."
    dependsOn(packageFirefoxExtensionDark, packageFirefoxExtensionAmoled)
    val metadataFile = xpiOutput.map { it.file("release-artifacts.json") }
    inputs.files(
        xpiOutput.map { it.file("XDM-Android-Firefox-1.2.0-release-dark.xpi") },
        xpiOutput.map { it.file("XDM-Android-Firefox-1.2.0-release-amoled.xpi") },
    )
    outputs.file(metadataFile)
    commandLine(
        "python3",
        layout.projectDirectory.file("tools/verify_release_artifacts.py").asFile.absolutePath,
        "--output-dir", xpiOutput.get().asFile.absolutePath,
        "--metadata", metadataFile.get().asFile.absolutePath,
    )
}

tasks.named("check") {
    dependsOn(validateFirefoxExtension, verifyFirefoxExtensionReleaseArtifacts)
}
