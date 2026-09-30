package com.mikeyphw.xdm.android

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
import kotlinx.coroutines.withTimeout
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
        val context = instrumentation.targetContext
        val testContext = instrumentation.context
        resetRuntimeState(context)

        val trustedCa = testContext.assets.open("rm08/trusted-ca.pem").use { it.readBytes() }
        val trustFile = File(context.noBackupFilesDir, "aria2/rm08/trusted-ca.pem")
        val trustProvider = Aria2TrustBundleProvider {
            trustFile.parentFile?.mkdirs()
            trustFile.writeBytes(trustedCa)
            assertTrue("RM08 trust bundle must be app-private and non-empty", trustFile.isFile && trustFile.length() > 0L)
            trustFile
        }
        val store = Aria2SessionStore(context, trustProvider)
        val secretProvider = AppPrivateAria2SecretProvider(context)
        var manager = newManager(context, store, secretProvider)

        try {
            val capability = manager.probe()
            assertTrue(capability.summary, capability.isAvailable)
            assertTrue("installed aria2 must match the packaged runtime lock", capability.attestationVerified)
            assertNotNull(capability.binary)
            val descriptor = requireNotNull(capability.binary)
            assertEquals(context.applicationInfo.nativeLibraryDir, descriptor.file.parentFile?.absolutePath)
            assertEquals("android-unversioned-sonames-v1", descriptor.dependencyPolicy)
            assertFalse("packaged aria2 must not depend on versioned Linux SONAMEs", descriptor.neededLibraries.any { it.matches(Regex("^lib[^/]+\\.so\\.\\d+.*$")) })

            val started = manager.start()
            assertTrue("packaged aria2 failed to start: ${started.state}", started.started)
            assertTrue(started.state is Aria2ProcessState.Running)
            val rpc = manager.rpc()
            assertTrue("authenticated RPC must return an aria2 version", rpc.getVersion().version.isNotBlank())

            val untrustedPayload = "XDM RM08 untrusted TLS must fail\n".toByteArray()
            TlsProbeServer(testContext, "rm08/untrusted-server.p12", untrustedPayload).use { server ->
                val output = File(context.cacheDir, "rm08-untrusted-${System.nanoTime()}.bin")
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
                val terminal = awaitTerminal(rpc::tellStatus, gid)
                assertEquals("untrusted TLS must be rejected by aria2/OpenSSL", Aria2TaskStatusValue.Error, terminal.status)
                assertFalse("untrusted TLS must not publish payload bytes", output.exists() && output.length() > 0L)
                runCatching { rpc.removeDownloadResult(gid) }
                output.delete()
            }

            val trustedPayload = "XDM RM08 trusted HTTPS payload\n".toByteArray()
            TlsProbeServer(testContext, "rm08/trusted-server.p12", trustedPayload).use { server ->
                val output = File(context.cacheDir, "rm08-trusted-${System.nanoTime()}.bin")
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
                val terminal = awaitTerminal(rpc::tellStatus, gid)
                assertEquals("trusted test CA must permit HTTPS transfer", Aria2TaskStatusValue.Complete, terminal.status)
                assertTrue("trusted HTTPS transfer did not create output", output.isFile)
                assertArrayEquals(trustedPayload, output.readBytes())
                assertTrue("aria2.saveSession must succeed after HTTPS transfer", rpc.saveSession())
                rpc.removeDownloadResult(gid)
                output.delete()

                val recoveryOutput = File(context.cacheDir, "rm08-recovery-${System.nanoTime()}.bin")
                val recoveryGid = rpc.addUri(
                    listOf(server.url("recovery")),
                    Aria2TaskOptions(
                        directory = recoveryOutput.parentFile!!.canonicalPath,
                        outputName = recoveryOutput.name,
                        pause = true,
                        continueDownload = true,
                        split = 1,
                        maxConnectionsPerServer = 1,
                    ),
                )
                val created = rpc.tellStatus(recoveryGid)
                assertTrue(
                    "recovery fixture must be durably paused before shutdown (status=${created.status})",
                    created.status == Aria2TaskStatusValue.Paused || created.status == Aria2TaskStatusValue.Waiting,
                )
                val taskFiles = store.taskFiles("rm08-recovery", recoveryOutput)
                val now = System.currentTimeMillis()
                store.writeOwnershipMetadata(
                    taskFiles,
                    Aria2TaskMapping(
                        downloadId = "rm08-recovery",
                        gid = recoveryGid,
                        sourceUrl = server.url("recovery"),
                        mirrorUrls = emptyList(),
                        destinationUri = recoveryOutput.toURI().toString(),
                        destinationKey = "rm08-recovery",
                        fileName = recoveryOutput.name,
                        conflictPolicy = "Overwrite",
                        mimeType = "application/octet-stream",
                        outputPath = recoveryOutput.canonicalPath,
                        controlPath = taskFiles.control.canonicalPath,
                        ownershipMetadataPath = taskFiles.ownershipMetadata.canonicalPath,
                        sessionFilePath = taskFiles.session.canonicalPath,
                        expectedLength = trustedPayload.size.toLong(),
                        ownershipGeneration = 1L,
                        backendInstanceId = "rm08-device",
                        backendSessionId = "rm08-device-session",
                        status = "Paused",
                        createdAtEpochMs = now,
                        updatedAtEpochMs = now,
                        lastSynchronizedAtEpochMs = now,
                    ),
                )
                assertTrue("session checkpoint before process restart failed", rpc.saveSession())
                val stopped = manager.stop()
                assertTrue("aria2 shutdown must save its session", stopped.sessionSaved)
                assertTrue("aria2 shutdown must be clean for Gate-12", stopped.clean)
                assertTrue("saved session must retain the XDM-owned GID", store.sessionFile.readText().contains(recoveryGid))

                manager = newManager(context, store, secretProvider)
                val restarted = manager.start()
                assertTrue("aria2 restart from saved session failed: ${restarted.state}", restarted.started)
                val recovered = manager.rpc().tellStatus(recoveryGid)
                assertTrue(
                    "saved owned task was not recovered after daemon restart (status=${recovered.status})",
                    recovered.status == Aria2TaskStatusValue.Paused || recovered.status == Aria2TaskStatusValue.Waiting,
                )
                runCatching { manager.rpc().remove(recoveryGid, force = true) }
                runCatching { manager.rpc().removeDownloadResult(recoveryGid) }
                store.deleteTaskMetadata(taskFiles)
                recoveryOutput.delete()
            }

            instrumentation.sendStatus(
                2,
                Bundle().apply {
                    putString("xgo_rm08_aria2", "pass")
                    putString("xgo_rm08_claims", "packaged-bytes,rpc-auth,tls-reject,tls-success,session-restart-recovery")
                },
            )
        } finally {
            runCatching { manager.stop() }
            resetRuntimeState(context)
        }
    }

    private fun newManager(
        context: android.content.Context,
        store: Aria2SessionStore,
        secretProvider: AppPrivateAria2SecretProvider,
    ): Aria2ProcessManager = Aria2ProcessManager(
        capabilityProbe = AndroidAria2CapabilityProbe(context, store),
        sessionStore = store,
        secretProvider = secretProvider,
        startupTimeoutMillis = 10_000L,
        shutdownTimeoutMillis = 5_000L,
        pollIntervalMillis = 50L,
    )

    private suspend fun awaitTerminal(
        status: suspend (String) -> Aria2TaskStatus,
        gid: String,
    ): Aria2TaskStatus = withTimeout(30_000L) {
        while (true) {
            val current = status(gid)
            if (current.status in setOf(Aria2TaskStatusValue.Complete, Aria2TaskStatusValue.Error, Aria2TaskStatusValue.Removed)) {
                return@withTimeout current
            }
            delay(50L)
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    private fun resetRuntimeState(context: android.content.Context) {
        File(context.filesDir, "aria2").deleteRecursively()
        File(context.noBackupFilesDir, "aria2").deleteRecursively()
        context.getSharedPreferences("aria2-runtime", android.content.Context.MODE_PRIVATE).edit().clear().commit()
    }

    private class TlsProbeServer(
        context: android.content.Context,
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
}
