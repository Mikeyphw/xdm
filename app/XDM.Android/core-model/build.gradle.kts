plugins { alias(libs.plugins.kotlin.jvm) }

kotlin { jvmToolchain(17) }

tasks.test { useJUnit() }


dependencies { testImplementation(libs.junit) }
