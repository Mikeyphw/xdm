package com.mikeyphw.xdm.android.transfer.aria2

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Aria2ProcessLauncherTest {
    @Test
    fun privateRuntimeEnvironmentNeverInheritsShellTestPaths() {
        val root = Files.createTempDirectory("aria2-private-process-environment").toFile()
        val plan = Aria2LaunchPlan(
            binary = root.resolve("libaria2c.so"),
            workingDirectory = root,
            configurationFile = root.resolve("launch.conf"),
            logFile = root.resolve("aria2.log"),
        )

        val environment = plan.privateRuntimeEnvironment()
        val canonicalRoot = root.canonicalPath

        assertEquals(canonicalRoot, environment.getValue("HOME"))
        assertEquals(canonicalRoot, environment.getValue("PWD"))
        assertTrue(environment.getValue("TMPDIR").startsWith(canonicalRoot + "/"))
        assertTrue(environment.getValue("XDG_CONFIG_HOME").startsWith(canonicalRoot + "/"))
        assertTrue(environment.getValue("XDG_CACHE_HOME").startsWith(canonicalRoot + "/"))
        assertTrue(environment.getValue("XDG_DATA_HOME").startsWith(canonicalRoot + "/"))
        assertTrue(environment.getValue("XDG_STATE_HOME").startsWith(canonicalRoot + "/"))
        assertTrue(environment.getValue("XDG_RUNTIME_DIR").startsWith(canonicalRoot + "/"))
        assertFalse(environment.values.any { it.startsWith("/data/local/tests") })
        environment.values.filter { it != canonicalRoot }.forEach { path ->
            assertTrue("private environment directory missing: $path", java.io.File(path).isDirectory)
        }
    }
}
