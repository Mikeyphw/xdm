package com.mikeyphw.xdm.android

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mikeyphw.xdm.android.browser.XdmBrowserDeepLinkContract
import com.mikeyphw.xdm.android.browser.XdmBrowserDeepLinkPayload
import com.mikeyphw.xdm.android.model.AutomationCommandAction
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BrowserCaptureEnvelopeManagerInstrumentedTest {
    @Test
    fun androidKeystoreOaepRoundTripUsesCurrentCaptureContract() {
        val manager = BrowserCaptureEnvelopeManager()

        manager.selfTestKeyWrap().getOrThrow()

        assertTrue(manager.expectedWrappedKeyBytes >= 256)
        assertTrue(manager.captureOaepHash == "SHA-1" || manager.captureOaepHash == "SHA-256")
    }

    @Test
    fun malformedWrappedKeySizeIsRejectedBeforeRsaDecrypt() {
        val manager = BrowserCaptureEnvelopeManager()
        val payload = XdmBrowserDeepLinkPayload(
            version = XdmBrowserDeepLinkContract.CurrentVersion,
            action = AutomationCommandAction.CaptureMedia,
            captureSessionId = "instrumented-session",
            captureKeyId = manager.keyId,
            wrappedKey = base64Url(ByteArray(32) { 7 }),
            envelopeIv = base64Url(ByteArray(12) { 3 }),
            envelopeCiphertext = base64Url(ByteArray(32) { 5 }),
        )

        val error = manager.decrypt(payload).exceptionOrNull()

        assertNotNull(error)
        assertTrue(error!!.message.orEmpty().contains("invalid encrypted-key size"))
        assertFalse(error.javaClass.simpleName == "IllegalBlockSizeException")
        assertFalse(error.cause?.javaClass?.simpleName == "IllegalBlockSizeException")
    }

    private fun base64Url(bytes: ByteArray): String = Base64.encodeToString(
        bytes,
        Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
    )
}
