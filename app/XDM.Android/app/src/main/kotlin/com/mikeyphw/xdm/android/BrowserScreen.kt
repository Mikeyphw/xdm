package com.mikeyphw.xdm.android

import android.annotation.SuppressLint
import android.content.Context
import android.content.ClipboardManager
import android.content.Intent
import android.net.http.SslError
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
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
import com.mikeyphw.xdm.android.model.MediaCaptureStatus
import com.mikeyphw.xdm.android.model.MediaResolutionStatus
import com.mikeyphw.xdm.android.model.MediaSourceKind
import com.mikeyphw.xdm.android.util.formatBytes
import java.lang.ref.WeakReference
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

@Composable
fun BrowserScreen(
    captures: List<MediaCaptureRecord>,
    modifier: Modifier = Modifier,
    initialUrl: String? = null,
    onInitialUrlConsumed: (String) -> Unit = {},
    onMediaRequest: (url: String, pageTitle: String?, pageUrl: String?, mimeType: String?) -> Unit,
    onOpenMediaInbox: () -> Unit,
    onOpenAddForUrl: (url: String, pageTitle: String?) -> Unit,
    onBrowserDownloadRequest: (url: String, pageTitle: String?, fileName: String?) -> Unit,
    onDownloadMediaCapture: (MediaCaptureRecord) -> Unit,
    onResolveMediaCapture: (MediaCaptureRecord) -> Unit,
) {
    val context = LocalContext.current
    val sessionStore = remember { BrowserSessionStore(context.applicationContext) }
    val restoredTabs = remember { sessionStore.loadTabs() }
    val restoredActiveTabId = remember { sessionStore.loadActiveTabId() }
    var tabs by remember { mutableStateOf(restoredTabs.ifEmpty { listOf(BrowserTab.blank()) }) }
    var activeTabId by remember { mutableStateOf(restoredActiveTabId.takeIf { saved -> tabs.any { it.id == saved } } ?: tabs.first().id) }
    var history by remember { mutableStateOf(sessionStore.loadHistory()) }
    var bookmarks by remember { mutableStateOf(sessionStore.loadBookmarks()) }
    var showLibrary by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }
    var importedLinks by remember { mutableStateOf<List<BrowserImportedLink>>(emptyList()) }
    var cookieProfile by remember { mutableStateOf(sessionStore.loadCookieProfile()) }
    val activeTab = tabs.firstOrNull { it.id == activeTabId } ?: tabs.first()
    val browserTabSessionState = BrowserTabSessionState(
        activeTabId = activeTab.id,
        activeTabTitle = activeTab.title,
        activeTabUrl = activeTab.url,
        tabCount = tabs.size,
        restoredTabCount = restoredTabs.size,
        showRestoredSession = restoredTabs.isNotEmpty(),
        canCloseActiveTab = tabs.size > 1 || activeTab.url.isNotBlank(),
    )
    var addressBar by remember(activeTab.id) { mutableStateOf(activeTab.url) }
    var loadRequest by remember { mutableStateOf(activeTab.url.takeIf(String::isNotBlank)) }
    var currentPageUrl by remember { mutableStateOf(activeTab.url.takeIf(String::isNotBlank)) }
    var currentPageTitle by remember { mutableStateOf(activeTab.title.takeIf { it != NewTabTitle }) }
    var showHistory by remember { mutableStateOf(false) }
    var showTabSwitcher by remember { mutableStateOf(restoredTabs.size > 1) }
    var browserLoadState by remember { mutableStateOf<BrowserLoadState>(BrowserLoadState.StartPage) }
    var browserChromeState by remember { mutableStateOf(BrowserChromeState.StartPage) }
    var browserDownloadDraft by remember { mutableStateOf<BrowserDownloadBridgeDraft?>(null) }
    val browserNavigator = remember { BrowserNavigator() }
    val currentTitleState by rememberUpdatedState(currentPageTitle)
    val currentUrlState by rememberUpdatedState(currentPageUrl)
    val onMediaRequestState by rememberUpdatedState(onMediaRequest)
    val classifier = remember { MediaCandidateClassifier() }
    val sniffedUrls = remember { mutableStateListOf<String>() }
    val pageCaptures = captures.filter { it.pageUrl == currentPageUrl || it.sourceUrl in sniffedUrls }.distinctBy { it.id }
    val pageResources = remember(sniffedUrls.toList(), pageCaptures) { toBrowserPageResources(sniffedUrls, pageCaptures) }

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

    fun openBrowserInput(raw: String) {
        val normalized = normalizeBrowserInput(raw)
        addressBar = normalized
        browserLoadState = BrowserLoadState.Loading(normalized, 0)
        browserChromeState = BrowserChromeState(url = normalized, title = currentPageTitle, isLoading = true, progress = 0)
        browserDownloadDraft = null
        loadRequest = normalized
    }

    fun openHome() {
        addressBar = ""
        loadRequest = null
        currentPageUrl = null
        currentPageTitle = null
        sniffedUrls.clear()
        browserDownloadDraft = null
        browserLoadState = BrowserLoadState.StartPage
        browserChromeState = BrowserChromeState.StartPage
        val now = System.currentTimeMillis()
        val updated = tabs.map { tab ->
            if (tab.id == activeTabId) tab.copy(url = "", title = NewTabTitle, updatedAtEpochMs = now) else tab
        }
        tabs = updated
        sessionStore.saveTabs(updated, activeTabId)
    }

    fun openBrowserEntry(url: String, title: String? = null) {
        addressBar = url
        browserLoadState = BrowserLoadState.Loading(url, 0)
        browserChromeState = BrowserChromeState(url = url, title = title ?: currentPageTitle, isLoading = true, progress = 0)
        browserDownloadDraft = null
        loadRequest = url
    }

    fun toggleCurrentBookmark() {
        val url = (browserChromeState.url ?: currentPageUrl).orEmpty().takeIf(String::isNotBlank) ?: return
        val title = (browserChromeState.title ?: currentPageTitle ?: hostFromUrl(url)).takeIf { it.isNotBlank() } ?: hostFromUrl(url)
        bookmarks = sessionStore.toggleBookmark(BrowserBookmarkEntry(url, title, System.currentTimeMillis()))
    }

    fun pasteClipboardIntoImport() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val text = clipboard?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        importText = text
        importedLinks = extractBrowserImportLinks(text)
    }

    LaunchedEffect(activeTabId) {
        tabs.firstOrNull { it.id == activeTabId }?.let { tab ->
            addressBar = tab.url
            currentPageUrl = tab.url.takeIf(String::isNotBlank)
            currentPageTitle = tab.title.takeIf { it != NewTabTitle }
            browserDownloadDraft = null
            if (tab.url.isNotBlank()) {
                browserLoadState = BrowserLoadState.Loading(tab.url, 0)
                browserChromeState = BrowserChromeState(url = tab.url, title = tab.title.takeIf { it != NewTabTitle }, isLoading = true, progress = 0)
                loadRequest = tab.url
            } else {
                browserLoadState = BrowserLoadState.StartPage
                browserChromeState = BrowserChromeState.StartPage
                loadRequest = null
            }
        }
    }

    LaunchedEffect(initialUrl) {
        val incoming = initialUrl?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        val normalized = normalizeBrowserInput(incoming)
        addressBar = normalized
        browserLoadState = BrowserLoadState.Loading(normalized, 0)
        browserChromeState = BrowserChromeState(url = normalized, title = currentPageTitle, isLoading = true, progress = 0)
        browserDownloadDraft = null
        loadRequest = normalized
        onInitialUrlConsumed(incoming)
    }

    BackHandler(enabled = browserChromeState.canGoBack && !loadRequest.isNullOrBlank()) {
        browserNavigator.goBack()
    }

    Column(modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BrowserAddressBar(
            value = addressBar,
            pageTitle = browserChromeState.title ?: currentPageTitle,
            pageUrl = browserChromeState.url ?: currentPageUrl,
            canGoBack = browserChromeState.canGoBack,
            canGoForward = browserChromeState.canGoForward,
            isLoading = browserChromeState.isLoading,
            progress = browserChromeState.progress,
            onValueChanged = { addressBar = it },
            onGo = { openBrowserInput(addressBar) },
            onBack = { browserNavigator.goBack() },
            onForward = { browserNavigator.goForward() },
            onHome = { openHome() },
            onReload = {
                browserChromeState = browserChromeState.copy(isLoading = true, progress = 0)
                browserNavigator.reload()
            },
            onStop = {
                browserNavigator.stopLoading()
                browserChromeState = browserChromeState.copy(isLoading = false)
            },
            onAddPage = {
                val target = (browserChromeState.url ?: currentUrlState ?: loadRequest).orEmpty()
                if (target.isNotBlank()) onOpenAddForUrl(target, currentTitleState)
            },
        )
        BrowserSessionPanel(
            sessionState = browserTabSessionState,
            tabs = tabs,
            activeTabId = activeTabId,
            history = history,
            showHistory = showHistory,
            showTabSwitcher = showTabSwitcher,
            cookieProfile = cookieProfile,
            onToggleHistory = { showHistory = !showHistory },
            onToggleTabSwitcher = { showTabSwitcher = !showTabSwitcher },
            onSelectTab = { tab ->
                activeTabId = tab.id
                showTabSwitcher = false
            },
            onNewTab = {
                val tab = BrowserTab.blank()
                persistTabs(listOf(tab) + tabs, tab.id)
                addressBar = ""
                loadRequest = null
                currentPageUrl = null
                currentPageTitle = null
                browserLoadState = BrowserLoadState.StartPage
                browserChromeState = BrowserChromeState.StartPage
                browserDownloadDraft = null
                showTabSwitcher = false
            },
            onCloseActiveTab = {
                val remaining = tabs.filterNot { it.id == activeTabId }.ifEmpty { listOf(BrowserTab.blank()) }
                val nextActive = remaining.first().id
                persistTabs(remaining, nextActive)
                val next = remaining.first()
                addressBar = next.url
                loadRequest = next.url.takeIf(String::isNotBlank)
                browserLoadState = if (next.url.isBlank()) BrowserLoadState.StartPage else BrowserLoadState.Loading(next.url, 0)
                browserChromeState = if (next.url.isBlank()) BrowserChromeState.StartPage else BrowserChromeState(url = next.url, title = next.title.takeIf { it != NewTabTitle }, isLoading = true, progress = 0)
                browserDownloadDraft = null
                showTabSwitcher = remaining.size > 1
            },
            onSelectHistory = { entry ->
                openBrowserEntry(entry.url, entry.title)
                showHistory = false
            },
            onCookieProfileChanged = { profile ->
                cookieProfile = profile
                sessionStore.saveCookieProfile(profile)
            },
        )
        BrowserLibraryPanel(
            currentUrl = browserChromeState.url ?: currentPageUrl,
            currentTitle = browserChromeState.title ?: currentPageTitle,
            bookmarks = bookmarks,
            history = history,
            pageResources = pageResources,
            importedLinks = importedLinks,
            importText = importText,
            showLibrary = showLibrary,
            onToggleLibrary = { showLibrary = !showLibrary },
            onToggleBookmark = { toggleCurrentBookmark() },
            onRemoveBookmark = { bookmark ->
                bookmarks = sessionStore.saveBookmarks(bookmarks.filterNot { it.url.equals(bookmark.url, ignoreCase = true) })
            },
            onOpenUrl = { url, title -> openBrowserEntry(url, title) },
            onAddUrl = onOpenAddForUrl,
            onImportTextChanged = { value -> importText = value },
            onParseImportLinks = { importedLinks = extractBrowserImportLinks(importText) },
            onPasteClipboard = { pasteClipboardIntoImport() },
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (loadRequest.isNullOrBlank()) {
                BrowserStartPage(
                    history = history,
                    onOpen = { raw -> openBrowserInput(raw) },
                )
            } else {
                EmbeddedBrowser(
                    loadRequest = loadRequest,
                    classifier = classifier,
                    browserNavigator = browserNavigator,
                    cookieProfile = cookieProfile,
                    onPageChanged = { url, title -> updateActiveTab(url, title) },
                    onLoadStateChanged = { browserLoadState = it },
                    onNavigationChanged = { browserChromeState = it },
                    onMediaDiscovered = { url, mimeType ->
                        if (sniffedUrls.none { it.equals(url, ignoreCase = true) }) sniffedUrls += url
                        onMediaRequestState(url, currentTitleState, currentUrlState, mimeType)
                    },
                    onDownloadRequested = { draft ->
                        browserDownloadDraft = draft
                        browserLoadState = BrowserLoadState.Loaded(draft.url)
                        browserChromeState = browserChromeState.copy(url = draft.sourcePageUrl ?: draft.url, title = draft.sourcePageTitle ?: browserChromeState.title, isLoading = false, progress = 100)
                    },
                )
                BrowserLoadOverlay(
                    state = browserLoadState,
                    onRetry = {
                        val target = (browserLoadState.url ?: loadRequest).orEmpty()
                        if (target.isNotBlank()) {
                            browserLoadState = BrowserLoadState.Loading(target, 0)
                            browserChromeState = browserChromeState.copy(url = target, isLoading = true, progress = 0)
                        }
                        browserNavigator.reload()
                    },
                    onOpenExternal = {
                        val target = (browserLoadState.url ?: currentUrlState ?: loadRequest).orEmpty()
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target))) }
                    },
                    onAddPage = {
                        val target = (browserLoadState.url ?: currentUrlState ?: loadRequest).orEmpty()
                        if (target.isNotBlank()) onOpenAddForUrl(target, currentTitleState)
                    },
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
        }
        browserDownloadDraft?.let { draft ->
            BrowserDownloadBridgeCard(
                draft = draft,
                onAddDownload = {
                    onBrowserDownloadRequest(draft.url, draft.sourcePageTitle, draft.fileName)
                    browserDownloadDraft = null
                },
                onInspectMedia = {
                    if (sniffedUrls.none { it.equals(draft.url, ignoreCase = true) }) sniffedUrls += draft.url
                    onMediaRequestState(draft.url, draft.sourcePageTitle, draft.sourcePageUrl, draft.mimeType)
                },
                onDismiss = { browserDownloadDraft = null },
            )
        }
        BrowserMediaCockpit(
            currentUrl = currentPageUrl,
            currentTitle = currentPageTitle,
            captures = pageCaptures,
            sniffedCount = sniffedUrls.size,
            onOpenMediaInbox = onOpenMediaInbox,
            onOpenAddForUrl = onOpenAddForUrl,
            onDownloadSelected = onDownloadMediaCapture,
            onResolveSelected = onResolveMediaCapture,
        )
    }
}

