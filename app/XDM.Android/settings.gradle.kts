pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "XDMAndroid"

include(
    ":app",
    ":core-model",
    ":core-utils",
    ":persistence",
    ":storage",
    ":transfer-api",
    ":transfer-native",
    ":transfer-aria2",
    ":scheduler",
    ":media",
    ":diagnostics",
    ":browser-integration",
    ":tasker-plugin",
    ":protocol-test-lab",
)
