plugins {
    alias(libs.plugins.android.library)
}

val requireAlignedAria2Runtime = providers.gradleProperty("xdm.requireAria2Runtime")
    .map(String::toBoolean)
    .orElse(false)

android {
    namespace = "com.mikeyphw.xdm.android.transfer.aria2"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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


val verifyAria2Runtime by tasks.registering(Exec::class) {
    group = "verification"
    description = "Verifies the attested ARM64 aria2 runtime when present or required."
    workingDir(rootProject.projectDir)
    commandLine(
        "python3",
        "tools/verify-aria2-runtime.py",
        *if (requireAlignedAria2Runtime.get()) {
            arrayOf("--require-payload", "--require-16kb-alignment")
        } else {
            emptyArray()
        },
    )
}

tasks.matching { it.name in setOf("preDebugBuild", "preBetaBuild", "preReleaseBuild") }.configureEach {
    dependsOn(verifyAria2Runtime)
}