@Composable
private fun BrowserAddressBar(
    value: String,
    pageTitle: String?,
    pageUrl: String?,
    canGoBack: Boolean,
    canGoForward: Boolean,
    isLoading: Boolean,
    progress: Int,
    onValueChanged: (String) -> Unit,
    onGo: () -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onHome: () -> Unit,
    onReload: () -> Unit,
    onStop: () -> Unit,
    onAddPage: () -> Unit,
) {
    val title = pageTitle?.takeIf { it.isNotBlank() } ?: "XDM Browser"
    val location = pageUrl?.takeIf { it.isNotBlank() } ?: "New tab"
    XdmListCard(compact = true) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(onClick = onBack, enabled = canGoBack, modifier = Modifier.semantics { contentDescription = "Browser back" }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Browser back") }
            IconButton(onClick = onForward, enabled = canGoForward, modifier = Modifier.semantics { contentDescription = "Browser forward" }) { Icon(Icons.AutoMirrored.Rounded.ArrowForward, "Browser forward") }
            IconButton(onClick = onHome, modifier = Modifier.semantics { contentDescription = "Browser home" }) { Icon(Icons.Rounded.Home, "Browser home") }
            if (isLoading) {
                TextButton(onClick = onStop, modifier = Modifier.semantics { contentDescription = "Stop loading" }) { Text("Stop") }
            } else {
                IconButton(onClick = onReload, enabled = pageUrl?.isNotBlank() == true, modifier = Modifier.semantics { contentDescription = "Reload page" }) { Icon(Icons.Rounded.Refresh, "Reload page") }
            }
            TextButton(onClick = onAddPage, enabled = pageUrl?.isNotBlank() == true) { Text("Add URL") }
        }
        XdmMetadataText(title, maxLines = 1)
        XdmSupportingText(if (isLoading) "Loading $progress% · $location" else location, maxLines = 1)
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
    sessionState: BrowserTabSessionState,
    tabs: List<BrowserTab>,
    activeTabId: String,
    history: List<BrowserHistoryEntry>,
    showHistory: Boolean,
    showTabSwitcher: Boolean,
    cookieProfile: BrowserCookieProfile,
    onToggleHistory: () -> Unit,
    onToggleTabSwitcher: () -> Unit,
    onSelectTab: (BrowserTab) -> Unit,
    onNewTab: () -> Unit,
    onCloseActiveTab: () -> Unit,
    onSelectHistory: (BrowserHistoryEntry) -> Unit,
    onCookieProfileChanged: (BrowserCookieProfile) -> Unit,
) {
    XdmListCard(compact = true) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                XdmMetadataText("Browser session")
                XdmSupportingText(sessionState.summary, maxLines = 2)
            }
            XdmMetadataText("${sessionState.tabCount} tab${if (sessionState.tabCount == 1) "" else "s"}")
        }
        if (sessionState.showRestoredSession) {
            XdmSupportingText("Restored ${sessionState.restoredTabCount} tab${if (sessionState.restoredTabCount == 1) "" else "s"} from the last browser session.", maxLines = 2)
        }
        XdmActionFlowRow {
            TextButton(onClick = onToggleTabSwitcher) { Text(if (showTabSwitcher) "Hide tabs" else "Show tabs") }
            TextButton(onClick = onNewTab) { Text("New tab") }
            TextButton(onClick = onCloseActiveTab, enabled = sessionState.canCloseActiveTab) { Text(if (tabs.size <= 1) "Clear tab" else "Close tab") }
        }
        if (showTabSwitcher) {
            BrowserTabSwitcher(
                tabs = tabs,
                activeTabId = activeTabId,
                onSelectTab = onSelectTab,
            )
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
        XdmSupportingText("Private tab isolation remains reserved for the privacy phase; use the Private cookie profile for temporary browsing until that model lands.", maxLines = 2)
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

@Composable
private fun BrowserTabSwitcher(
    tabs: List<BrowserTab>,
    activeTabId: String,
    onSelectTab: (BrowserTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        XdmSupportingText("Open tabs", maxLines = 1)
        XdmActionFlowRow {
            tabs.take(MaxVisibleTabs).forEach { tab ->
                FilterChip(
                    selected = tab.id == activeTabId,
                    onClick = { onSelectTab(tab) },
                    label = { Text(tab.displayLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            }
        }
        if (tabs.size > MaxVisibleTabs) {
            XdmSupportingText("${tabs.size - MaxVisibleTabs} more tab${if (tabs.size - MaxVisibleTabs == 1) "" else "s"} are retained in the restored session.", maxLines = 1)
        }
    }
}


@Composable
private fun BrowserLibraryPanel(
    currentUrl: String?,
    currentTitle: String?,
    bookmarks: List<BrowserBookmarkEntry>,
    history: List<BrowserHistoryEntry>,
    pageResources: List<BrowserPageResourceEntry>,
    importedLinks: List<BrowserImportedLink>,
    importText: String,
    showLibrary: Boolean,
    onToggleLibrary: () -> Unit,
    onToggleBookmark: () -> Unit,
    onRemoveBookmark: (BrowserBookmarkEntry) -> Unit,
    onOpenUrl: (String, String?) -> Unit,
    onAddUrl: (String, String?) -> Unit,
    onImportTextChanged: (String) -> Unit,
    onParseImportLinks: () -> Unit,
    onPasteClipboard: () -> Unit,
) {
    XdmListCard(compact = true) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                XdmMetadataText("Browser library")
                XdmSupportingText("Bookmarks, page resources, history, and imported links stay browser-scoped and separate from downloader history.", maxLines = 2)
            }
            XdmMetadataText("${bookmarks.size} saved")
        }
        XdmActionFlowRow {
            TextButton(onClick = onToggleLibrary) { Text(if (showLibrary) "Hide library" else "Library") }
            TextButton(onClick = onToggleBookmark, enabled = !currentUrl.isNullOrBlank()) { Text("Bookmark page") }
            TextButton(onClick = onPasteClipboard) { Text("Paste links") }
        }
        if (showLibrary) {
            XdmMetadataText("Bookmarks")
            if (bookmarks.isEmpty()) {
                XdmSupportingText("No bookmarks yet. Open a page and use Bookmark page to keep it here.", maxLines = 2)
            } else {
                bookmarks.take(MaxVisibleBookmarks).forEach { bookmark ->
                    BrowserLibraryRow(
                        title = bookmark.title,
                        url = bookmark.url,
                        onOpen = { onOpenUrl(bookmark.url, bookmark.title) },
                        onAdd = { onAddUrl(bookmark.url, bookmark.title) },
                        onRemove = { onRemoveBookmark(bookmark) },
                    )
                }
            }
            XdmMetadataText("Page resources")
            if (pageResources.isEmpty()) {
                XdmSupportingText("Open a page with media or downloadable resources and XDM will list review-first candidates here.", maxLines = 2)
            } else {
                pageResources.take(MaxVisiblePageResources).forEach { resource ->
                    BrowserLibraryRow(
                        title = resource.label,
                        url = resource.url,
                        onOpen = { onOpenUrl(resource.url, resource.label) },
                        onAdd = { onAddUrl(resource.url, resource.label) },
                    )
                }
            }
            XdmMetadataText("Import links")
            OutlinedTextField(
                value = importText,
                onValueChange = onImportTextChanged,
                label = { Text("Paste text with links") },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
            XdmActionFlowRow {
                Button(onClick = onParseImportLinks, enabled = importText.isNotBlank()) { Text("Find links") }
            }
            if (importedLinks.isNotEmpty()) {
                importedLinks.take(MaxVisibleImportLinks).forEach { link ->
                    BrowserLibraryRow(
                        title = link.label,
                        url = link.url,
                        onOpen = { onOpenUrl(link.url, link.label) },
                        onAdd = { onAddUrl(link.url, link.label) },
                    )
                }
            }
            XdmMetadataText("Recent history")
            if (history.isEmpty()) {
                XdmSupportingText("No browser history yet.", maxLines = 1)
            } else {
                history.take(MaxVisibleHistory).forEach { entry ->
                    BrowserLibraryRow(
                        title = entry.title,
                        url = entry.url,
                        onOpen = { onOpenUrl(entry.url, entry.title) },
                        onAdd = { onAddUrl(entry.url, entry.title) },
                    )
                }
            }
            currentUrl?.takeIf { it.isNotBlank() }?.let { url ->
                XdmSupportingText("Current page: ${currentTitle?.takeIf { it.isNotBlank() } ?: hostFromUrl(url)}", maxLines = 1)
            }
        }
    }
}

@Composable
private fun BrowserLibraryRow(
    title: String,
    url: String,
    onOpen: () -> Unit,
    onAdd: () -> Unit,
    onRemove: (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        XdmMetadataText(title.ifBlank { hostFromUrl(url) }, maxLines = 1)
        XdmSupportingText(url, maxLines = 1)
        XdmActionFlowRow {
            TextButton(onClick = onOpen) { Text("Open") }
            TextButton(onClick = onAdd) { Text("Add") }
            onRemove?.let { remove -> TextButton(onClick = remove) { Text("Remove") } }
        }
    }
}

@Composable
private fun BrowserStartPage(
    history: List<BrowserHistoryEntry>,
    onOpen: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        XdmListCard(compact = false) {
            XdmCardTitle("XDM Browser")
            XdmSupportingText(
                "Start with a URL or search above. The browser now opens on a visible start page instead of an empty WebView.",
                maxLines = 3,
            )
            XdmActionFlowRow {
                Button(onClick = { onOpen("duckduckgo.com") }) { Text("Search the web") }
                OutlinedButton(onClick = { onOpen("https://example.com") }) { Text("Test page") }
            }
        }
        XdmListCard(compact = true) {
            XdmMetadataText("Recent pages")
            if (history.isEmpty()) {
                XdmSupportingText("No history yet. Pages you open will appear here for quick relaunch.", maxLines = 2)
            } else {
                history.take(MaxVisibleHistory).forEach { entry ->
                    TextButton(onClick = { onOpen(entry.url) }, modifier = Modifier.fillMaxWidth()) {
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

@Composable
private fun BrowserLoadOverlay(
    state: BrowserLoadState,
    onRetry: () -> Unit,
    onOpenExternal: () -> Unit,
    onAddPage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        BrowserLoadState.StartPage,
        is BrowserLoadState.Loaded -> Unit
        is BrowserLoadState.Loading -> {
            XdmListCard(compact = true, modifier = modifier.fillMaxWidth().padding(8.dp)) {
                XdmMetadataText("Loading ${state.progress}%")
                LinearProgressIndicator(progress = { state.progress / 100f }, modifier = Modifier.fillMaxWidth())
                XdmSupportingText(state.url, maxLines = 1)
            }
        }
        is BrowserLoadState.Error -> {
            BrowserReliabilityCard(
                title = "Page did not load",
                detail = state.message,
                url = state.url,
                onRetry = onRetry,
                onOpenExternal = onOpenExternal,
                onAddPage = onAddPage,
                modifier = modifier.fillMaxWidth().padding(8.dp),
            )
        }
        is BrowserLoadState.Blank -> {
            BrowserReliabilityCard(
                title = "Blank page detected",
                detail = state.message,
                url = state.url,
                onRetry = onRetry,
                onOpenExternal = onOpenExternal,
                onAddPage = onAddPage,
                modifier = modifier.fillMaxWidth().padding(8.dp),
            )
        }
    }
}

@Composable
private fun BrowserReliabilityCard(
    title: String,
    detail: String,
    url: String?,
    onRetry: () -> Unit,
    onOpenExternal: () -> Unit,
    onAddPage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    XdmListCard(compact = false, modifier = modifier) {
        XdmCardTitle(title)
        XdmSupportingText(detail, maxLines = 3)
        if (!url.isNullOrBlank()) XdmMetadataText(url, maxLines = 1)
        XdmActionFlowRow {
            Button(onClick = onRetry) { Text("Retry") }
            OutlinedButton(onClick = onOpenExternal) { Text("Open externally") }
            TextButton(onClick = onAddPage, enabled = !url.isNullOrBlank()) { Text("Add URL") }
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
    onLoadStateChanged: (BrowserLoadState) -> Unit,
    onNavigationChanged: (BrowserChromeState) -> Unit,
    onMediaDiscovered: (String, String?) -> Unit,
    onDownloadRequested: (BrowserDownloadBridgeDraft) -> Unit,
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
                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        val progress = newProgress.coerceIn(0, 100)
                        val url = view?.url ?: loadRequest
                        onNavigationChanged(browserNavigator.snapshot(isLoading = progress < 100, progress = progress))
                        if (!url.isNullOrBlank() && progress < 100) {
                            onLoadStateChanged(BrowserLoadState.Loading(url, progress))
                        }
                    }

                    override fun onReceivedTitle(view: WebView?, title: String?) {
                        onPageChanged(view?.url, title)
                        onNavigationChanged(browserNavigator.snapshot(isLoading = false, progress = 100))
                    }
                }
                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        if (!url.isNullOrBlank()) onLoadStateChanged(BrowserLoadState.Loading(url, 0))
                        onNavigationChanged(browserNavigator.snapshot(isLoading = true, progress = 0).copy(url = url ?: view?.url, title = view?.title))
                        onPageChanged(url, view?.title)
                        sniffBrowserUrl(url, null, classifier, onMediaDiscovered)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        onPageChanged(url, view?.title)
                        onNavigationChanged(browserNavigator.snapshot(isLoading = false, progress = 100).copy(url = url ?: view?.url, title = view?.title))
                        if (!url.isNullOrBlank()) onLoadStateChanged(BrowserLoadState.Loaded(url))
                        sniffBrowserUrl(url, null, classifier, onMediaDiscovered)
                        val finishedUrl = url
                        view?.postDelayed({
                            val currentUrl = view.url
                            if (!finishedUrl.isNullOrBlank() && currentUrl == finishedUrl) {
                                view.evaluateJavascript(BlankPageProbeScript) { result ->
                                    if (result != "true" && view.url == finishedUrl) {
                                        onLoadStateChanged(BrowserLoadState.Blank(finishedUrl, "The page finished loading but did not expose visible content. Retry, open externally, or add the URL directly."))
                                    }
                                }
                            }
                        }, BlankPageProbeDelayMs)
                    }

                    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                        if (request?.isForMainFrame == true) {
                            val url = request.url?.toString() ?: view?.url
                            onNavigationChanged(browserNavigator.snapshot(isLoading = false, progress = 0).copy(url = url))
                            onLoadStateChanged(BrowserLoadState.Error(url, "WebView load error ${error?.errorCode ?: 0}: ${error?.description ?: "unknown error"}"))
                        }
                    }

                    override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                        if (request?.isForMainFrame == true) {
                            val url = request.url?.toString() ?: view?.url
                            onNavigationChanged(browserNavigator.snapshot(isLoading = false, progress = 0).copy(url = url))
                            onLoadStateChanged(BrowserLoadState.Error(url, "HTTP ${errorResponse?.statusCode ?: 0} ${errorResponse?.reasonPhrase.orEmpty()}".trim()))
                        }
                    }

                    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                        handler?.cancel()
                        onNavigationChanged(browserNavigator.snapshot(isLoading = false, progress = 0).copy(url = error?.url ?: view?.url))
                        onLoadStateChanged(BrowserLoadState.Error(error?.url ?: view?.url, "SSL error blocked. XDM will not proceed through certificate failures."))
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
                setDownloadListener { url, _, contentDisposition, mimeType, contentLength ->
                    val safeUrl = url?.takeIf { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) } ?: return@setDownloadListener
                    val fileName = URLUtil.guessFileName(safeUrl, contentDisposition, mimeType).takeIf { it.isNotBlank() } ?: "downloadfile"
                    val pageUrl = this.url?.takeIf { !it.equals(safeUrl, ignoreCase = true) }
                    val pageTitle = this.title?.takeIf { it.isNotBlank() }
                    onPageChanged(pageUrl ?: safeUrl, pageTitle ?: fileName)
                    onDownloadRequested(
                        BrowserDownloadBridgeDraft(
                            url = safeUrl,
                            fileName = fileName,
                            mimeType = mimeType?.takeIf { it.isNotBlank() },
                            contentLength = contentLength,
                            sourcePageUrl = pageUrl,
                            sourcePageTitle = pageTitle,
                        ),
                    )
                }
            }
        },
        update = { webView ->
            browserNavigator.attach(webView)
            webView.applyBrowserSettings(webView.context, cookieProfile)
            val target = loadRequest
            if (!target.isNullOrBlank() && target != lastLoaded) {
                lastLoaded = target
                onLoadStateChanged(BrowserLoadState.Loading(target, 0))
                onNavigationChanged(browserNavigator.snapshot(isLoading = true, progress = 0).copy(url = target))
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
private fun BrowserDownloadBridgeCard(
    draft: BrowserDownloadBridgeDraft,
    onAddDownload: () -> Unit,
    onInspectMedia: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    XdmListCard(modifier = modifier.fillMaxWidth(), compact = true) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                XdmCardTitle("Download detected")
                XdmSupportingText("XDM will always ask before sending browser downloads to the downloader.", maxLines = 2)
            }
            Icon(Icons.Rounded.Download, contentDescription = "Download detected")
        }
        XdmMetadataText(draft.fileName, maxLines = 1)
        XdmSupportingText(draft.detailLine, maxLines = 2)
        draft.sourcePageLabel?.let { XdmSupportingText(it, maxLines = 1) }
        XdmSupportingText("Cookies, tokens, and authorization headers are not shown here and are not persisted as raw browser handoff data.", maxLines = 2)
        XdmActionFlowRow {
            Button(onClick = onAddDownload) { Text("Add download") }
            TextButton(onClick = onInspectMedia) { Text("Inspect media") }
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

@Composable
private fun BrowserMediaCockpit(
    currentUrl: String?,
    currentTitle: String?,
    captures: List<MediaCaptureRecord>,
    sniffedCount: Int,
    onOpenMediaInbox: () -> Unit,
    onOpenAddForUrl: (url: String, pageTitle: String?) -> Unit,
    onDownloadSelected: (MediaCaptureRecord) -> Unit,
    onResolveSelected: (MediaCaptureRecord) -> Unit,
) {
    val featured = captures.maxWithOrNull(
        compareBy<MediaCaptureRecord> { it.variantCount }
            .thenBy { it.updatedAtEpochMs }
            .thenBy { it.title },
    )
    val groups = remember(captures) { captures.toBrowserMediaCockpitGroups() }
    XdmListCard(compact = true) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                XdmCardTitle(if (captures.isEmpty()) "Media cockpit" else "Media found")
                XdmSupportingText(
                    if (captures.isEmpty()) "Browser media tray upgraded: open a page and XDM will surface HLS, DASH, MP4, audio, and direct media candidates here before anything is queued."
                    else "Review detected media, choose the best candidate, or open the full Media inbox for variant diagnostics.",
                    maxLines = 3,
                )
            }
            Icon(Icons.Rounded.Download, contentDescription = "Media cockpit", tint = MaterialTheme.colorScheme.primary)
        }
        if (captures.isEmpty() && sniffedCount > 0) {
            XdmMetadataText("$sniffedCount candidate request${if (sniffedCount == 1) "" else "s"} observed while waiting for metadata persistence.")
        }
        if (groups.isNotEmpty()) {
            XdmMetadataText("Grouped captures")
            groups.forEach { group ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(group.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        XdmSupportingText(group.summary, maxLines = 1)
                    }
                    XdmMetadataText(group.countLabel, maxLines = 1)
                }
            }
        }
        featured?.let { capture ->
            BrowserMediaVariantCard(capture = capture)
            XdmActionFlowRow {
                Button(onClick = { onDownloadSelected(capture) }) { Text("Download selected") }
                TextButton(onClick = { onResolveSelected(capture) }) { Text("Resolve variants") }
                TextButton(onClick = onOpenMediaInbox) { Text("Review media") }
            }
        } ?: XdmActionFlowRow {
            Button(onClick = onOpenMediaInbox, enabled = captures.isNotEmpty()) { Text("Review media") }
        }
        XdmSupportingText(
            "Live, expiring, unknown, or protected-media signals stay review-first. XDM shows diagnostics and never bypasses protected media.",
            maxLines = 3,
        )
        XdmActionFlowRow {
            TextButton(onClick = { currentUrl?.let { onOpenAddForUrl(it, currentTitle) } }, enabled = !currentUrl.isNullOrBlank()) { Text("Add page URL") }
        }
    }
}

