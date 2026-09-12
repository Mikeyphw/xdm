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
    outputs.files(
        layout.projectDirectory.file("src/main/jniLibs/arm64-v8a/libxdm_ffmpeg.so"),
        layout.projectDirectory.file("src/main/jniLibs/arm64-v8a/libxdm_ffprobe.so"),
        layout.projectDirectory.file("runtime/ffmpeg-runtime.lock.json"),
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
    sourceSets.getByName("main").assets.srcDir("runtime")
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

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

val verifyFfmpegRuntime = tasks.register<Exec>("verifyFfmpegRuntime") {
    group = "verification"
    description = "Verifies the pinned FFmpeg/FFprobe runtime, provenance lock, ABI and 16 KB alignment."
    workingDir(rootProject.projectDir)
    commandLine(
        "python3",
        "tools/verify-ffmpeg-runtime.py",
        *if (requireFfmpegRuntime.get()) arrayOf("--require-payload", "--require-16kb-alignment") else emptyArray(),
    )
}

tasks.matching { it.name in setOf("preDebugBuild", "preReleaseBuild") }.configureEach {
    dependsOn(verifyFfmpegRuntime)
}
