plugins { alias(libs.plugins.kotlin.jvm) }

kotlin { jvmToolchain(21) }

tasks.test { useJUnit() }


dependencies { testImplementation(libs.junit) }
