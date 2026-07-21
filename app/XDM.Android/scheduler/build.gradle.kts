plugins { alias(libs.plugins.android.library) }
android {
    namespace = "com.mikeyphw.xdm.android.scheduler"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    lint { abortOnError = true; warningsAsErrors = true; disable += "GradleDependency" }
}

dependencies {
    implementation(project(":core-model"))
    implementation(project(":core-utils"))
    implementation(project(":persistence"))
    implementation(project(":transfer-api"))
    implementation(project(":transfer-native"))
    implementation(project(":transfer-aria2"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
