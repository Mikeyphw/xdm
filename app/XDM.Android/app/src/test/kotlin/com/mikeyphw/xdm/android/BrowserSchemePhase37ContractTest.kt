package com.mikeyphw.xdm.android

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class BrowserSchemePhase37ContractTest {
    @Test
    fun customSchemeBelongsOnlyToExternalAddDownloadActivity() {
        val root = androidRoot()
        val manifest = File(root, "app/src/main/AndroidManifest.xml")
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(manifest)
        val activities = document.getElementsByTagName("activity")
        val owners = mutableListOf<String>()
        var captureHost = false
        var addHost = false
        var defaultCategory = false
        var browsableCategory = false

        for (index in 0 until activities.length) {
            val activity = activities.item(index) as Element
            val activityName = activity.androidAttribute("name")
            val filters = activity.getElementsByTagName("intent-filter")
            for (filterIndex in 0 until filters.length) {
                val filter = filters.item(filterIndex) as Element
                val data = filter.getElementsByTagName("data")
                val hasScheme = (0 until data.length).map { data.item(it) as Element }
                    .any { it.androidAttribute("scheme") == "${'$'}{xdmBrowserScheme}" }
                if (!hasScheme) continue
                owners += activityName
                val categories = filter.getElementsByTagName("category")
                defaultCategory = defaultCategory || (0 until categories.length).map { categories.item(it) as Element }
                    .any { it.androidAttribute("name") == "android.intent.category.DEFAULT" }
                browsableCategory = browsableCategory || (0 until categories.length).map { categories.item(it) as Element }
                    .any { it.androidAttribute("name") == "android.intent.category.BROWSABLE" }
                captureHost = captureHost || (0 until data.length).map { data.item(it) as Element }
                    .any { it.androidAttribute("host") == "capture" }
                addHost = addHost || (0 until data.length).map { data.item(it) as Element }
                    .any { it.androidAttribute("host") == "add" }
            }
        }

        assertEquals(listOf(".ExternalAddDownloadActivity"), owners.distinct())
        assertTrue(defaultCategory)
        assertTrue(browsableCategory)
        assertTrue(captureHost)
        assertTrue(addHost)
    }

    @Test
    fun buildVariantsOwnDistinctSchemesAndExposeBuildConfig() {
        val gradle = File(androidRoot(), "app/build.gradle.kts").readText()
        listOf("xdmdownload", "xdmdownload-debug").forEach { scheme ->
            assertTrue("Missing scheme $scheme", gradle.contains("\"$scheme\""))
        }
        assertTrue(gradle.contains("manifestPlaceholders[\"xdmBrowserScheme\"]"))
        assertTrue(gradle.contains("buildConfigField(\"String\", \"XDM_BROWSER_SCHEME\""))
    }

    @Test
    fun customSchemeIsParsedBeforeGenericExternalReceiverRouting() {
        val mainActivity = File(
            androidRoot(),
            "app/src/main/kotlin/com/mikeyphw/xdm/android/MainActivity.kt",
        ).readText()
        val parserIndex = mainActivity.indexOf("XdmBrowserDeepLinkParser.parse")
        val genericIndex = mainActivity.indexOf("shouldOpenExternalAddPrompt")
        assertTrue("Custom scheme parser must run before generic receiver routing", parserIndex in 0 until genericIndex)
        assertTrue(mainActivity.contains("BuildConfig.XDM_BROWSER_SCHEME"))
        assertTrue(mainActivity.contains("toAutomationCommandDraft"))
    }

    @Test
    fun phaseDoesNotAddBrowserRuntimeOrTopLevelRoute() {
        val root = androidRoot()
        val source = listOf(
            File(root, "browser-integration/src/main"),
            File(root, "app/src/main/kotlin/com/mikeyphw/xdm/android/MainActivity.kt"),
        ).flatMap { file -> if (file.isDirectory) file.walkTopDown().filter(File::isFile).toList() else listOf(file) }
            .joinToString("\n") { it.readText() }
        assertFalse(source.contains("android.webkit"))
        assertFalse(source.contains("WebView"))
        assertEquals(listOf("Downloads", "Add", "Media", "Library", "Activity", "Settings"), AppRoute.entries.map(AppRoute::label))
    }

    private fun Element.androidAttribute(name: String): String =
        getAttributeNS("http://schemas.android.com/apk/res/android", name)

    private fun androidRoot(): File {
        var current = File(System.getProperty("user.dir")).canonicalFile
        repeat(8) {
            if (File(current, "PROJECT_MANIFEST.json").isFile && File(current, "app/src/main").isDirectory) return current
            current = current.parentFile ?: return@repeat
        }
        error("Could not locate XDM.Android root")
    }
}
