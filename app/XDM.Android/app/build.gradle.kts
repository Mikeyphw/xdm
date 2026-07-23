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
val hasReleaseSigning = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { it != null }

val requireAlignedAria2Runtime = providers.gradleProperty("xdm.requireAria2Runtime")
    .map(String::toBoolean)
    .orElse(false)

android {
    namespace = "com.mikeyphw.xdm.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mikeyphw.xdm.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 21
        versionName = "0.20.0-rc08"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        getByName("debug") { applicationIdSuffix = ".debug"; versionNameSuffix = "-debug" }
        create("beta") {
            initWith(getByName("release"))
            applicationIdSuffix = ".beta"
            versionNameSuffix = "-beta"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
        getByName("release") {
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    packaging {
        jniLibs.useLegacyPackaging = true
        // Termux/chroot ARM builds can accidentally resolve the Linux x86_64 NDK host strip tool.
        // Keep native debug symbols for every packaged JNI lib so Gradle never shells out to that host-only llvm-strip.
        jniLibs.keepDebugSymbols += "**/*.so"
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
