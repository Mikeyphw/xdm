package com.mikeyphw.xdm.android

import android.content.Context
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mikeyphw.xdm.android.transfer.Aria2TaskMapping
import com.mikeyphw.xdm.android.transfer.aria2.AndroidAria2CapabilityProbe
import com.mikeyphw.xdm.android.transfer.aria2.AppPrivateAria2SecretProvider
import com.mikeyphw.xdm.android.transfer.aria2.Aria2ProcessManager
import com.mikeyphw.xdm.android.transfer.aria2.Aria2ProcessState
import com.mikeyphw.xdm.android.transfer.aria2.Aria2SessionStore
import com.mikeyphw.xdm.android.transfer.aria2.Aria2TaskOptions
import com.mikeyphw.xdm.android.transfer.aria2.Aria2TaskStatus
import com.mikeyphw.xdm.android.transfer.aria2.Aria2TaskStatusValue
import com.mikeyphw.xdm.android.transfer.aria2.Aria2TrustBundleProvider
import java.io.File
import java.net.InetAddress
import java.security.KeyStore
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Aria2Rm08PackagedRuntimeInstrumentedTest {
    @Test
    fun packagedRuntimeEnforcesTlsAndRecoversSavedOwnedSession() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val fixture = Fixture(
            context = instrumentation.targetContext,
            testContext = instrumentation.context,
        )

        try {
            fixture.reset()
            verifyPackagedRuntimeAndTls(fixture)

            fixture.reset()
            verifyOwnedSessionRecovery(fixture)

            instrumentation.sendStatus(
                2,
                Bundle().apply {
                    putString("xgo_rm08_aria2", "pass")
                    putString("xgo_rm08_claims", "packaged-bytes,rpc-auth,tls-reject,tls-success,session-restart-recovery")
                },
            )
        } finally {
            fixture.stopQuietly()
            fixture.reset()
        }
    }

    private suspend fun verifyPackagedRuntimeAndTls(fixture: Fixture) {
        val store = fixture.newStore()
        val manager = fixture.newManager(store)
        fixture.manager = manager

        try {
            val capability = manager.probe()
            assertTrue(capability.summary, capability.isAvailable)
            assertTrue("installed aria2 must match the packaged runtime lock", capability.attestationVerified)
            assertNotNull(capability.binary)
            val descriptor = requireNotNull(capability.binary)
            assertEquals(fixture.context.applicationInfo.nativeLibraryDir, descriptor.file.parentFile?.absolutePath)
            assertEquals("android-unversioned-sonames-v1", descriptor.dependencyPolicy)
            assertFalse(
                "packaged aria2 must not depend on versioned Linux SONAMEs",
                descriptor.neededLibraries.any { it.matches(Regex("^lib[^/]+\\.so\\.\\d+.*$")) },
            )

            val started = manager.start()
            assertTrue("packaged aria2 failed to start: ${started.state}", started.started)
            assertTrue(started.state is Aria2ProcessState.Running)
            val rpc = manager.rpc()
            assertTrue("authenticated RPC must return an aria2 version", rpc.getVersion().version.isNotBlank())

            val untrustedPayload = "XDM RM08 untrusted TLS must fail\n".toByteArray()
            TlsProbeServer(fixture.testContext, "rm08/untrusted-server.p12", untrustedPayload).use { server ->
                val output = File(fixture.context.cacheDir, "rm08-untrusted-${System.nanoTime()}.bin")
                val gid = rpc.addUri(
                    listOf(server.url("untrusted")),
                    Aria2TaskOptions(
                        directory = output.parentFile!!.canonicalPath,
                        outputName = output.name,
                        pause = false,
                        continueDownload = false,
                        split = 1,
                        maxConnectionsPerServer = 1,
                    ),
                )
                try {
                    val terminal = awaitStatus(
                        status = rpc::tellStatus,
                        gid = gid,
                        accepted = TERMINAL_STATES,
                        timeoutMillis = 20_000L,
                        label = "untrusted TLS transfer",
                    )
                    assertEquals(
                        "untrusted TLS must be rejected by aria2/OpenSSL (error=${terminal.errorMessage.orEmpty()})",
                        Aria2TaskStatusValue.Error,
                        terminal.status,
                    )
                    assertFalse("untrusted TLS must not publish payload bytes", output.exists() && output.length() > 0L)
                } finally {
                    runCatching { rpc.remove(gid, force = true) }
                    runCatching { rpc.removeDownloadResult(gid) }
                    output.delete()
                    File(output.parentFile, output.name + ".aria2").delete()
                }
            }

            val trustedPayload = "XDM RM08 trusted HTTPS payload\n".toByteArray()
            TlsProbeServer(fixture.testContext, "rm08/trusted-server.p12", trustedPayload).use { server ->
                val output = File(fixture.context.cacheDir, "rm08-trusted-${System.nanoTime()}.bin")
                val gid = rpc.addUri(
                    listOf(server.url("trusted")),
                    Aria2TaskOptions(
                        directory = output.parentFile!!.canonicalPath,
                        outputName = output.name,
                        pause = false,
                        continueDownload = false,
                        split = 1,
                        maxConnectionsPerServer = 1,
                    ),
                )
                try {
                    val terminal = awaitStatus(
                        status = rpc::tellStatus,
                        gid = gid,
                        accepted = TERMINAL_STATES,
                        timeoutMillis = 20_000L,
                        label = "trusted TLS transfer",
                    )
                    assertEquals(
                        "trusted test CA must permit HTTPS transfer (error=${terminal.errorMessage.orEmpty()})",
                        Aria2TaskStatusValue.Complete,
                        terminal.status,
                    )
                    assertTrue("trusted HTTPS transfer did not create output", output.isFile)
                    assertArrayEquals(trustedPayload, output.readBytes())
                } finally {
                    runCatching { rpc.removeDownloadResult(gid) }
                    output.delete()
                    File(output.parentFile, output.name + ".aria2").delete()
                }
            }

            assertTrue("aria2.saveSession must succeed after TLS qualification", rpc.saveSession())
            val stopped = manager.stop()
            fixture.manager = null
            assertTrue("aria2 shutdown after TLS qualification must save its session", stopped.sessionSaved)
            assertTrue("aria2 shutdown after TLS qualification must be clean", stopped.clean)
        } finally {
            fixture.stopQuietly()
        }
    }

    private suspend fun verifyOwnedSessionRecovery(fixture: Fixture) {
        val store = fixture.newStore()
        var manager = fixture.newManager(store)
        fixture.manager = manager
        var taskFiles: com.mikeyphw.xdm.android.transfer.aria2.Aria2TaskFiles? = null
        var recoveryOutput: File? = null

        val payload = "XDM RM08 owned-session recovery payload\n".toByteArray()
        TlsProbeServer(fixture.testContext, "rm08/trusted-server.p12", payload).use { server ->
            try {
                val started = manager.start()
                assertTrue("aria2 recovery fixture failed to start: ${started.state}", started.started)
                val rpc = manager.rpc()

                val output = File(fixture.context.cacheDir, "rm08-recovery-${System.nanoTime()}.bin")
                recoveryOutput = output
                val sourceUrl = server.url("recovery")
                val gid = rpc.addUri(
                    listOf(sourceUrl),
                    Aria2TaskOptions(
                        directory = output.parentFile!!.canonicalPath,
                        outputName = output.name,
                        pause = true,
                        continueDownload = false,
                        split = 1,
                        maxConnectionsPerServer = 1,
                    ),
                )
                val paused = awaitStatus(
                    status = rpc::tellStatus,
                    gid = gid,
                    accepted = PAUSED_STATES,
                    timeoutMillis = 10_000L,
                    label = "owned recovery fixture",
                )
                assertTrue(
                    "recovery fixture must be paused before checkpoint (status=${paused.status})",
                    paused.status in PAUSED_STATES,
                )

                val files = store.taskFiles("rm08-recovery", output)
                taskFiles = files
                val now = System.currentTimeMillis()
                store.writeOwnershipMetadata(
                    files,
                    Aria2TaskMapping(
                        downloadId = "rm08-recovery",
                        gid = gid,
                        sourceUrl = sourceUrl,
                        mirrorUrls = emptyList(),
                        destinationUri = output.toURI().toString(),
                        destinationKey = "rm08-recovery",
                        fileName = output.name,
                        conflictPolicy = "Overwrite",
                        mimeType = "application/octet-stream",
                        outputPath = output.canonicalPath,
                        controlPath = files.control.canonicalPath,
                        ownershipMetadataPath = files.ownershipMetadata.canonicalPath,
                        sessionFilePath = files.session.canonicalPath,
                        expectedLength = payload.size.toLong(),
                        ownershipGeneration = 1L,
                        backendInstanceId = "rm08-device",
                        backendSessionId = "rm08-device-session",
                        status = "Paused",
                        createdAtEpochMs = now,
                        updatedAtEpochMs = now,
                        lastSynchronizedAtEpochMs = now,
                    ),
                )
                assertTrue("session checkpoint before restart failed", rpc.saveSession())
                awaitSessionRecord(
                    sessionFile = store.sessionFile,
                    gid = gid,
                    sourceUrl = sourceUrl,
                    timeoutMillis = 10_000L,
                )

                val stopped = manager.stop()
                fixture.manager = null
                assertTrue("aria2 shutdown must save its owned session", stopped.sessionSaved)
                assertTrue("aria2 shutdown must be clean for Gate-12", stopped.clean)
                assertSessionRecord(store.sessionFile, gid, sourceUrl)

                manager = fixture.newManager(store)
                fixture.manager = manager
                val restarted = manager.start()
                assertTrue("aria2 restart from saved session failed: ${restarted.state}", restarted.started)
                val recoveredRpc = manager.rpc()
                val recovered = awaitStatus(
                    status = recoveredRpc::tellStatus,
                    gid = gid,
                    accepted = PAUSED_STATES,
                    timeoutMillis = 10_000L,
                    label = "recovered owned session",
                )
                assertTrue(
                    "saved owned task was not recovered as paused/waiting (status=${recovered.status})",
                    recovered.status in PAUSED_STATES,
                )

                runCatching { recoveredRpc.remove(gid, force = true) }
                runCatching { recoveredRpc.removeDownloadResult(gid) }
                assertTrue("session cleanup checkpoint failed", recoveredRpc.saveSession())
                awaitSessionRecordAbsent(store.sessionFile, gid, timeoutMillis = 10_000L)
                store.deleteTaskMetadata(files)
                taskFiles = null
                val finalStop = manager.stop()
                fixture.manager = null
                assertTrue("aria2 final recovery shutdown must save its session", finalStop.sessionSaved)
                assertTrue("aria2 final recovery shutdown must be clean", finalStop.clean)
            } finally {
                taskFiles?.let(store::deleteTaskMetadata)
                recoveryOutput?.let { output ->
                    output.delete()
                    File(output.parentFile, output.name + ".aria2").delete()
                }
                fixture.stopQuietly()
            }
        }
    }

    private suspend fun awaitStatus(
        status: suspend (String) -> Aria2TaskStatus,
        gid: String,
        accepted: Set<Aria2TaskStatusValue>,
        timeoutMillis: Long,
        label: String,
    ): Aria2TaskStatus {
        var last: Aria2TaskStatus? = null
        val result = withTimeoutOrNull(timeoutMillis) {
            while (true) {
                runCatching { status(gid) }.getOrNull()?.let { current ->
                    last = current
                    if (current.status in accepted) return@withTimeoutOrNull current
                }
                delay(50L)
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }
        return result ?: error("$label did not reach ${accepted.joinToString()} within ${timeoutMillis}ms (last=${last?.status}, error=${last?.errorMessage.orEmpty()})")
    }

    private suspend fun awaitSessionRecord(
        sessionFile: File,
        gid: String,
        sourceUrl: String,
        timeoutMillis: Long,
    ) {
        val ready = withTimeoutOrNull(timeoutMillis) {
            while (true) {
                val text = runCatching { sessionFile.readText(Charsets.UTF_8) }.getOrDefault("")
                if (text.contains("gid=$gid") && text.contains(sourceUrl)) return@withTimeoutOrNull true
                delay(50L)
            }
            @Suppress("UNREACHABLE_CODE")
            false
        } ?: false
        assertTrue("saved session did not durably contain owned gid=$gid and its source URI", ready)
    }

    private fun assertSessionRecord(sessionFile: File, gid: String, sourceUrl: String) {
        assertTrue("saved aria2 session file is missing", sessionFile.isFile)
        val text = sessionFile.readText(Charsets.UTF_8)
        assertTrue("saved session must retain the XDM-owned GID", text.contains("gid=$gid"))
        assertTrue("saved session must retain the XDM-owned source URI", text.contains(sourceUrl))
    }

    private suspend fun awaitSessionRecordAbsent(sessionFile: File, gid: String, timeoutMillis: Long) {
        val removed = withTimeoutOrNull(timeoutMillis) {
            while (true) {
                val text = runCatching { sessionFile.readText(Charsets.UTF_8) }.getOrDefault("")
                if (!text.contains("gid=$gid")) return@withTimeoutOrNull true
                delay(50L)
            }
            @Suppress("UNREACHABLE_CODE")
            false
        } ?: false
        assertTrue("removed recovery task remained in aria2 saved session", removed)
    }

    private class Fixture(
        val context: Context,
        val testContext: Context,
    ) {
        private val trustedCa: ByteArray = testContext.assets.open("rm08/trusted-ca.pem").use { it.readBytes() }
        var manager: Aria2ProcessManager? = null

        fun newStore(): Aria2SessionStore {
            val trustFile = File(context.noBackupFilesDir, "aria2/rm08/trusted-ca.pem")
            val trustProvider = Aria2TrustBundleProvider {
                trustFile.parentFile?.mkdirs()
                trustFile.writeBytes(trustedCa)
                check(trustFile.isFile && trustFile.length() > 0L) { "RM08 trust bundle must be app-private and non-empty" }
                trustFile
            }
            return Aria2SessionStore(context, trustProvider)
        }

        fun newManager(store: Aria2SessionStore): Aria2ProcessManager = Aria2ProcessManager(
            capabilityProbe = AndroidAria2CapabilityProbe(context, store),
            sessionStore = store,
            secretProvider = AppPrivateAria2SecretProvider(context),
            startupTimeoutMillis = 10_000L,
            shutdownTimeoutMillis = 5_000L,
            pollIntervalMillis = 50L,
        )

        suspend fun stopQuietly() {
            manager?.let { current -> runCatching { current.stop() } }
            manager = null
        }

        fun reset() {
            File(context.filesDir, "aria2").deleteRecursively()
            File(context.noBackupFilesDir, "aria2").deleteRecursively()
            context.getSharedPreferences("aria2-runtime", Context.MODE_PRIVATE).edit().clear().commit()
            context.cacheDir.listFiles().orEmpty()
                .filter { it.name.startsWith("rm08-") }
                .forEach { it.deleteRecursively() }
        }
    }

    private class TlsProbeServer(
        context: Context,
        asset: String,
        private val payload: ByteArray,
    ) : AutoCloseable {
        private val running = AtomicBoolean(true)
        private val server: SSLServerSocket
        private val thread: Thread

        init {
            val password = "rm08-test-only".toCharArray()
            val keyStore = KeyStore.getInstance("PKCS12")
            context.assets.open(asset).use { keyStore.load(it, password) }
            val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                init(keyStore, password)
            }
            val sslContext = SSLContext.getInstance("TLS").apply {
                init(keyManagers.keyManagers, null, null)
            }
            server = (sslContext.serverSocketFactory.createServerSocket(
                0,
                8,
                InetAddress.getByName("127.0.0.1"),
            ) as SSLServerSocket).apply {
                needClientAuth = false
                soTimeout = 500
            }
            thread = Thread({ serve() }, "xdm-rm08-tls-server").apply {
                isDaemon = true
                start()
            }
        }

        fun url(path: String): String = "https://127.0.0.1:${server.localPort}/$path"

        private fun serve() {
            while (running.get()) {
                val socket = try {
                    server.accept() as SSLSocket
                } catch (_: java.net.SocketTimeoutException) {
                    continue
                } catch (_: Throwable) {
                    break
                }
                socket.use { client ->
                    runCatching {
                        client.soTimeout = 3_000
                        client.startHandshake()
                        val reader = client.inputStream.bufferedReader(Charsets.US_ASCII)
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                        }
                        client.outputStream.buffered().use { output ->
                            output.write(
                                "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n"
                                    .toByteArray(Charsets.US_ASCII),
                            )
                            output.write(payload)
                            output.flush()
                        }
                    }
                }
            }
        }

        override fun close() {
            running.set(false)
            runCatching { server.close() }
            runCatching { thread.join(2_000L) }
        }
    }

    private companion object {
        val TERMINAL_STATES = setOf(
            Aria2TaskStatusValue.Complete,
            Aria2TaskStatusValue.Error,
            Aria2TaskStatusValue.Removed,
        )
        val PAUSED_STATES = setOf(
            Aria2TaskStatusValue.Paused,
            Aria2TaskStatusValue.Waiting,
        )
    }
}
