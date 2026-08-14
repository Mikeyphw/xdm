package com.mikeyphw.xdm.android

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mikeyphw.xdm.android.scheduler.AndroidSecureRequestEnvelopeStore
import com.mikeyphw.xdm.android.scheduler.SecureRequestEnvelope
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExternalControlSecurityInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun exportedAutomationResolvesOnlyToReviewGate() {
        val resolved = context.packageManager.queryIntentActivities(
            Intent("com.mikeyphw.xdm.android.PAUSE_ALL").setPackage(context.packageName),
            0,
        )
        assertEquals(1, resolved.size)
        assertEquals(ExternalAutomationActivity::class.java.name, resolved.single().activityInfo.name)
        assertFalse(resolved.any { it.activityInfo.name == MainActivity::class.java.name })
    }

    @Test
    fun integrationSecretIsShownOnceAndOnlyVerifierIsStored() {
        val store = ExternalAutomationTrustStore(context)
        store.revoke()
        val secret = store.generateAndRotate()
        assertTrue(store.verify(secret))
        assertFalse(store.verify(secret + "x"))
        val verifier = File(context.noBackupFilesDir, "external-automation-verifier-v1.json")
        assertTrue(verifier.isFile)
        assertFalse(verifier.readText().contains(secret))
        store.revoke()
    }

    @Test
    fun exportedReviewActivitiesDoNotInheritMainActivity() {
        assertFalse(MainActivity::class.java.isAssignableFrom(ExternalAutomationActivity::class.java))
        assertFalse(MainActivity::class.java.isAssignableFrom(ExternalAddDownloadActivity::class.java))
    }

    @Test
    fun secureEnvelopeCiphertextDoesNotContainUrlOrAuthorization() {
        val root = File(context.noBackupFilesDir, "secure-request-envelopes-v1").apply { deleteRecursively() }
        val store = AndroidSecureRequestEnvelopeStore(context)
        store.put(
            SecureRequestEnvelope(
                subjectId = "download:instrumented",
                exactUrl = "https://cdn.example/video?token=super-secret",
                boundHost = "cdn.example",
                headers = mapOf("Authorization" to "Bearer private-secret"),
                expiresAtEpochMs = System.currentTimeMillis() + 60_000L,
                attemptGeneration = 4L,
            ),
        )
        val persisted = root.listFiles().orEmpty().joinToString("\n") { it.readText() }
        assertFalse(persisted.contains("super-secret"))
        assertFalse(persisted.contains("private-secret"))
        assertEquals(4L, store.get("download:instrumented")?.attemptGeneration)
        store.delete("download:instrumented")
    }

    @Test
    fun appBackupFlagIsDisabled() {
        val info = context.packageManager.getApplicationInfo(context.packageName, 0)
        assertEquals(0, info.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
    }
}
