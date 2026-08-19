plugins { alias(libs.plugins.android.library) }
android {
    namespace = "com.mikeyphw.xdm.android.transfer.nativeengine"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21 }
    lint { abortOnError = true; warningsAsErrors = true; disable += "GradleDependency" }
}

dependencies {
    implementation(project(":core-model"))
    implementation(project(":transfer-api"))
    implementation(project(":storage"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
