plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
}

val verifyStableToolchainBaseline = tasks.register("verifyStableToolchainBaseline") {
    group = "verification"
    description = "Verify XDM Android's pinned stable Gradle/AGP/Kotlin/KSP/Java/SDK baseline."

    // Resolve Gradle model values during configuration. The task action below
    // captures only plain Strings, Files and Lists, which are safe for the
    // configuration cache.
    val expectedGradleVersion = "9.7.1"
    val runningGradleVersion = gradle.gradleVersion

    val catalogFile =
        layout.projectDirectory.file("gradle/libs.versions.toml").asFile
    val wrapperFile =
        layout.projectDirectory.file("gradle/wrapper/gradle-wrapper.properties").asFile
    val ciWorkflowFile =
        layout.projectDirectory.file(".github/workflows/android.yml").asFile
    val appBuildFile =
        layout.projectDirectory.file("app/build.gradle.kts").asFile
    val rootDirectory =
        layout.projectDirectory.asFile

    val subprojectBuildFiles = subprojects.map {
        it.layout.projectDirectory.file("build.gradle.kts").asFile
    }

    val jvmToolchainFiles = listOf(
        "core-model/build.gradle.kts",
        "core-utils/build.gradle.kts",
        "transfer-api/build.gradle.kts",
        "browser-extension/build.gradle.kts",
    ).map {
        layout.projectDirectory.file(it).asFile
    }

    inputs.files(
        catalogFile,
        wrapperFile,
        ciWorkflowFile,
        appBuildFile,
    )
    inputs.files(subprojectBuildFiles)
    inputs.files(jvmToolchainFiles)

    doLast {
        require(runningGradleVersion == expectedGradleVersion) {
            "XDM Android requires Gradle $expectedGradleVersion; running $runningGradleVersion."
        }

        require(JavaVersion.current() == JavaVersion.VERSION_21) {
            "XDM Android requires Java 21; running ${System.getProperty("java.version")}."
        }

        val catalog = catalogFile.readText()
        val wrapper = wrapperFile.readText()

        val requiredCatalogPins = listOf(
            "agp = \"9.3.1\"" to "AGP 9.3.1",
            "kotlin = \"2.4.10\"" to "Kotlin 2.4.10",
            "ksp = \"2.3.11\"" to "KSP 2.3.11",
            "composeBom = \"2026.06.01\"" to "Compose BOM 2026.06.01",
        )

        requiredCatalogPins.forEach { (needle, label) ->
            require(catalog.contains(needle)) {
                "Stable toolchain baseline drift: missing $label."
            }
        }

        require(wrapper.contains("gradle-9.7.1-bin.zip")) {
            "Gradle wrapper must pin Gradle 9.7.1."
        }

        require(
            wrapper.contains(
                "distributionSha256Sum=" +
                    "acd53f1edaf02f1a8ff99879f8a34b302661a057d9b063ae9e35b552f804d20a"
            )
        ) {
            "Gradle 9.7.1 wrapper checksum is missing or incorrect."
        }

        val ciWorkflow = ciWorkflowFile.readText()

        require(ciWorkflow.contains("java-version: '21'")) {
            "Android CI must use Java 21."
        }

        require(ciWorkflow.contains("gradle-version: '9.7.1'")) {
            "Android CI must use Gradle 9.7.1."
        }

        require(
            ciWorkflow.contains("gradle wrapper --gradle-version 9.7.1")
        ) {
            "Android CI wrapper bootstrap must use Gradle 9.7.1."
        }

        val prereleaseMarkers = listOf(
            "alpha",
            "beta",
            "rc",
            "eap",
            "preview",
            "canary",
            "snapshot",
            "nightly",
            "milestone",
        )

        val versionAssignments =
            Regex("""(?m)^\s*[A-Za-z][A-Za-z0-9]*\s*=\s*"([^"]+)"""")
                .findAll(catalog)
                .map { it.groupValues[1] }
                .toList()

        val prerelease = versionAssignments.firstOrNull { version ->
            val normalized = version.lowercase()

            prereleaseMarkers.any { marker ->
                Regex(
                    """(^|[.\-_])${Regex.escape(marker)}([.\-_0-9]|$)"""
                ).containsMatchIn(normalized)
            }
        }

        require(prerelease == null) {
            "Prerelease dependency/tool version is forbidden: $prerelease"
        }

        val androidBuildFiles = subprojectBuildFiles
            .filter {
                it.isFile &&
                    it.readText().contains("libs.plugins.android.")
            }

        require(androidBuildFiles.isNotEmpty()) {
            "No Android module build files found for baseline verification."
        }

        androidBuildFiles.forEach { buildFile ->
            val moduleBuild = buildFile.readText()
            val relativePath = buildFile.relativeTo(rootDirectory).path

            require(moduleBuild.contains("compileSdk = 36")) {
                "$relativePath: compileSdk must remain 36."
            }

            require(
                moduleBuild.contains(
                    "buildToolsVersion = \"36.0.0\""
                )
            ) {
                "$relativePath: Build Tools must be 36.0.0."
            }

            require(
                moduleBuild.contains("JavaVersion.VERSION_21")
            ) {
                "$relativePath: Java source/target compatibility must be 21."
            }

            require(
                !moduleBuild.contains("JavaVersion.VERSION_17")
            ) {
                "$relativePath: Java 17 target remains after Java 21 normalization."
            }
        }

        val appBuild = appBuildFile.readText()

        require(appBuild.contains("targetSdk = 36")) {
            "app/build.gradle.kts: targetSdk must remain 36."
        }

        jvmToolchainFiles.forEach { buildFile ->
            val moduleBuild = buildFile.readText()
            val relativePath = buildFile.relativeTo(rootDirectory).path

            require(moduleBuild.contains("jvmToolchain(21)")) {
                "$relativePath must use JVM toolchain 21."
            }
        }
    }
}

subprojects {
    tasks.matching { it.name == "preBuild" }.configureEach {
        dependsOn(rootProject.tasks.named("verifyStableToolchainBaseline"))
    }
}
