package com.mikeyphw.xdm.android

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mikeyphw.xdm.android.media.MediaCandidateClassifier
import com.mikeyphw.xdm.android.media.MediaRequestFacts
import com.mikeyphw.xdm.android.model.MediaCaptureRecord
import java.lang.ref.WeakReference
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

@Composable
fun BrowserScreen(
    captures: List<MediaCaptureRecord>,
    onMediaRequest: (url: String, pageTitle: String?, pageUrl: String?, mimeType: String?) -> Unit,
    onOpenMediaInbox: () -> Unit,
    onOpenAddForUrl: (url: String, pageTitle: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val sessionStore = remember { BrowserSessionStore(context.applicationContext) }
    var tabs by remember { mutableStateOf(sessionStore.loadTabs().ifEmpty { listOf(BrowserTab.blank()) }) }
    var activeTabId by remember { mutableStateOf(sessionStore.loadActiveTabId().takeIf { saved -> tabs.any { it.id == saved } } ?: tabs.first().id) }
    var history by remember { mutableStateOf(sessionStore.loadHistory()) }
    var cookieProfile by remember { mutableStateOf(sessionStore.loadCookieProfile()) }
    val activeTab = tabs.firstOrNull { it.id == activeTabId } ?: tabs.first()
    var addressBar by remember(activeTab.id) { mutableStateOf(activeTab.url) }
    var loadRequest by remember { mutableStateOf(activeTab.url.takeIf(String::isNotBlank)) }
    var currentPageUrl by remember { mutableStateOf(activeTab.url.takeIf(String::isNotBlank)) }
    var currentPageTitle by remember { mutableStateOf(activeTab.title.takeIf { it != NewTabTitle }) }
    var showHistory by remember { mutableStateOf(false) }
    val browserNavigator = remember { BrowserNavigator() }
    val currentTitleState by rememberUpdatedState(currentPageTitle)
    val currentUrlState by rememberUpdatedState(currentPageUrl)
    val onMediaRequestState by rememberUpdatedState(onMediaRequest)
    val classifier = remember { MediaCandidateClassifier() }
    val sniffedUrls = remember { mutableStateListOf<String>() }
    val pageCaptures = captures.filter { it.pageUrl == currentPageUrl || it.sourceUrl in sniffedUrls }.distinctBy { it.id }

    fun persistTabs(updated: List<BrowserTab>, nextActiveTabId: String = activeTabId) {
        tabs = updated.ifEmpty { listOf(BrowserTab.blank()) }
        activeTabId = nextActiveTabId.takeIf { id -> tabs.any { it.id == id } } ?: tabs.first().id
        sessionStore.saveTabs(tabs, activeTabId)
    }

    fun updateActiveTab(url: String?, title: String?) {
        val normalizedUrl = url?.takeIf { it.isNotBlank() } ?: return
        val safeTitle = title?.takeIf { it.isNotBlank() } ?: hostFromUrl(normalizedUrl)
        currentPageUrl = normalizedUrl
        currentPageTitle = safeTitle
        addressBar = normalizedUrl
        val now = System.currentTimeMillis()
        val updated = tabs.map { tab ->
            if (tab.id == activeTabId) tab.copy(url = normalizedUrl, title = safeTitle, updatedAtEpochMs = now) else tab
        }
        tabs = updated
        sessionStore.saveTabs(updated, activeTabId)
        history = sessionStore.recordHistory(BrowserHistoryEntry(normalizedUrl, safeTitle, now))
    }

    LaunchedEffect(activeTabId) {
        tabs.firstOrNull { it.id == activeTabId }?.let { tab ->
            addressBar = tab.url
            currentPageUrl = tab.url.takeIf(String::isNotBlank)
            currentPageTitle = tab.title.takeIf { it != NewTabTitle }
            if (tab.url.isNotBlank()) loadRequest = tab.url
        }
    }

    Column(modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BrowserAddressBar(
            value = addressBar,
            onValueChanged = { addressBar = it },
            onGo = {
                val normalized = normalizeBrowserInput(addressBar)
                addressBar = normalized
                loadRequest = normalized
            },
            onBack = { browserNavigator.goBack() },
            onForward = { browserNavigator.goForward() },
            onReload = { browserNavigator.reload() },
        )
        BrowserSessionPanel(
            tabs = tabs,
            activeTabId = activeTabId,
            history = history,
            showHistory = showHistory,
            cookieProfile = cookieProfile,
            onToggleHistory = { showHistory = !showHistory },
            onSelectTab = { tab -> activeTabId = tab.id },
            onNewTab = {
                val tab = BrowserTab.blank()
                persistTabs(listOf(tab) + tabs, tab.id)
                addressBar = ""
                loadRequest = null
                currentPageUrl = null
                currentPageTitle = null
            },
            onCloseActiveTab = {
                val remaining = tabs.filterNot { it.id == activeTabId }.ifEmpty { listOf(BrowserTab.blank()) }
                val nextActive = remaining.first().id
                persistTabs(remaining, nextActive)
                val next = remaining.first()
                addressBar = next.url
                loadRequest = next.url.takeIf(String::isNotBlank)
            },
            onSelectHistory = { entry ->
                addressBar = entry.url
                loadRequest = entry.url
                showHistory = false
            },
            onCookieProfileChanged = { profile ->
                cookieProfile = profile
                sessionStore.saveCookieProfile(profile)
            },
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            EmbeddedBrowser(
                loadRequest = loadRequest,
                classifier = classifier,
                browserNavigator = browserNavigator,
                cookieProfile = cookieProfile,
                onPageChanged = { url, title -> updateActiveTab(url, title) },
                onMediaDiscovered = { url, mimeType ->
                    if (sniffedUrls.none { it.equals(url, ignoreCase = true) }) sniffedUrls += url
                    onMediaRequestState(url, currentTitleState, currentUrlState, mimeType)
                },
            )
        }
        BrowserMediaTray(
            currentUrl = currentPageUrl,
            currentTitle = currentPageTitle,
            captures = pageCaptures,
            sniffedCount = sniffedUrls.size,
            onOpenMediaInbox = onOpenMediaInbox,
            onOpenAddForUrl = onOpenAddForUrl,
        )
    }
}

@Composable
private fun BrowserAddressBar(
    value: String,
    onValueChanged: (String) -> Unit,
    onGo: () -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onReload: () -> Unit,
) {
    XdmListCard(compact = true) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Browser back" }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Browser back") }
            IconButton(onClick = onForward, modifier = Modifier.semantics { contentDescription = "Browser forward" }) { Icon(Icons.AutoMirrored.Rounded.ArrowForward, "Browser forward") }
            IconButton(onClick = onReload, modifier = Modifier.semantics { contentDescription = "Reload page" }) { Icon(Icons.Rounded.Refresh, "Reload page") }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChanged,
                label = { Text("URL or search") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = onGo, enabled = value.isNotBlank(), modifier = Modifier.sizeIn(minHeight = 48.dp)) { Text("Go") }
        }
    }
}

