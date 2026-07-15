plugins { alias(libs.plugins.android.library) }
android {
    namespace = "com.mikeyphw.xdm.android.protocollab"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    lint { abortOnError = true; warningsAsErrors = true }
}


dependencies { implementation(project(":core-model")); implementation(project(":transfer-api")); implementation(libs.kotlinx.coroutines.android); testImplementation(libs.junit) }