@Composable
private fun BrowserMediaVariantCard(
    capture: MediaCaptureRecord,
    modifier: Modifier = Modifier,
) {
    XdmListCard(compact = true, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                XdmMetadataText(capture.title, maxLines = 1)
                XdmSupportingText(capture.variantSummary, maxLines = 2)
            }
            XdmMetadataText(capture.mediaKindLabel, maxLines = 1)
        }
        XdmSupportingText(capture.mediaCockpitDiagnostics, maxLines = 2)
    }
}

private data class BrowserMediaCockpitGroup(
    val label: String,
    val count: Int,
    val summary: String,
) {
    val countLabel: String get() = "$count item${if (count == 1) "" else "s"}"
}

private fun List<MediaCaptureRecord>.toBrowserMediaCockpitGroups(): List<BrowserMediaCockpitGroup> = groupBy { it.mediaKindLabel }
    .map { (label, captures) ->
        BrowserMediaCockpitGroup(
            label = label,
            count = captures.size,
            summary = captures.maxByOrNull { it.variantCount }?.variantSummary ?: "Review capture",
        )
    }
    .sortedWith(compareByDescending<BrowserMediaCockpitGroup> { it.count }.thenBy { it.label })
    .take(MaxVisibleMediaGroups)

private val MediaCaptureRecord.mediaKindLabel: String
    get() = when (kind) {
        MediaSourceKind.HlsPlaylist -> "HLS"
        MediaSourceKind.DashManifest -> "DASH"
        MediaSourceKind.ProgressiveMedia -> "Progressive"
        MediaSourceKind.AudioStream -> "Audio"
        MediaSourceKind.VideoStream -> "Video"
        MediaSourceKind.DirectFile -> "Direct file"
        MediaSourceKind.Unknown -> "Unknown"
    }