@Composable
private fun BrowserSessionPanel(
    tabs: List<BrowserTab>,
    activeTabId: String,
    history: List<BrowserHistoryEntry>,
    showHistory: Boolean,
    cookieProfile: BrowserCookieProfile,
    onToggleHistory: () -> Unit,
    onSelectTab: (BrowserTab) -> Unit,
    onNewTab: () -> Unit,
    onCloseActiveTab: () -> Unit,
    onSelectHistory: (BrowserHistoryEntry) -> Unit,
    onCookieProfileChanged: (BrowserCookieProfile) -> Unit,
) {
    XdmListCard(compact = true) {
        XdmMetadataText("Tabs")
        XdmActionFlowRow {
            tabs.take(MaxVisibleTabs).forEach { tab ->
                FilterChip(
                    selected = tab.id == activeTabId,
                    onClick = { onSelectTab(tab) },
                    label = { Text(tab.title.take(22), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            }
            TextButton(onClick = onNewTab) { Text("New tab") }
            TextButton(onClick = onCloseActiveTab) { Text(if (tabs.size <= 1) "Clear tab" else "Close tab") }
        }
        XdmMetadataText("Cookie profile")
        XdmActionFlowRow {
            BrowserCookieProfile.entries.forEach { profile ->
                FilterChip(
                    selected = cookieProfile == profile,
                    onClick = { onCookieProfileChanged(profile) },
                    label = { Text(profile.label) },
                )
            }
        }
        XdmSupportingText(cookieProfile.description, maxLines = 2)
        XdmActionFlowRow {
            TextButton(onClick = onToggleHistory) { Text(if (showHistory) "Hide history" else "History") }
        }
        if (showHistory) {
            if (history.isEmpty()) {
                XdmMetadataText("No browser history yet.")
            } else {
                history.take(MaxVisibleHistory).forEach { entry ->
                    TextButton(onClick = { onSelectHistory(entry) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                            XdmMetadataText(entry.title, maxLines = 1)
                            XdmSupportingText(entry.url, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun EmbeddedBrowser(
    loadRequest: String?,
    classifier: MediaCandidateClassifier,
    browserNavigator: BrowserNavigator,
    cookieProfile: BrowserCookieProfile,
    onPageChanged: (String?, String?) -> Unit,
    onMediaDiscovered: (String, String?) -> Unit,
) {
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var lastLoaded by remember { mutableStateOf<String?>(null) }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                browserNavigator.attach(this)
                applyBrowserSettings(context, cookieProfile)
                webChromeClient = object : WebChromeClient() {
                    override fun onReceivedTitle(view: WebView?, title: String?) {
                        onPageChanged(view?.url, title)
                    }
                }
                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        onPageChanged(url, view?.title)
                        sniffBrowserUrl(url, null, classifier, onMediaDiscovered)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        onPageChanged(url, view?.title)
                        sniffBrowserUrl(url, null, classifier, onMediaDiscovered)
                    }

                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val url = request?.url?.toString()
                        sniffBrowserUrl(url, null, classifier, onMediaDiscovered)
                        return false
                    }

                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val url = request?.url?.toString()
                        val accept = request?.requestHeaders?.entries?.firstOrNull { it.key.equals("Accept", ignoreCase = true) }?.value
                        if (url != null && classifier.isCandidate(MediaRequestFacts(url, accept))) {
                            mainHandler.post { onMediaDiscovered(url, accept) }
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                }
                setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
                    val title = URLUtil.guessFileName(url, contentDisposition, mimeType)
                    onPageChanged(this.url, this.title ?: title)
                    onMediaDiscovered(url, mimeType)
                }
            }
        },
        update = { webView ->
            browserNavigator.attach(webView)
            webView.applyBrowserSettings(webView.context, cookieProfile)
            val target = loadRequest
            if (!target.isNullOrBlank() && target != lastLoaded) {
                lastLoaded = target
                webView.loadUrl(target)
            }
        },
    )
    DisposableEffect(Unit) {
        onDispose {
            browserNavigator.detach()
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun WebView.applyBrowserSettings(context: Context, profile: BrowserCookieProfile) {
    // The embedded browser intentionally enables JavaScript so modern video pages can reveal
    // media manifests. XDM still keeps downloads review-first, blocks direct protected-media
    // queueing, and does not expose a JavaScript interface to page content.
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = !profile.privateMode
    settings.cacheMode = if (profile.privateMode) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT
    settings.mediaPlaybackRequiresUserGesture = true
    settings.useWideViewPort = profile.desktopMode
    settings.loadWithOverviewMode = profile.desktopMode
    settings.userAgentString = if (profile.desktopMode) DesktopUserAgent else WebSettings.getDefaultUserAgent(context)
    CookieManager.getInstance().setAcceptCookie(profile.acceptCookies)
    if (profile.privateMode) CookieManager.getInstance().removeSessionCookies(null)
}

@Composable
private fun BrowserMediaTray(
    currentUrl: String?,
    currentTitle: String?,
    captures: List<MediaCaptureRecord>,
    sniffedCount: Int,
    onOpenMediaInbox: () -> Unit,
    onOpenAddForUrl: (url: String, pageTitle: String?) -> Unit,
) {
    XdmListCard(compact = true) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                XdmCardTitle(if (captures.isEmpty()) "Browser media tray" else "${captures.size} media item${if (captures.size == 1) "" else "s"} found")
                XdmSupportingText(
                    if (captures.isEmpty()) "Open a page and XDM will sniff video, audio, HLS, DASH, and direct downloads without auto-queueing."
                    else "Review captured variants in the Media inbox before downloading.",
                    maxLines = 2,
                )
            }
            Icon(Icons.Rounded.Download, null, tint = MaterialTheme.colorScheme.primary)
        }
        if (captures.isNotEmpty()) {
            captures.take(3).forEach { capture ->
                Text(
                    text = capture.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else if (sniffedCount > 0) {
            XdmMetadataText("$sniffedCount candidate request${if (sniffedCount == 1) "" else "s"} observed while waiting for metadata persistence.")
        }
        XdmActionFlowRow {
            Button(onClick = onOpenMediaInbox, enabled = captures.isNotEmpty()) { Text("Review media") }
            TextButton(onClick = { currentUrl?.let { onOpenAddForUrl(it, currentTitle) } }, enabled = !currentUrl.isNullOrBlank()) { Text("Add page URL") }
        }
    }
}

private fun sniffBrowserUrl(
    url: String?,
    mimeType: String?,
    classifier: MediaCandidateClassifier,
    onMediaDiscovered: (String, String?) -> Unit,
) {
    val safeUrl = url?.takeIf { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) } ?: return
    if (classifier.isCandidate(MediaRequestFacts(safeUrl, mimeType))) onMediaDiscovered(safeUrl, mimeType)
}

private fun normalizeBrowserInput(input: String): String {
    val trimmed = input.trim()
    if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) return trimmed
    if (trimmed.contains('.') && !trimmed.contains(' ')) return "https://$trimmed"
    return "https://duckduckgo.com/?q=" + URLEncoder.encode(trimmed, "UTF-8")
}

private class BrowserNavigator {
    private var current: WeakReference<WebView>? = null

    fun attach(webView: WebView) {
        current = WeakReference(webView)
    }

    fun detach() {
        current = null
    }

    fun goBack() {
        current?.get()?.takeIf { it.canGoBack() }?.goBack()
    }

    fun goForward() {
        current?.get()?.takeIf { it.canGoForward() }?.goForward()
    }

    fun reload() {
        current?.get()?.reload()
    }
}

private data class BrowserTab(
    val id: String,
    val url: String,
    val title: String,
    val updatedAtEpochMs: Long,
) {
    companion object {
        fun blank(): BrowserTab = BrowserTab("tab-${System.currentTimeMillis()}", "", NewTabTitle, System.currentTimeMillis())
    }
}

private data class BrowserHistoryEntry(
    val url: String,
    val title: String,
    val visitedAtEpochMs: Long,
)

private enum class BrowserCookieProfile(
    val label: String,
    val description: String,
    val acceptCookies: Boolean,
    val privateMode: Boolean,
    val desktopMode: Boolean,
) {
    Standard("Standard", "Keeps normal cookies, storage, and the default mobile user agent for sites that require login.", true, false, false),
    Private("Private", "Disables persistent DOM storage, rejects cookies, and clears session cookies while browsing.", false, true, false),
    Desktop("Desktop", "Keeps cookies but requests desktop pages for sites that hide media behind mobile layouts.", true, false, true),
}

private class BrowserSessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("xdm_browser_sessions", Context.MODE_PRIVATE)

    fun loadActiveTabId(): String? = prefs.getString(KeyActiveTab, null)

    fun loadCookieProfile(): BrowserCookieProfile = prefs.getString(KeyCookieProfile, BrowserCookieProfile.Standard.name)
        ?.let { value -> BrowserCookieProfile.entries.firstOrNull { it.name == value } }
        ?: BrowserCookieProfile.Standard

    fun saveCookieProfile(profile: BrowserCookieProfile) {
        prefs.edit().putString(KeyCookieProfile, profile.name).apply()
    }

    fun loadTabs(): List<BrowserTab> = prefs.getString(KeyTabs, null)
        ?.lineSequence()
        ?.mapNotNull(::decodeTab)
        ?.take(MaxStoredTabs)
        ?.toList()
        .orEmpty()

    fun saveTabs(tabs: List<BrowserTab>, activeTabId: String) {
        prefs.edit()
            .putString(KeyTabs, tabs.take(MaxStoredTabs).joinToString("\n", transform = ::encodeTab))
            .putString(KeyActiveTab, activeTabId)
            .apply()
    }

    fun loadHistory(): List<BrowserHistoryEntry> = prefs.getString(KeyHistory, null)
        ?.lineSequence()
        ?.mapNotNull(::decodeHistory)
        ?.take(MaxStoredHistory)
        ?.toList()
        .orEmpty()

    fun recordHistory(entry: BrowserHistoryEntry): List<BrowserHistoryEntry> {
        val updated = (listOf(entry) + loadHistory().filterNot { it.url == entry.url }).take(MaxStoredHistory)
        prefs.edit().putString(KeyHistory, updated.joinToString("\n", transform = ::encodeHistory)).apply()
        return updated
    }

    private fun encodeTab(tab: BrowserTab): String = listOf(tab.id, tab.url, tab.title, tab.updatedAtEpochMs.toString()).joinToString("\t") { encode(it) }

    private fun decodeTab(line: String): BrowserTab? {
        val parts = line.split('\t')
        if (parts.size < 4) return null
        return BrowserTab(
            id = decode(parts[0]).takeIf(String::isNotBlank) ?: return null,
            url = decode(parts[1]),
            title = decode(parts[2]).ifBlank { NewTabTitle },
            updatedAtEpochMs = decode(parts[3]).toLongOrNull() ?: 0L,
        )
    }

    private fun encodeHistory(entry: BrowserHistoryEntry): String = listOf(entry.url, entry.title, entry.visitedAtEpochMs.toString()).joinToString("\t") { encode(it) }

    private fun decodeHistory(line: String): BrowserHistoryEntry? {
        val parts = line.split('\t')
        if (parts.size < 3) return null
        return BrowserHistoryEntry(
            url = decode(parts[0]).takeIf(String::isNotBlank) ?: return null,
            title = decode(parts[1]).ifBlank { hostFromUrl(decode(parts[0])) },
            visitedAtEpochMs = decode(parts[2]).toLongOrNull() ?: 0L,
        )
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
    private fun decode(value: String): String = URLDecoder.decode(value, "UTF-8")

    companion object {
        private const val KeyTabs = "tabs"
        private const val KeyHistory = "history"
        private const val KeyActiveTab = "active_tab"
        private const val KeyCookieProfile = "cookie_profile"
    }
}

private fun hostFromUrl(url: String): String = runCatching { Uri.parse(url).host?.removePrefix("www.") }.getOrNull()?.takeIf(String::isNotBlank) ?: "Browser page"

private const val NewTabTitle = "New tab"
private const val MaxVisibleTabs = 5
private const val MaxVisibleHistory = 6
private const val MaxStoredTabs = 12
private const val MaxStoredHistory = 80
private const val DesktopUserAgent = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
