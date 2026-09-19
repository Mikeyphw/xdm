plugins {
    alias(libs.plugins.android.library)
}

val requireFfmpegRuntime = providers.gradleProperty("xdm.requireFfmpegRuntime")
    .map(String::toBoolean)
    .orElse(false)

val installPinnedFfmpegRuntime = tasks.register<Exec>("installPinnedFfmpegRuntime") {
    group = "build setup"
    description = "Builds the pinned ARM64 FFmpeg/FFprobe runtime with OpenSSL using Android NDK 29."
    workingDir(rootProject.projectDir)
    commandLine("python3", "tools/install-ffmpeg-runtime.py", "--build-pinned")
    inputs.files(
        layout.projectDirectory.file("runtime/ffmpeg-runtime.json"),
        rootProject.layout.projectDirectory.file("tools/install-ffmpeg-runtime.py"),
        rootProject.layout.projectDirectory.file("tools/android_elf_runtime.py"),
    )
    outputs.files(
        layout.projectDirectory.file("src/main/jniLibs/arm64-v8a/libxdm_ffmpeg.so"),
        layout.projectDirectory.file("src/main/jniLibs/arm64-v8a/libxdm_ffprobe.so"),
        layout.projectDirectory.file("runtime/ffmpeg-runtime.lock.json"),
        layout.projectDirectory.file("runtime/licenses/FFmpeg-LGPL-2.1.txt"),
        layout.projectDirectory.file("runtime/licenses/OpenSSL-Apache-2.0.txt"),
    )
}

android {
    namespace = "com.mikeyphw.xdm.android.media.ffmpeg"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    packaging {
        jniLibs.useLegacyPackaging = true
        jniLibs.keepDebugSymbols += setOf("**/libxdm_ffmpeg.so", "**/libxdm_ffprobe.so")
    }
    lint {
        abortOnError = true
        warningsAsErrors = true
        disable += "GradleDependency"
        if (!requireFfmpegRuntime.get()) disable += "Aligned16KB"
    }
}

// AGP 9.2 no longer guarantees that the legacy AndroidLibrarySourceSet API
// matches the decorated source-set implementation. Register the runtime assets
// through the stable variant sources API instead of casting the legacy container.
androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addStaticSourceDirectory("runtime")
    }
}

// The native runtime is optional for ordinary source/debug builds. Distribution graphs opt in
// with -Pxdm.requireFfmpegRuntime=true, at which point generated assets/JNI inputs are installed
// before packaging. This prevents a normal Gradle sync/assemble from unexpectedly compiling FFmpeg.
if (requireFfmpegRuntime.get()) {
    tasks.matching { task ->
        task.name.startsWith("merge") &&
            (task.name.endsWith("Assets") || task.name.contains("JniLib", ignoreCase = true))
    }.configureEach {
        dependsOn(installPinnedFfmpegRuntime)
    }
}

// When a final validation graph also contains native installation/package work, lint must never
// inspect this generated module concurrently. `mustRunAfter` avoids forcing a native build for a
// standalone lint invocation while deterministically ordering the combined FF04 release graph.
tasks.matching { task -> task.name.contains("lint", ignoreCase = true) }.configureEach {
    mustRunAfter(installPinnedFfmpegRuntime)
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

val verifyFfmpegRuntime = tasks.register<Exec>("verifyFfmpegRuntime") {
    group = "verification"
    if (requireFfmpegRuntime.get()) dependsOn(installPinnedFfmpegRuntime)
    mustRunAfter(installPinnedFfmpegRuntime)
    description = "Verifies the pinned FFmpeg/FFprobe runtime, provenance lock, ABI and 16 KB alignment."
    workingDir(rootProject.projectDir)
    inputs.files(
        layout.projectDirectory.file("runtime/ffmpeg-runtime.json"),
        layout.projectDirectory.file("runtime/ffmpeg-runtime.lock.json"),
        layout.projectDirectory.file("runtime/licenses/FFmpeg-LGPL-2.1.txt"),
        layout.projectDirectory.file("runtime/licenses/OpenSSL-Apache-2.0.txt"),
        layout.projectDirectory.file("src/main/jniLibs/arm64-v8a/libxdm_ffmpeg.so"),
        layout.projectDirectory.file("src/main/jniLibs/arm64-v8a/libxdm_ffprobe.so"),
        rootProject.layout.projectDirectory.file("tools/verify-ffmpeg-runtime.py"),
        rootProject.layout.projectDirectory.file("tools/android_elf_runtime.py"),
    )
    inputs.property("requireFfmpegRuntime", requireFfmpegRuntime)
    val successMarker = layout.buildDirectory.file("validation/verifyFfmpegRuntime.success")
    outputs.file(successMarker)
    commandLine(
        "python3",
        "tools/verify-ffmpeg-runtime.py",
        *if (requireFfmpegRuntime.get()) arrayOf("--require-payload", "--require-16kb-alignment") else emptyArray(),
    )
    doLast {
        val marker = outputs.files.singleFile
        marker.parentFile.mkdirs()
        marker.writeText("ok\n")
    }
}

val verifyFfmpegReleaseRuntime = tasks.register<Exec>("verifyFfmpegReleaseRuntime") {
    group = "verification"
    if (requireFfmpegRuntime.get()) dependsOn(installPinnedFfmpegRuntime)
    mustRunAfter(installPinnedFfmpegRuntime)
    description = "Requires the already-built attested FFmpeg/FFprobe payload for direct release packaging without triggering a source build."
    workingDir(rootProject.projectDir)
    inputs.files(
        layout.projectDirectory.file("runtime/ffmpeg-runtime.json"),
        layout.projectDirectory.file("runtime/ffmpeg-runtime.lock.json"),
        layout.projectDirectory.file("runtime/licenses/FFmpeg-LGPL-2.1.txt"),
        layout.projectDirectory.file("runtime/licenses/OpenSSL-Apache-2.0.txt"),
        layout.projectDirectory.file("src/main/jniLibs/arm64-v8a/libxdm_ffmpeg.so"),
        layout.projectDirectory.file("src/main/jniLibs/arm64-v8a/libxdm_ffprobe.so"),
        rootProject.layout.projectDirectory.file("tools/verify-ffmpeg-runtime.py"),
        rootProject.layout.projectDirectory.file("tools/android_elf_runtime.py"),
    )
    commandLine("python3", "tools/verify-ffmpeg-runtime.py", "--require-payload", "--require-16kb-alignment")
}

tasks.matching { it.name == "preDebugBuild" }.configureEach {
    dependsOn(verifyFfmpegRuntime)
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(verifyFfmpegReleaseRuntime)
}
