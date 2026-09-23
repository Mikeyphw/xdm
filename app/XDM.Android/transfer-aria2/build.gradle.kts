plugins {
    alias(libs.plugins.android.library)
}

val requireAlignedAria2Runtime = providers.gradleProperty("xdm.requireAria2Runtime")
    .map(String::toBoolean)
    .orElse(false)
val trustedAria2ArchiveSha256 = providers.gradleProperty("xdm.aria2.archiveSha256")
    .orElse(providers.environmentVariable("XDM_ARIA2_ARCHIVE_SHA256"))

val installOfficialAria2Runtime = tasks.register<Exec>("installOfficialAria2Runtime") {
    group = "build setup"
    description = "Installs the pinned official ARM64 aria2 runtime payload incrementally."
    workingDir(rootProject.projectDir)
    val trustedDigestProvider = trustedAria2ArchiveSha256
    inputs.property("trustedAria2ArchiveSha256", trustedDigestProvider.orElse(""))
    doFirst {
        val trustedDigest = trustedDigestProvider.orNull
            ?: throw GradleException("XDM_ARIA2_ARCHIVE_SHA256 or -Pxdm.aria2.archiveSha256 is required to install distributable aria2 bytes")
        require(Regex("^[0-9A-Fa-f]{64}$").matches(trustedDigest)) { "Trusted aria2 archive SHA-256 must be 64 hex characters" }
        commandLine(
            "python3", "tools/install-aria2-runtime.py", "--download-official",
            "--expected-archive-sha256", trustedDigest, "--require-trusted-digest",
        )
    }
    inputs.files(
        layout.projectDirectory.file("runtime/aria2-runtime.json"),
        rootProject.layout.projectDirectory.file("tools/install-aria2-runtime.py"),
        rootProject.layout.projectDirectory.file("tools/android_elf_runtime.py"),
    )
    outputs.files(
        layout.projectDirectory.file("src/main/jniLibs/arm64-v8a/libaria2c.so"),
        layout.projectDirectory.file("runtime/aria2-runtime.lock.json"),
        layout.projectDirectory.file("runtime/licenses/GPL-2.0.txt"),
        layout.projectDirectory.file("runtime/licenses/SOURCE-NOTICE.txt"),
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
            // Optional dev/debug builds may carry the currently pinned upstream aria2 payload,
            // which is not guaranteed to be 16 KB ELF-page aligned. Strict distribution
            // builds keep the check enabled via -Pxdm.requireAria2Runtime=true.
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
        dependsOn(installOfficialAria2Runtime)
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
    if (requireAlignedAria2Runtime.get()) dependsOn(installOfficialAria2Runtime)
    description = "Verifies the attested ARM64 aria2 runtime when present or required."
    workingDir(rootProject.projectDir)
    val requireRuntimeProvider = requireAlignedAria2Runtime
    val trustedDigestProvider = trustedAria2ArchiveSha256
    inputs.files(
        layout.projectDirectory.file("runtime/aria2-runtime.json"),
        layout.projectDirectory.file("runtime/aria2-runtime.lock.json"),
        layout.projectDirectory.file("src/main/jniLibs/arm64-v8a/libaria2c.so"),
        rootProject.layout.projectDirectory.file("tools/verify-aria2-runtime.py"),
        rootProject.layout.projectDirectory.file("tools/android_elf_runtime.py"),
    )
    inputs.property("requireAlignedAria2Runtime", requireRuntimeProvider)
    inputs.property("trustedAria2ArchiveSha256", trustedDigestProvider.orElse(""))
    val successMarker = layout.buildDirectory.file("validation/verifyAria2Runtime.success")
    outputs.file(successMarker)
    doFirst {
        val arguments = mutableListOf("python3", "tools/verify-aria2-runtime.py")
        if (requireRuntimeProvider.get()) {
            val trustedDigest = trustedDigestProvider.orNull
                ?: throw GradleException("Strict aria2 verification requires XDM_ARIA2_ARCHIVE_SHA256 or -Pxdm.aria2.archiveSha256")
            arguments += listOf(
                "--require-payload", "--require-16kb-alignment",
                "--require-trusted-archive-digest", "--expected-archive-sha256", trustedDigest,
            )
        }
        commandLine(*arguments.toTypedArray())
    }
    doLast {
        val marker = successMarker.get().asFile
        marker.parentFile.mkdirs()
        marker.writeText("ok\n")
    }
}

val verifyAria2ReleaseRuntime = tasks.register<Exec>("verifyAria2ReleaseRuntime") {
    group = "verification"
    if (requireAlignedAria2Runtime.get()) dependsOn(installOfficialAria2Runtime)
    description = "Requires an already-installed aria2 payload bound to the trusted release digest; never downloads during direct release packaging."
    workingDir(rootProject.projectDir)
    val trustedDigestProvider = trustedAria2ArchiveSha256
    inputs.files(
        layout.projectDirectory.file("runtime/aria2-runtime.json"),
        layout.projectDirectory.file("runtime/aria2-runtime.lock.json"),
        layout.projectDirectory.file("runtime/licenses/GPL-2.0.txt"),
        layout.projectDirectory.file("runtime/licenses/SOURCE-NOTICE.txt"),
        layout.projectDirectory.file("src/main/jniLibs/arm64-v8a/libaria2c.so"),
        rootProject.layout.projectDirectory.file("tools/verify-aria2-runtime.py"),
        rootProject.layout.projectDirectory.file("tools/android_elf_runtime.py"),
    )
    inputs.property("trustedAria2ArchiveSha256", trustedDigestProvider.orElse(""))
    doFirst {
        val trustedDigest = trustedDigestProvider.orNull
            ?: throw GradleException("Direct release packaging requires XDM_ARIA2_ARCHIVE_SHA256 or -Pxdm.aria2.archiveSha256; install the pinned runtime explicitly before packaging")
        require(Regex("^[0-9A-Fa-f]{64}$").matches(trustedDigest)) { "Trusted aria2 archive SHA-256 must be 64 hex characters" }
        commandLine(
            "python3", "tools/verify-aria2-runtime.py",
            "--require-payload", "--require-16kb-alignment",
            "--require-trusted-archive-digest", "--expected-archive-sha256", trustedDigest,
        )
    }
}

tasks.matching { it.name == "preDebugBuild" }.configureEach {
    dependsOn(verifyAria2Runtime)
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(verifyAria2ReleaseRuntime)
}

