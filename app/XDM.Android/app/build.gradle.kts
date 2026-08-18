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

fun validationEvidence(propertyName: String): Boolean = providers.gradleProperty(propertyName)
    .map(String::toBoolean)
    .orElse(false)
    .get()

val staticValidationPassed = validationEvidence("xdm.validation.staticPassed")
val fullValidationPassed = validationEvidence("xdm.validation.fullPassed")
val realDeviceSmokePassed = validationEvidence("xdm.validation.realDeviceSmokePassed")
val aria2PayloadVerified = validationEvidence("xdm.validation.aria2PayloadVerified")

android {
    namespace = "com.mikeyphw.xdm.android"
    compileSdk = 36

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
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    packaging {
        jniLibs.useLegacyPackaging = true
        // Keep only the attested aria2 runtime symbols; release inventory rejects broad debug-symbol retention.
        jniLibs.keepDebugSymbols += "**/libaria2c.so"
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
        if (!requireAlignedAria2Runtime.get()) {
            // The currently pinned upstream aria2 payload is optional for developer builds
            // and is not guaranteed to be 16 KB ELF-page aligned. Strict aria2 builds
            // keep the check enabled via -Pxdm.requireAria2Runtime=true.
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


tasks.register<Exec>("finalRemediationStaticGate") {
    group = "verification"
    description = "Run the Overlay 13 final static remediation gate."
    workingDir(rootProject.projectDir)
    commandLine("bash", "tools/run-final-release-gate.sh", "--ci")
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
