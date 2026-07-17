plugins {
    alias(libs.plugins.android.library)
}

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
    }
}

dependencies {
    implementation(project(":core-model"))
    implementation(project(":transfer-api"))
    implementation(project(":storage"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}


val requireAria2Runtime = providers.gradleProperty("xdm.requireAria2Runtime")
    .map(String::toBoolean)
    .orElse(false)

val verifyAria2Runtime by tasks.registering(Exec::class) {
    group = "verification"
    description = "Verifies the attested ARM64 aria2 runtime when present or required."
    workingDir(rootProject.projectDir)
    commandLine(
        "python3",
        "tools/verify-aria2-runtime.py",
        *if (requireAria2Runtime.get()) arrayOf("--require-payload") else emptyArray(),
    )
}

tasks.matching { it.name in setOf("preDebugBuild", "preBetaBuild", "preReleaseBuild") }.configureEach {
    dependsOn(verifyAria2Runtime)
}
