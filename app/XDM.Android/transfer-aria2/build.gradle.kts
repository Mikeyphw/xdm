plugins {
    alias(libs.plugins.android.library)
}

val requireAlignedAria2Runtime = providers.gradleProperty("xdm.requireAria2Runtime")
    .map(String::toBoolean)
    .orElse(false)

val installOfficialAria2Runtime = tasks.register<Exec>("installOfficialAria2Runtime") {
    group = "build setup"
    description = "Installs the pinned official ARM64 aria2 runtime payload incrementally."
    workingDir(rootProject.projectDir)
    commandLine("python3", "tools/install-aria2-runtime.py", "--download-official")
    inputs.files(
        layout.projectDirectory.file("runtime/aria2-runtime.json"),
        rootProject.layout.projectDirectory.file("tools/install-aria2-runtime.py"),
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
            // Optional dev/debug builds may carry the currently pinned upstream aria2 payload,
            // which is not guaranteed to be 16 KB ELF-page aligned. Strict distribution
            // builds keep the check enabled via -Pxdm.requireAria2Runtime=true.
            disable += "Aligned16KB"
        }
    }
}

// Packaging owns runtime installation. Declaring this dependency on JNI merge tasks makes
// standalone assemble/package invocations correct while Gradle can mark the installer UP-TO-DATE
// across Devtool's split phases.
tasks.matching { task ->
    task.name.startsWith("merge") && task.name.contains("JniLib", ignoreCase = true)
}.configureEach {
    dependsOn(installOfficialAria2Runtime)
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
    dependsOn(installOfficialAria2Runtime)
    description = "Verifies the attested ARM64 aria2 runtime when present or required."
    workingDir(rootProject.projectDir)
    inputs.files(
        layout.projectDirectory.file("runtime/aria2-runtime.json"),
        layout.projectDirectory.file("runtime/aria2-runtime.lock.json"),
        layout.projectDirectory.file("src/main/jniLibs/arm64-v8a/libaria2c.so"),
        rootProject.layout.projectDirectory.file("tools/verify-aria2-runtime.py"),
    )
    inputs.property("requireAlignedAria2Runtime", requireAlignedAria2Runtime)
    val successMarker = layout.buildDirectory.file("validation/verifyAria2Runtime.success")
    outputs.file(successMarker)
    commandLine(
        "python3",
        "tools/verify-aria2-runtime.py",
        *if (requireAlignedAria2Runtime.get()) {
            arrayOf("--require-payload", "--require-16kb-alignment")
        } else {
            emptyArray()
        },
    )
    doLast {
        val marker = successMarker.get().asFile
        marker.parentFile.mkdirs()
        marker.writeText("ok\n")
    }
}

tasks.matching { it.name in setOf("preDebugBuild", "preReleaseBuild") }.configureEach {
    dependsOn(verifyAria2Runtime)
}

