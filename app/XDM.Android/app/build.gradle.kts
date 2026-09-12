import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.Exec

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

fun releaseSigningValue(propertyName: String, environmentName: String): String? =
    providers.gradleProperty(propertyName)
        .orElse(providers.environmentVariable(environmentName))
        .orNull
        ?.takeIf { it.isNotBlank() }

val releaseStoreFile = releaseSigningValue("xdm.release.storeFile", "XDM_RELEASE_STORE_FILE")
val releaseStorePassword = releaseSigningValue("xdm.release.storePassword", "XDM_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = releaseSigningValue("xdm.release.keyAlias", "XDM_RELEASE_KEY_ALIAS")
val releaseKeyPassword = releaseSigningValue("xdm.release.keyPassword", "XDM_RELEASE_KEY_PASSWORD")
val releaseBuildId = releaseSigningValue("xdm.release.buildId", "XDM_RELEASE_BUILD_ID")
    ?: providers.environmentVariable("GITHUB_SHA").orNull?.take(12)
    ?: "local-dev"
val pinnedReleaseSignerSha256 = releaseSigningValue("xdm.release.signerSha256", "XDM_RELEASE_SIGNER_SHA256")
val releaseCertificateNotAfter = releaseSigningValue("xdm.release.certificateNotAfter", "XDM_RELEASE_CERTIFICATE_NOT_AFTER")

fun buildConfigString(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
val hasReleaseSigning = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { it != null }

val requireAlignedAria2Runtime = providers.gradleProperty("xdm.requireAria2Runtime")
    .map(String::toBoolean)
    .orElse(false)
val requireAlignedFfmpegRuntime = providers.gradleProperty("xdm.requireFfmpegRuntime")
    .map(String::toBoolean)
    .orElse(false)

fun validationEvidence(propertyName: String): Boolean = providers.gradleProperty(propertyName)
    .map(String::toBoolean)
    .orElse(false)
    .get()

val staticValidationPassed = validationEvidence("xdm.validation.staticPassed")
val fullValidationPassed = validationEvidence("xdm.validation.fullPassed")
val realDeviceSmokePassed = validationEvidence("xdm.validation.realDeviceSmokePassed")
val aria2PayloadVerified = validationEvidence("xdm.validation.aria2PayloadVerified")
val ffmpegPayloadVerified = validationEvidence("xdm.validation.ffmpegPayloadVerified")
val diagnosticExportValidated = validationEvidence("xdm.validation.diagnosticExportPassed")
val releaseDocsValidated = validationEvidence("xdm.validation.releaseDocsPassed")
val routeTopologyValidated = validationEvidence("xdm.validation.routeTopologyPassed")
val lintValidationPassed = validationEvidence("xdm.validation.lintPassed")
val nativeSymbolsValidated = validationEvidence("xdm.validation.nativeSymbolsPassed")

android {
    namespace = "com.mikeyphw.xdm.android"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.mikeyphw.xdm.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 22
        versionName = "0.21.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["xdmBrowserScheme"] = "xdmdownload"
        buildConfigField("String", "XDM_BROWSER_SCHEME", "\"xdmdownload\"")
        buildConfigField("String", "XDM_RELEASE_BUILD_ID", buildConfigString(releaseBuildId))
        buildConfigField("String", "XDM_PINNED_RELEASE_SIGNER_SHA256", buildConfigString(pinnedReleaseSignerSha256 ?: "UNPINNED"))
        buildConfigField("String", "XDM_RELEASE_CERTIFICATE_NOT_AFTER", buildConfigString(releaseCertificateNotAfter ?: "UNKNOWN"))
        buildConfigField("Boolean", "XDM_RELEASE_SIGNING_CONFIGURED", hasReleaseSigning.toString())
        // Validation evidence is fail-closed. Release tooling must opt in only after the named gate has passed.
        buildConfigField("Boolean", "XDM_STATIC_VALIDATION_PASSED", staticValidationPassed.toString())
        buildConfigField("Boolean", "XDM_FULL_VALIDATION_PASSED", fullValidationPassed.toString())
        buildConfigField("Boolean", "XDM_REAL_DEVICE_SMOKE_PASSED", realDeviceSmokePassed.toString())
        buildConfigField("Boolean", "XDM_ARIA2_PAYLOAD_VERIFIED", aria2PayloadVerified.toString())
        buildConfigField("Boolean", "XDM_FFMPEG_PAYLOAD_VERIFIED", ffmpegPayloadVerified.toString())
        buildConfigField("Boolean", "XDM_DIAGNOSTIC_EXPORT_VALIDATED", diagnosticExportValidated.toString())
        buildConfigField("Boolean", "XDM_RELEASE_DOCS_VALIDATED", releaseDocsValidated.toString())
        buildConfigField("Boolean", "XDM_ROUTE_TOPOLOGY_VALIDATED", routeTopologyValidated.toString())
        buildConfigField("Boolean", "XDM_LINT_VALIDATION_PASSED", lintValidationPassed.toString())
        buildConfigField("Boolean", "XDM_NATIVE_SYMBOLS_VALIDATED", nativeSymbolsValidated.toString())
        buildConfigField("Boolean", "XDM_ARIA2_PAYLOAD_GATE_CONFIGURED", "true")
        buildConfigField("Boolean", "XDM_FFMPEG_PAYLOAD_GATE_CONFIGURED", "true")
        ndk {
            abiFilters += setOf("arm64-v8a")
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            manifestPlaceholders["xdmBrowserScheme"] = "xdmdownload-debug"
            buildConfigField("String", "XDM_BROWSER_SCHEME", "\"xdmdownload-debug\"")
        }
        getByName("release") {
            isMinifyEnabled = false
            manifestPlaceholders["xdmBrowserScheme"] = "xdmdownload"
            buildConfigField("String", "XDM_BROWSER_SCHEME", "\"xdmdownload\"")
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }

        create("developmentUnsigned") {
            initWith(getByName("debug"))
            matchingFallbacks += listOf("debug")
            applicationIdSuffix = ".devunsigned"
            versionNameSuffix = "-unsigned-dev"
            isDebuggable = true
            signingConfig = null
            manifestPlaceholders["xdmBrowserScheme"] = "xdmdownload-devunsigned"
            buildConfigField("String", "XDM_BROWSER_SCHEME", "\"xdmdownload-devunsigned\"")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21 }
    packaging {
        jniLibs.useLegacyPackaging = true
        // Keep app-owned attested runtimes plus the two AndroidX dependency payloads that AGP's
        // x86_64 strip helper cannot execute on native ARM64 Termux. This is an exact allowlist,
        // not broad symbol retention; AGP packages these dependency libraries unchanged instead of
        // repeatedly invoking the unavailable glibc/qemu strip path.
        jniLibs.keepDebugSymbols += setOf(
            "**/libaria2c.so",
            "**/libxdm_ffmpeg.so",
            "**/libxdm_ffprobe.so",
            "**/libandroidx.graphics.path.so",
            "**/libdatastore_shared_counter.so",
        )
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
    lint {
        abortOnError = true
        warningsAsErrors = true
        disable += setOf(
            "AndroidGradlePluginVersion",
            "DataExtractionRules",
            "GradleDependency",
            "MissingApplicationIcon",
            "OldTargetApi",
            // XDM currently ships only the attested arm64-v8a aria2 runtime; ChromeOS x86_64 is unsupported until a matching payload is validated.
            "ChromeOsAbiSupport",
            // Some existing checkouts still carry mipmap-anydpi-v26 launcher resources while minSdk is 26.
            // Treat this as a compatibility cleanup item instead of blocking unrelated media overlays.
            "ObsoleteSdkInt",
            "UseKtx",
        )
        if (!requireAlignedAria2Runtime.get() && !requireAlignedFfmpegRuntime.get()) {
            // Native payloads are optional for source-only developer compilation. Strict artifact
            // builds keep 16 KB validation enabled with either runtime requirement property.
            disable += "Aligned16KB"
        }
    }
}

val xdmAssertReleaseSigningInputs = tasks.register("xdmAssertReleaseSigningInputs") {
    group = "verification"
    description = "Fails publishable release builds unless release keystore inputs and signer pin metadata are present."
    doLast {
        require(hasReleaseSigning) { "Release signing inputs are required for assembleRelease/bundleRelease. Use assembleDevelopmentUnsigned for unsigned local handoff builds." }
        val storePath = requireNotNull(releaseStoreFile) { "xdm.release.storeFile or XDM_RELEASE_STORE_FILE is required" }
        require(file(storePath).isFile) { "Release keystore does not exist: $storePath" }
        require(!pinnedReleaseSignerSha256.isNullOrBlank()) { "xdm.release.signerSha256 or XDM_RELEASE_SIGNER_SHA256 is required for signer continuity" }
        require(Regex("^[0-9A-Fa-f]{64}$").matches(pinnedReleaseSignerSha256!!)) { "Pinned release signer SHA-256 must be 64 hex characters" }
    }
}

tasks.matching { it.name in setOf("assembleRelease", "bundleRelease") }.configureEach {
    dependsOn(xdmAssertReleaseSigningInputs)
}

dependencies {
    implementation(project(":core-model"))
    implementation(project(":core-utils"))
    implementation(project(":persistence"))
    implementation(project(":storage"))
    implementation(project(":transfer-api"))
    implementation(project(":transfer-native"))
    implementation(project(":transfer-aria2"))
    implementation(project(":scheduler"))
    implementation(project(":media"))
    implementation(project(":media-ffmpeg"))
    implementation(libs.okhttp)
    implementation(project(":diagnostics"))
    implementation(project(":browser-integration"))
    implementation(project(":browser-extension"))
    implementation(project(":tasker-plugin"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.icons)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.window)
    implementation(libs.androidx.webkit)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}


tasks.register<Exec>("verifyFfmpeg01EmbeddedRuntimeContract") {
    group = "verification"
    description = "Verify the FF01 embedded FFmpeg/FFprobe runtime and app-owned media execution contract."
    workingDir(rootProject.projectDir)
    commandLine("python3", "tools/validate-ffmpeg01-embedded-runtime-media-execution.py")
}


tasks.register<Exec>("verifyFfmpeg02MediaMuxHlsPostprocessingContract") {
    group = "verification"
    description = "Verify FF02 embedded adaptive mux, HLS finalization, progress, cancellation, atomic publication, and FFprobe correctness."
    workingDir(rootProject.projectDir)
    commandLine("python3", "tools/validate-ffmpeg02-media-mux-hls-postprocessing.py")
}


tasks.register<Exec>("verifyFfmpeg03RuntimeRoutingTermuxUiReliabilityContract") {
    group = "verification"
    description = "Verify FF03 runtime routing, safe Termux fallback, yt-dlp boundary, UI controls, and redacted diagnostics."
    workingDir(rootProject.projectDir)
    commandLine("python3", "tools/validate-ffmpeg03-runtime-routing-termux-ui-reliability.py")
}


tasks.register<Exec>("verifyFfmpeg04FullReleaseSeal") {
    group = "verification"
    description = "Verify the complete FF01-FF04 embedded media runtime release seal and no-Termux acceptance contract."
    workingDir(rootProject.projectDir)
    commandLine("python3", "tools/validate-ffmpeg04-full-release-seal.py")
}


tasks.register<Exec>("verifyFfmpegRoadmapPostSealHotfix") {
    group = "verification"
    description = "Verify post-seal FFmpeg roadmap execution ownership: native HLS production wiring and embedded-first local post-processing."
    workingDir(rootProject.projectDir)
    commandLine("python3", "tools/validate-ffmpeg-roadmap-postseal-hotfix.py")
}

tasks.register<Exec>("verifyFfmpegDebugApkRuntime") {
    group = "verification"
    description = "Build and verify the debug APK contains the exact attested 16 KB FFmpeg/FFprobe payload and licenses."
    dependsOn("assembleDebug")
    workingDir(rootProject.projectDir)
    commandLine(
        "python3", "tools/verify-ffmpeg-runtime.py",
        "--require-payload", "--require-16kb-alignment",
        "--apk", "app/build/outputs/apk/debug/app-debug.apk",
    )
}

// AGP registers assembleDebug after this build script body is evaluated. Configure the
// variant task lazily so project configuration never assumes it already exists, and make
// packaging depend on the freshly installed attested runtime rather than merely ordering it.
tasks.matching { it.name == "assembleDebug" }.configureEach {
    dependsOn(":media-ffmpeg:installPinnedFfmpegRuntime")
}


val finalRemediationStaticGate = tasks.register<Exec>("finalRemediationStaticGate") {
    group = "verification"
    description = "Run the canonical XDM final static release gate, including the UX13 end-to-end UI/UX seal."
    workingDir(rootProject.projectDir)
    commandLine("bash", "tools/run-final-release-gate.sh", "--ci")
}

// FF04 final validation is deliberately represented as one lifecycle task. The dependency
// graph still uses Gradle-native tasks, but the major roots are ordered so expensive lint model
// generation cannot overlap the native-runtime mutation/package stages on ARM64 Termux.
val verifyFfmpeg04FinalReleaseValidation = tasks.register("verifyFfmpeg04FinalReleaseValidation") {
    group = "verification"
    description = "Run the complete staged FF04 release validation: seal, unit tests, Android-test APK, runtime APK attestation, static gate, then lint."
    dependsOn(
        "verifyFfmpeg04FullReleaseSeal",
        "verifyFfmpegRoadmapPostSealHotfix",
        ":media-ffmpeg:testDebugUnitTest",
        ":media:test",
        "testDebugUnitTest",
        "assembleDebugAndroidTest",
        "verifyFfmpegDebugApkRuntime",
        finalRemediationStaticGate,
        "lintDebug",
    )
}

// Order the heavyweight roots. `mustRunAfter` is used in addition to dependencies because the
// lifecycle task intentionally keeps each existing validator/test task authoritative.
tasks.matching { it.name == "testDebugUnitTest" }.configureEach {
    mustRunAfter(":media-ffmpeg:testDebugUnitTest", ":media:test", "verifyFfmpeg04FullReleaseSeal", "verifyFfmpegRoadmapPostSealHotfix")
}
tasks.matching { it.name == "assembleDebugAndroidTest" }.configureEach {
    mustRunAfter("testDebugUnitTest")
}
tasks.matching { it.name == "verifyFfmpegDebugApkRuntime" }.configureEach {
    mustRunAfter("assembleDebugAndroidTest")
}
finalRemediationStaticGate.configure {
    mustRunAfter("verifyFfmpegDebugApkRuntime")
}

// Keep every lint task in every Android subproject behind the static/runtime stages. This closes
// the v7r1 parallel-build race where media-ffmpeg lint-model work could inspect generated JNI
// inputs while installPinnedFfmpegRuntime/package tasks were mutating the same module.
rootProject.subprojects.forEach { subproject ->
    subproject.tasks.matching { it.name.contains("lint", ignoreCase = true) }.configureEach {
        mustRunAfter(finalRemediationStaticGate)
    }
}

verifyFfmpeg04FinalReleaseValidation.configure {
    doLast {
        println("FF04 staged final release validation completed")
    }
}


tasks.register("checkBrowserIntegration") {
    group = "verification"
    description = "Run Android browser bridge unit checks and validate the keyless development Firefox extension."
    dependsOn(
        "testDebugUnitTest",
        ":browser-extension:validateFirefoxExtension",
    )
}


val cleanKotlinValidationState = providers.gradleProperty("xdm.cleanKotlinValidation")
    .map(String::toBoolean)
    .orElse(false)

val resetKotlinValidationState = tasks.register<Delete>("resetKotlinValidationState") {
    group = "verification"
    description = "Remove app Kotlin compiler outputs and incremental state before constrained validation."
    delete(
        layout.buildDirectory.dir("kotlin"),
        layout.buildDirectory.dir("intermediates/built_in_kotlinc"),
        layout.buildDirectory.dir("tmp/kotlin-classes"),
        layout.buildDirectory.dir("reports/kotlin-build"),
    )
    onlyIf { cleanKotlinValidationState.get() }
}

tasks.matching { it.name.startsWith("compile") && it.name.endsWith("Kotlin") }.configureEach {
    if (cleanKotlinValidationState.get()) {
        dependsOn(resetKotlinValidationState)
    }
}