private val MediaCaptureRecord.variantSummary: String
    get() = listOfNotNull(
        variantCount.takeIf { it > 0 }?.let { "$it variant${if (it == 1) "" else "s"}" },
        selectedVariantId?.takeIf { it.isNotBlank() }?.let { "selected" },
        mimeType?.takeIf { it.isNotBlank() },
        container?.takeIf { it.isNotBlank() },
        codecs?.takeIf { it.isNotBlank() },
    ).joinToString(" · ").ifBlank { "Candidate awaiting metadata" }

private val MediaCaptureRecord.mediaCockpitDiagnostics: String
    get() = when {
        resolutionStatus == MediaResolutionStatus.RequiresRefresh -> "Manifest may be expired; resolve variants before downloading."
        resolutionStatus == MediaResolutionStatus.Failed -> "Resolution failed; open Media diagnostics before retrying."
        status == MediaCaptureStatus.MetadataMissing -> "Metadata is incomplete; inspect media before queueing."
        kind == MediaSourceKind.HlsPlaylist || kind == MediaSourceKind.DashManifest -> "Adaptive stream detected; choose a variant before download."
        sourceUrl.contains("drm", ignoreCase = true) || sourceUrl.contains("widevine", ignoreCase = true) -> "Protected-media hint detected; diagnostics only, no bypass."
        sourceUrl.contains("live", ignoreCase = true) || sourceUrl.contains("m3u8", ignoreCase = true) -> "Possible live/expiring stream; review before queueing."
        else -> "Ready for review-first download handoff."
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


private data class BrowserDownloadBridgeDraft(
    val url: String,
    val fileName: String,
    val mimeType: String?,
    val contentLength: Long,
    val sourcePageUrl: String?,
    val sourcePageTitle: String?,
) {
    val detailLine: String
        get() = listOfNotNull(
            mimeType?.takeIf { it.isNotBlank() } ?: "unknown type",
            contentLength.takeIf { it > 0L }?.formatBytes() ?: "size unknown",
            hostFromUrl(url),
        ).joinToString(" · ")

    val sourcePageLabel: String?
        get() = sourcePageUrl?.let { page -> "From ${sourcePageTitle?.takeIf { it.isNotBlank() } ?: hostFromUrl(page)}" }
}

private data class BrowserTabSessionState(
    val activeTabId: String,
    val activeTabTitle: String,
    val activeTabUrl: String,
    val tabCount: Int,
    val restoredTabCount: Int,
    val showRestoredSession: Boolean,
    val canCloseActiveTab: Boolean,
) {
    val summary: String
        get() = "${activeTabTitle.ifBlank { NewTabTitle }} · ${activeTabUrl.ifBlank { "New tab" }}"
}

private data class BrowserChromeState(
    val url: String? = null,
    val title: String? = null,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val isLoading: Boolean = false,
    val progress: Int = 0,
) {
    companion object {
        val StartPage = BrowserChromeState(title = NewTabTitle)
    }
}

private sealed interface BrowserLoadState {
    val url: String?

    data object StartPage : BrowserLoadState { override val url: String? = null }
    data class Loading(override val url: String, val progress: Int) : BrowserLoadState
    data class Loaded(override val url: String) : BrowserLoadState
    data class Error(override val url: String?, val message: String) : BrowserLoadState
    data class Blank(override val url: String, val message: String) : BrowserLoadState
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

    fun stopLoading() {
        current?.get()?.stopLoading()
    }

    fun snapshot(isLoading: Boolean = false, progress: Int = 100): BrowserChromeState {
        val webView = current?.get()
        return BrowserChromeState(
            url = webView?.url,
            title = webView?.title?.takeIf(String::isNotBlank),
            canGoBack = webView?.canGoBack() == true,
            canGoForward = webView?.canGoForward() == true,
            isLoading = isLoading,
            progress = progress.coerceIn(0, 100),
        )
    }
}

private data class BrowserTab(
    val id: String,
    val url: String,
    val title: String,
    val updatedAtEpochMs: Long,
) {
    val displayLabel: String
        get() = title.takeIf { it.isNotBlank() }?.take(28) ?: hostFromUrl(url).take(28)

    companion object {
        fun blank(): BrowserTab = BrowserTab("tab-${System.currentTimeMillis()}", "", NewTabTitle, System.currentTimeMillis())
    }
}

private data class BrowserHistoryEntry(
    val url: String,
    val title: String,
    val visitedAtEpochMs: Long,
)

private data class BrowserBookmarkEntry(
    val url: String,
    val title: String,
    val savedAtEpochMs: Long,
)

private data class BrowserPageResourceEntry(
    val url: String,
    val label: String,
    val kind: String,
)

private data class BrowserImportedLink(
    val url: String,
    val label: String,
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

    fun loadBookmarks(): List<BrowserBookmarkEntry> = prefs.getString(KeyBookmarks, null)
        ?.lineSequence()
        ?.mapNotNull(::decodeBookmark)
        ?.take(MaxStoredBookmarks)
        ?.toList()
        .orEmpty()

    fun saveBookmarks(bookmarks: List<BrowserBookmarkEntry>): List<BrowserBookmarkEntry> {
        val trimmed = bookmarks.distinctBy { it.url.lowercase(Locale.US) }.take(MaxStoredBookmarks)
        prefs.edit().putString(KeyBookmarks, trimmed.joinToString("\n", transform = ::encodeBookmark)).apply()
        return trimmed
    }

    fun toggleBookmark(entry: BrowserBookmarkEntry): List<BrowserBookmarkEntry> {
        val existing = loadBookmarks()
        val updated = if (existing.any { it.url.equals(entry.url, ignoreCase = true) }) {
            existing.filterNot { it.url.equals(entry.url, ignoreCase = true) }
        } else {
            listOf(entry) + existing
        }
        return saveBookmarks(updated)
    }

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
    private fun encodeBookmark(entry: BrowserBookmarkEntry): String = listOf(entry.url, entry.title, entry.savedAtEpochMs.toString()).joinToString("\t") { encode(it) }

    private fun decodeBookmark(line: String): BrowserBookmarkEntry? {
        val parts = line.split('\t')
        if (parts.size < 3) return null
        return BrowserBookmarkEntry(
            url = decode(parts[0]).takeIf(String::isNotBlank) ?: return null,
            title = decode(parts[1]).ifBlank { hostFromUrl(decode(parts[0])) },
            savedAtEpochMs = decode(parts[2]).toLongOrNull() ?: 0L,
        )
    }


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
        private const val KeyBookmarks = "bookmarks"
        private const val KeyActiveTab = "active_tab"
        private const val KeyCookieProfile = "cookie_profile"
    }
}

