import org.gradle.api.GradleException
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.Exec
import java.security.KeyStore
import java.security.MessageDigest

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

fun certificateSha256(store: File, storePassword: String, alias: String): String {
    val failures = mutableListOf<String>()
    for (type in listOf("PKCS12", "JKS", KeyStore.getDefaultType()).distinct()) {
        try {
            val keyStore = KeyStore.getInstance(type)
            store.inputStream().use { keyStore.load(it, storePassword.toCharArray()) }
            val certificate = keyStore.getCertificate(alias)
                ?: throw GradleException("alias '$alias' has no certificate")
            return MessageDigest.getInstance("SHA-256")
                .digest(certificate.encoded)
                .joinToString("") { "%02x".format(it) }
        } catch (error: Exception) {
            failures += "$type: ${error.message ?: error.javaClass.simpleName}"
        }
    }
    throw GradleException("Unable to load release signing certificate from ${store.absolutePath}: ${failures.joinToString("; ")}")
}

val releaseSignerEvidence = layout.buildDirectory.file("release/provenance/release-signer-attestation.json")
val xdmAssertReleaseSigningInputs = tasks.register("xdmAssertReleaseSigningInputs") {
    group = "verification"
    description = "Binds every publishable release package to the configured keystore certificate and pinned SHA-256 fingerprint."
    releaseStoreFile?.let { inputs.file(file(it)) }
    inputs.property("releaseKeyAlias", releaseKeyAlias ?: "<missing>")
    inputs.property("expectedReleaseSignerSha256", pinnedReleaseSignerSha256 ?: "<missing>")
    outputs.file(releaseSignerEvidence)
    doLast {
        require(hasReleaseSigning) { "Release signing inputs are required for publishable release packaging. Use assembleDevelopmentUnsigned for unsigned local handoff builds." }
        val storePath = requireNotNull(releaseStoreFile) { "xdm.release.storeFile or XDM_RELEASE_STORE_FILE is required" }
        val store = file(storePath)
        require(store.isFile) { "Release keystore does not exist: $storePath" }
        val alias = requireNotNull(releaseKeyAlias)
        val password = requireNotNull(releaseStorePassword)
        val expected = requireNotNull(pinnedReleaseSignerSha256) { "xdm.release.signerSha256 or XDM_RELEASE_SIGNER_SHA256 is required for signer continuity" }
            .lowercase()
        require(Regex("^[0-9a-f]{64}$").matches(expected)) { "Pinned release signer SHA-256 must be 64 hex characters" }
        val actual = certificateSha256(store, password, alias)
        require(actual == expected) { "Configured release keystore certificate SHA-256 $actual does not match pinned signer $expected" }
        val output = releaseSignerEvidence.get().asFile
        output.parentFile.mkdirs()
        output.writeText(
            """{
  "schemaVersion": 1,
  "keyAlias": "${alias.replace("\\", "\\\\").replace("\"", "\\\"")}",
  "certificateSha256": "$actual",
  "verified": true
}
"""
        )
    }
}

