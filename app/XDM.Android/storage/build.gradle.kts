plugins { alias(libs.plugins.android.library) }
android {
    namespace = "com.mikeyphw.xdm.android.storage"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21 }
    lint { abortOnError = true; warningsAsErrors = true; disable += "GradleDependency" }
}


dependencies { implementation(project(":core-model"))
    implementation(project(":core-utils")); implementation(project(":transfer-api")); implementation(libs.kotlinx.coroutines.android); testImplementation(libs.junit) }
