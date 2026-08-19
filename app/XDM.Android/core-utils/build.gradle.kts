plugins { alias(libs.plugins.kotlin.jvm) }

kotlin { jvmToolchain(21) }

tasks.test { useJUnit() }


dependencies { implementation(project(":core-model")); implementation(libs.kotlinx.coroutines.core); testImplementation(libs.junit) }
