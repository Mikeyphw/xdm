plugins {
    alias(libs.plugins.android.library)
}

val requireAlignedAria2Runtime = providers.gradleProperty("xdm.requireAria2Runtime")
    .map(String::toBoolean)
    .orElse(false)
val installCompatibilityAria2Runtime = tasks.register<Exec>("installCompatibilityAria2Runtime") {
    group = "build setup"
    description = "Installs the pinned legacy aria2 Android prebuilt for non-release compatibility only."
    workingDir(rootProject.projectDir)
    commandLine("python3", "tools/install-aria2-runtime.py", "--download-official", "--require-trusted-digest")
    inputs.files(
        layout.projectDirectory.file("runtime/aria2-runtime.json"),
        rootProject.layout.projectDirectory.file("tools/install-aria2-runtime.py"),
        rootProject.layout.projectDirectory.file("tools/android_elf_runtime.py"),
    )
    outputs.files(
        layout.projectDirectory.file("src/main/jniLibs/arm64-v8a/libaria2c.so"),
        layout.projectDirectory.file("runtime/aria2-runtime.lock.json"),
    )
}

val buildPinnedAria2Runtime = tasks.register<Exec>("buildPinnedAria2Runtime") {
    group = "build setup"
    description = "Builds XDM's release-qualified ARM64 aria2 runtime from pinned sources."
    workingDir(rootProject.projectDir)
    commandLine("python3", "tools/build-aria2-runtime.py", "--build-pinned")
    inputs.files(
        layout.projectDirectory.file("runtime/aria2-runtime.json"),
        layout.projectDirectory.file("runtime/licenses/GPL-2.0.txt"),
        layout.projectDirectory.file("runtime/licenses/SOURCE-NOTICE.txt"),
        rootProject.layout.projectDirectory.file("tools/build-aria2-runtime.py"),
        rootProject.layout.projectDirectory.file("tools/install-ffmpeg-runtime.py"),
        rootProject.layout.projectDirectory.file("tools/android_elf_runtime.py"),
    )
    outputs.files(
        layout.projectDirectory.file("src/main/jniLibs/arm64-v8a/libaria2c.so"),
        layout.projectDirectory.file("runtime/aria2-runtime.lock.json"),
    )
}


android {
    namespace = "com.mikeyphw.xdm.android.transfer.aria2"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    packaging {
        jniLibs.useLegacyPackaging = true
        jniLibs.keepDebugSymbols += "**/libaria2c.so"
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
    lint {
        abortOnError = true
        warningsAsErrors = true
        disable += "GradleDependency"
        if (!requireAlignedAria2Runtime.get()) {
            // Optional direct Gradle dev/debug builds may omit the native payload entirely.
            // Strict distribution graphs keep the alignment check enabled via
            // -Pxdm.requireAria2Runtime=true.
            disable += "Aligned16KB"
        }
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addStaticSourceDirectory("runtime")
    }
}

// Keep aria2 optional for ordinary source/debug builds. Distribution graphs explicitly opt in
// with -Pxdm.requireAria2Runtime=true and therefore cannot consume an unpinned upstream binary.
if (requireAlignedAria2Runtime.get()) {
    tasks.matching { task ->
        task.name.startsWith("merge") && task.name.contains("JniLib", ignoreCase = true)
    }.configureEach {
        dependsOn(buildPinnedAria2Runtime)
    }
}

dependencies {
    implementation(project(":core-model"))
    implementation(project(":core-utils"))
    implementation(project(":transfer-api"))
    implementation(project(":storage"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}


val verifyAria2Runtime = tasks.register<Exec>("verifyAria2Runtime") {
    group = "verification"
    if (requireAlignedAria2Runtime.get()) dependsOn(buildPinnedAria2Runtime)
    mustRunAfter(buildPinnedAria2Runtime)
    description = "Verifies the attested ARM64 aria2 runtime when present or required."
    workingDir(rootProject.projectDir)
    inputs.files(
        layout.projectDirectory.file("runtime/aria2-runtime.json"),
        layout.projectDirectory.file("runtime/aria2-runtime.lock.json"),
        layout.projectDirectory.file("src/main/jniLibs/arm64-v8a/libaria2c.so"),
        rootProject.layout.projectDirectory.file("tools/verify-aria2-runtime.py"),
        rootProject.layout.projectDirectory.file("tools/android_elf_runtime.py"),
    )
    inputs.property("requireAlignedAria2Runtime", requireAlignedAria2Runtime)
    val successMarker = layout.buildDirectory.file("validation/verifyAria2Runtime.success")
    outputs.file(successMarker)
    val verificationCommand = mutableListOf(
        "python3", "tools/verify-aria2-runtime.py",
        "--success-marker", successMarker.get().asFile.absolutePath,
    )
    if (requireAlignedAria2Runtime.get()) {
        verificationCommand += listOf("--require-payload", "--require-16kb-alignment", "--require-source-build")
    }
    commandLine(verificationCommand)
}


val verifyAria2ReleaseRuntime = tasks.register<Exec>("verifyAria2ReleaseRuntime") {
    group = "verification"
    if (requireAlignedAria2Runtime.get()) dependsOn(buildPinnedAria2Runtime)
    mustRunAfter(buildPinnedAria2Runtime)
    description = "Requires XDM source-built aria2 bytes with schema-v3 native provenance."
    workingDir(rootProject.projectDir)
    inputs.files(
        layout.projectDirectory.file("runtime/aria2-runtime.json"),
        layout.projectDirectory.file("runtime/aria2-runtime.lock.json"),
        layout.projectDirectory.file("runtime/licenses/GPL-2.0.txt"),
        layout.projectDirectory.file("runtime/licenses/SOURCE-NOTICE.txt"),
        layout.projectDirectory.file("src/main/jniLibs/arm64-v8a/libaria2c.so"),
        rootProject.layout.projectDirectory.file("tools/verify-aria2-runtime.py"),
        rootProject.layout.projectDirectory.file("tools/build-aria2-runtime.py"),
        rootProject.layout.projectDirectory.file("tools/install-ffmpeg-runtime.py"),
        rootProject.layout.projectDirectory.file("tools/android_elf_runtime.py"),
    )
    commandLine(
        "python3", "tools/verify-aria2-runtime.py",
        "--require-payload", "--require-16kb-alignment", "--require-source-build",
    )
}

val verifyRm07Aria2NativeRuntime = tasks.register<Exec>("verifyRm07Aria2NativeRuntime") {
    group = "verification"
    description = "Runs XGO-RM07 source/provenance and ELF-policy regressions without building the network payload."
    workingDir(rootProject.projectDir)
    commandLine("python3", "tools/test-rm07-aria2-native-runtime.py")
    inputs.files(
        layout.projectDirectory.file("runtime/aria2-runtime.json"),
        rootProject.layout.projectDirectory.file("tools/build-aria2-runtime.py"),
        rootProject.layout.projectDirectory.file("tools/verify-aria2-runtime.py"),
        rootProject.layout.projectDirectory.file("tools/android_elf_runtime.py"),
        layout.projectDirectory.file("build.gradle.kts"),
    )
}


tasks.matching { it.name == "preDebugBuild" }.configureEach {
    dependsOn(verifyAria2Runtime)
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(verifyAria2ReleaseRuntime)
}