private fun toBrowserPageResources(sniffedUrls: List<String>, captures: List<MediaCaptureRecord>): List<BrowserPageResourceEntry> {
    val sniffed = sniffedUrls.map { url -> BrowserPageResourceEntry(url, hostFromUrl(url), "Observed request") }
    val captured = captures.map { capture -> BrowserPageResourceEntry(capture.sourceUrl, capture.title, capture.mediaKindLabel) }
    return (captured + sniffed)
        .filter { it.url.startsWith("http://", ignoreCase = true) || it.url.startsWith("https://", ignoreCase = true) }
        .distinctBy { it.url.lowercase(Locale.US) }
        .take(MaxStoredPageResources)
}

private fun extractBrowserImportLinks(text: String): List<BrowserImportedLink> = BrowserImportUrlRegex.findAll(text)
    .map { it.value.trimEnd('.', ',', ';', ')', ']', '}') }
    .filter { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
    .distinctBy { it.lowercase(Locale.US) }
    .take(MaxImportedLinks)
    .map { url -> BrowserImportedLink(url, hostFromUrl(url)) }
    .toList()

private fun hostFromUrl(url: String): String = runCatching { Uri.parse(url).host?.removePrefix("www.") }.getOrNull()?.takeIf(String::isNotBlank) ?: "Browser page"

private val BrowserImportUrlRegex = Regex("""https?://[^\s<>'"]+""")

private const val NewTabTitle = "New tab"
private const val MaxVisibleTabs = 8
private const val MaxVisibleHistory = 6
private const val MaxVisibleBookmarks = 8
private const val MaxVisiblePageResources = 8
private const val MaxVisibleImportLinks = 8
private const val MaxVisibleMediaGroups = 4
private const val MaxStoredTabs = 12
private const val MaxStoredHistory = 80
private const val MaxStoredBookmarks = 80
private const val MaxStoredPageResources = 80
private const val MaxImportedLinks = 40
private const val DesktopUserAgent = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
private const val BlankPageProbeDelayMs = 1200L
private const val BlankPageProbeScript = """
(() => {
  const body = document.body;
  if (!body) return false;
  const visibleText = (body.innerText || '').trim().length > 0;
  const visibleMedia = document.images.length > 0 || document.querySelectorAll('video,audio,canvas,iframe').length > 0;
  const visibleBox = body.getBoundingClientRect && body.getBoundingClientRect().height > 24;
  return Boolean(visibleText || visibleMedia || visibleBox);
})()
"""
