plugins { alias(libs.plugins.kotlin.jvm) }

kotlin { jvmToolchain(17) }

tasks.test { useJUnit() }


dependencies { api(project(":core-model")); implementation(libs.kotlinx.coroutines.core); testImplementation(libs.junit) }
