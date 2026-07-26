plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(libs.junit)
}

val extensionSource = layout.projectDirectory.dir("src/main/extension/xdm-firefox")
val unpackedOutput = layout.buildDirectory.dir("firefox/unpacked")

val prepareFirefoxExtension by tasks.registering(Exec::class) {
    group = "browser extension"
    description = "Render the development Firefox extension into build/firefox/unpacked without creating an XPI."
    inputs.dir(extensionSource)
    outputs.dir(unpackedOutput)
    commandLine(
        "python3",
        layout.projectDirectory.file("tools/prepare_extension.py").asFile.absolutePath,
        "--source", extensionSource.asFile.absolutePath,
        "--output", unpackedOutput.get().asFile.absolutePath,
        "--extension-version", "1.0.0",
        "--application-id", "com.mikeyphw.xdm.android",
        "--channel", "release",
        "--xdm-scheme", "xdmdownload",
        "--default-target", "xdm",
    )
}

val jsTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Run the repository-owned detector, candidate-store, and handoff JavaScript tests."
    commandLine(
        "bash", "-lc",
        "node tests/test_detector.js && node tests/test_handoff.js && node tests/test_background.js",
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

tasks.named("check") {
    dependsOn(validateFirefoxExtension)
}