// Direct AGP packaging/signing tasks are release entry points too. Binding the preflight to
// all of them prevents callers from bypassing signer continuity by invoking packageRelease,
// signReleaseBundle, validateSigningRelease, or another publishable release packaging task.
tasks.matching { task ->
    val name = task.name
    name.contains("Release") && (
        name.startsWith("assemble") ||
        name.startsWith("bundle") ||
        name.startsWith("package") ||
        name.startsWith("sign") ||
        name.startsWith("validateSigning")
    )
}.configureEach {
    if (name != "xdmAssertReleaseSigningInputs") dependsOn(xdmAssertReleaseSigningInputs)
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


val repositoryRoot = rootProject.projectDir.parentFile.parentFile
val staticValidationExtensions = setOf("kt", "kts", "py", "sh", "json", "toml", "xml", "md")
val staticValidationInputs = files(
    File(repositoryRoot, ".devtool.toml"),
    File(repositoryRoot, "CHANGELOG.md"),
    fileTree(repositoryRoot) {
        include { element ->
            val path = element.path.replace('\\', '/')
            val extension = element.file.extension
            val topLevelReport = !path.contains('/') && path.startsWith("XDM_") && extension == "md"
            val androidSource = path.startsWith("app/XDM.Android/") && extension in staticValidationExtensions
            (topLevelReport || androidSource) &&
                !path.contains("/build/") &&
                !path.contains("/.gradle/") &&
                !path.contains("/__pycache__/") &&
                extension != "pyc"
        }
    },
)


fun Exec.trackStaticValidation(stampName: String) {
    inputs.files(staticValidationInputs)
    val stampFile = project.layout.buildDirectory.file("validation/$stampName.stamp")
    outputs.file(stampFile)
    doLast {
        val stamp = stampFile.get().asFile
        stamp.parentFile.mkdirs()
        stamp.writeText("ok\n")
    }
}

val verifyFfmpeg01EmbeddedRuntimeContract = tasks.register<Exec>("verifyFfmpeg01EmbeddedRuntimeContract") {
    group = "verification"
    description = "Verify the FF01 embedded FFmpeg/FFprobe runtime and app-owned media execution contract."
    workingDir(rootProject.projectDir)
    commandLine("python3", "tools/validate-ffmpeg01-embedded-runtime-media-execution.py")
    trackStaticValidation("ffmpeg01")
}

val verifyFfmpeg02MediaMuxHlsPostprocessingContract = tasks.register<Exec>("verifyFfmpeg02MediaMuxHlsPostprocessingContract") {
    group = "verification"
    description = "Verify FF02 embedded adaptive mux, HLS finalization, progress, cancellation, atomic publication, and FFprobe correctness."
    dependsOn(verifyFfmpeg01EmbeddedRuntimeContract)
    workingDir(rootProject.projectDir)
    commandLine("python3", "tools/validate-ffmpeg02-media-mux-hls-postprocessing.py", "--skip-prerequisites")
    trackStaticValidation("ffmpeg02")
}

val verifyFfmpeg03RuntimeRoutingTermuxUiReliabilityContract = tasks.register<Exec>("verifyFfmpeg03RuntimeRoutingTermuxUiReliabilityContract") {
    group = "verification"
    description = "Verify FF03 runtime routing, safe Termux fallback, yt-dlp boundary, UI controls, and redacted diagnostics."
    dependsOn(verifyFfmpeg02MediaMuxHlsPostprocessingContract)
    workingDir(rootProject.projectDir)
    commandLine("python3", "tools/validate-ffmpeg03-runtime-routing-termux-ui-reliability.py", "--skip-prerequisites")
    trackStaticValidation("ffmpeg03")
}

val verifyExecutionMediaSemanticsRepair = tasks.register<Exec>("verifyExecutionMediaSemanticsRepair") {
    group = "verification"
    description = "Verify the retained execution/media semantics repair contract once for the FFmpeg release DAG."
    workingDir(rootProject.projectDir)
    commandLine("python3", "tools/validate-execution-media-semantics-repair.py")
    trackStaticValidation("execution-media-semantics")
}

val verifyFfmpeg04FullReleaseSeal = tasks.register<Exec>("verifyFfmpeg04FullReleaseSeal") {
    group = "verification"
    description = "Verify the complete FF01-FF04 embedded media runtime release seal and no-Termux acceptance contract."
    dependsOn(verifyFfmpeg03RuntimeRoutingTermuxUiReliabilityContract, verifyExecutionMediaSemanticsRepair)
    workingDir(rootProject.projectDir)
    commandLine("python3", "tools/validate-ffmpeg04-full-release-seal.py", "--skip-prerequisites")
    trackStaticValidation("ffmpeg04")
}

val verifyFfmpegRoadmapPostSealHotfix = tasks.register<Exec>("verifyFfmpegRoadmapPostSealHotfix") {
    group = "verification"
    description = "Verify post-seal FFmpeg roadmap execution ownership: native HLS production wiring and embedded-first local post-processing."
    dependsOn(verifyFfmpeg03RuntimeRoutingTermuxUiReliabilityContract, verifyExecutionMediaSemanticsRepair)
    workingDir(rootProject.projectDir)
    commandLine("python3", "tools/validate-ffmpeg-roadmap-postseal-hotfix.py", "--skip-prerequisites")
    trackStaticValidation("ffmpeg-postseal")
}

val verifyXar01BuildProvenance = tasks.register<Exec>("verifyXar01BuildProvenance") {
    group = "verification"
    description = "Verify XAR01 reproducible build, signing, native-runtime provenance, and release-entrypoint contracts."
    workingDir(rootProject.projectDir)
    commandLine("python3", "tools/validate-xar01-build-provenance.py")
    trackStaticValidation("xar01-build-provenance")
}

val verifyXar02ConcurrencyCas = tasks.register<Exec>("verifyXar02ConcurrencyCas") {
    group = "verification"
    description = "Verify XAR02 generation-owned state machines, compare-and-set concurrency, and exact sidecar ownership contracts."
    workingDir(rootProject.projectDir)
    commandLine("python3", "tools/validate-xar02-concurrency-cas.py")
    trackStaticValidation("xar02-concurrency-cas")
}

val verifyXar03PersistenceGenerationIntegrity = tasks.register<Exec>("verifyXar03PersistenceGenerationIntegrity") {
    group = "verification"
    description = "Verify XAR03 Room migrations, attempt-owned durable evidence, and recovery/finalization invariants."
    workingDir(rootProject.projectDir)
    commandLine("python3", "tools/validate-xar03-persistence-generation-integrity.py")
    trackStaticValidation("xar03-persistence-generation-integrity")
}

val verifyXar04NavigationSessionOwnership = tasks.register<Exec>("verifyXar04NavigationSessionOwnership") {
    group = "verification"
    description = "Verify XAR04 navigation truth, Add Download session ownership, and adaptive shell behavior."
    workingDir(rootProject.projectDir)
    commandLine("python3", "tools/validate-xar04-navigation-session-ownership.py")
    trackStaticValidation("xar04-navigation-session-ownership")
}


val verifyXar05ExternalIntakeAdmission = tasks.register<Exec>("verifyXar05ExternalIntakeAdmission") {
    group = "verification"
    description = "Verify XAR05 unified external intake, atomic exact-request admission, and S03 coverage."
    workingDir(rootProject.projectDir)
    commandLine("python3", "tools/validate-xar05-external-intake-admission.py")
    trackStaticValidation("xar05-external-intake-admission")
}

val verifyXar06StoragePublication = tasks.register<Exec>("verifyXar06StoragePublication") {
    group = "verification"
    description = "Verify XAR06 transactional storage publication, resume, provider recovery, and S06 coverage."
    workingDir(rootProject.projectDir)
    commandLine("python3", "tools/validate-xar06-storage-publication.py")
    trackStaticValidation("xar06-storage-publication")
}

val verifyGradleTaskGraphOptimization = tasks.register<Exec>("verifyGradleTaskGraphOptimization") {
    group = "verification"
    description = "Verify XDM Android Gradle task-graph deduplication, incremental runtime setup, and validation coverage preservation."
    workingDir(repositoryRoot)
    commandLine("python3", "app/XDM.Android/tools/validate-gradle-task-graph-optimization.py")
    trackStaticValidation("gradle-task-graph-optimization")
}

tasks.register<Exec>("verifyFfmpegDebugApkRuntime") {
    group = "verification"
    description = "Build and verify the debug APK contains the exact attested 16 KB FFmpeg/FFprobe payload and licenses."
    dependsOn(":media-ffmpeg:installPinnedFfmpegRuntime", "assembleDebug")
    workingDir(rootProject.projectDir)
    inputs.files(
        layout.buildDirectory.file("outputs/apk/debug/app-debug.apk"),
        rootProject.layout.projectDirectory.file("tools/verify-ffmpeg-runtime.py"),
        rootProject.layout.projectDirectory.file("media-ffmpeg/runtime/ffmpeg-runtime.json"),
        rootProject.layout.projectDirectory.file("media-ffmpeg/runtime/ffmpeg-runtime.lock.json"),
        rootProject.layout.projectDirectory.file("media-ffmpeg/runtime/licenses/FFmpeg-LGPL-2.1.txt"),
        rootProject.layout.projectDirectory.file("media-ffmpeg/runtime/licenses/OpenSSL-Apache-2.0.txt"),
        rootProject.layout.projectDirectory.file("media-ffmpeg/src/main/jniLibs/arm64-v8a/libxdm_ffmpeg.so"),
        rootProject.layout.projectDirectory.file("media-ffmpeg/src/main/jniLibs/arm64-v8a/libxdm_ffprobe.so"),
    )
    val successMarker = layout.buildDirectory.file("validation/verifyFfmpegDebugApkRuntime.success")
    outputs.file(successMarker)
    commandLine(
        "python3", "tools/verify-ffmpeg-runtime.py",
        "--require-payload", "--require-16kb-alignment",
        "--apk", "app/build/outputs/apk/debug/app-debug.apk",
    )
    doLast {
        val marker = successMarker.get().asFile
        marker.parentFile.mkdirs()
        marker.writeText("ok\n")
    }
}

// Ordinary debug builds keep the embedded FFmpeg runtime optional. When the explicit strict
// APK-runtime verifier is in the graph, this ordering ensures installation finishes before
// assembleDebug without forcing a native source build for normal developer compilation.
tasks.matching { it.name == "assembleDebug" }.configureEach {
    mustRunAfter(":media-ffmpeg:installPinnedFfmpegRuntime")
}


val finalRemediationStaticGate = tasks.register<Exec>("finalRemediationStaticGate") {
    group = "verification"
    description = "Run the canonical XDM final static release gate, including the UX13 end-to-end UI/UX seal."
    dependsOn(verifyXar01BuildProvenance, verifyXar02ConcurrencyCas, verifyXar03PersistenceGenerationIntegrity, verifyFfmpeg04FullReleaseSeal, verifyFfmpegRoadmapPostSealHotfix, verifyGradleTaskGraphOptimization)
    workingDir(rootProject.projectDir)
    // Preserve the historical command line for retained source-contract tests. The environment
    // tells the shell gate that Gradle already executed the FFmpeg/execution DAG exactly once.
    environment("XDM_GRADLE_ORCHESTRATED", "1")
    commandLine("bash", "tools/run-final-release-gate.sh", "--ci")
    trackStaticValidation("final-remediation-static")
}

// FF04 final validation remains one lifecycle task, but Gradle now owns the prerequisite DAG.
// Independent tests/package/static work may overlap; only lint remains ordered behind the
// static/runtime stages to avoid the known generated-JNI race on ARM64 Termux.
val verifyFfmpeg04FinalReleaseValidation = tasks.register("verifyFfmpeg04FinalReleaseValidation") {
    group = "verification"
    description = "Run the complete FF04 release validation with a deduplicated Gradle-owned static validator DAG."
    dependsOn(
        "verifyFfmpeg04FullReleaseSeal",
        "verifyFfmpegRoadmapPostSealHotfix",
        "verifyGradleTaskGraphOptimization",
        ":media-ffmpeg:testDebugUnitTest",
        ":media:test",
        "testDebugUnitTest",
        "assembleDebugAndroidTest",
        "verifyFfmpegDebugApkRuntime",
        finalRemediationStaticGate,
        "lintDebug",
    )
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
