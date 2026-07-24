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
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
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
    var showResourceInspector by remember { mutableStateOf(false) }
    var resourceFilter by remember { mutableStateOf(BrowserResourceFilter.All) }
    var importText by remember { mutableStateOf("") }
    var importedLinks by remember { mutableStateOf<List<BrowserImportedLink>>(emptyList()) }
    var cookieProfile by remember { mutableStateOf(sessionStore.loadCookieProfile()) }
    var browserPrivacySettings by remember { mutableStateOf(sessionStore.loadPrivacySettings()) }
    var showPrivacySettings by remember { mutableStateOf(false) }
    val activeTab = tabs.firstOrNull { it.id == activeTabId } ?: tabs.first()
    val activeTabIsPrivate = activeTab.isPrivate
    val privateTabCount = tabs.count { it.isPrivate }
    val effectiveCookieProfile = if (activeTabIsPrivate) BrowserCookieProfile.Private else cookieProfile
    val browserTabSessionState = BrowserTabSessionState(
        activeTabId = activeTab.id,
        activeTabTitle = activeTab.title,
        activeTabUrl = activeTab.url,
        tabCount = tabs.size,
        restoredTabCount = restoredTabs.size,
        showRestoredSession = restoredTabs.isNotEmpty(),
        canCloseActiveTab = tabs.size > 1 || activeTab.url.isNotBlank(),
        activeTabIsPrivate = activeTabIsPrivate,
        privateTabCount = privateTabCount,
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
    var browserPermissionPrompt by remember { mutableStateOf<BrowserPermissionPrompt?>(null) }
    var browserPermissionEvents by remember { mutableStateOf<List<BrowserPermissionEvent>>(emptyList()) }
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
        if (!activeTabIsPrivate) {
            history = sessionStore.recordHistory(BrowserHistoryEntry(normalizedUrl, safeTitle, now))
        }
    }

    fun openBrowserInput(raw: String) {
        val normalized = normalizeBrowserInput(raw, browserPrivacySettings.searchEngine)
        addressBar = normalized
        browserLoadState = BrowserLoadState.Loading(normalized, 0)
        browserChromeState = BrowserChromeState(url = normalized, title = currentPageTitle, isLoading = true, progress = 0)
        browserDownloadDraft = null
        loadRequest = normalized
    }

    fun openBrowserEntry(url: String, title: String? = null) {
        addressBar = url
        browserLoadState = BrowserLoadState.Loading(url, 0)
        browserChromeState = BrowserChromeState(url = url, title = title ?: currentPageTitle, isLoading = true, progress = 0)
        browserDownloadDraft = null
        loadRequest = url
    }

    fun openHome() {
        val homeUrl = browserPrivacySettings.homePage.url
        if (!homeUrl.isNullOrBlank()) {
            openBrowserEntry(homeUrl, browserPrivacySettings.homePage.label)
            return
        }
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

    fun updateBrowserPrivacySettings(settings: BrowserPrivacySettings) {
        browserPrivacySettings = settings
        sessionStore.savePrivacySettings(settings)
    }

    fun clearBrowserData() {
        browserNavigator.clearBrowsingData(context.applicationContext)
        sessionStore.clearBrowsingData()
        val blank = BrowserTab.blank()
        tabs = listOf(blank)
        activeTabId = blank.id
        history = emptyList()
        addressBar = ""
        loadRequest = null
        currentPageUrl = null
        currentPageTitle = null
        sniffedUrls.clear()
        importedLinks = emptyList()
        importText = ""
        browserDownloadDraft = null
        browserLoadState = BrowserLoadState.StartPage
        browserChromeState = BrowserChromeState.StartPage
    }

    fun openNewPrivateTab() {
        browserNavigator.clearPrivateBrowsingData()
        val tab = BrowserTab.blank(isPrivate = true)
        persistTabs(listOf(tab) + tabs, tab.id)
        addressBar = ""
        loadRequest = null
        currentPageUrl = null
        currentPageTitle = null
        sniffedUrls.clear()
        browserDownloadDraft = null
        browserLoadState = BrowserLoadState.StartPage
        browserChromeState = BrowserChromeState.StartPage
        showTabSwitcher = false
    }

    fun closePrivateTabs() {
        if (tabs.none { it.isPrivate }) return
        browserNavigator.clearPrivateBrowsingData()
        val remaining = tabs.filterNot { it.isPrivate }.ifEmpty { listOf(BrowserTab.blank()) }
        val next = remaining.first()
        persistTabs(remaining, next.id)
        addressBar = next.url
        loadRequest = next.url.takeIf(String::isNotBlank)
        currentPageUrl = next.url.takeIf(String::isNotBlank)
        currentPageTitle = next.title.takeIf { it != NewTabTitle }
        sniffedUrls.clear()
        browserDownloadDraft = null
        browserLoadState = if (next.url.isBlank()) BrowserLoadState.StartPage else BrowserLoadState.Loading(next.url, 0)
        browserChromeState = if (next.url.isBlank()) BrowserChromeState.StartPage else BrowserChromeState(url = next.url, title = next.title.takeIf { it != NewTabTitle }, isLoading = next.url.isNotBlank(), progress = 0)
        showTabSwitcher = remaining.size > 1
    }

    fun resetBrowserPrivacySettings() {
        updateBrowserPrivacySettings(BrowserPrivacySettings())
    }

    fun rememberPermissionDecision(prompt: BrowserPermissionPrompt, decision: String) {
        browserPermissionEvents = (listOf(BrowserPermissionEvent(prompt.origin, prompt.summaryLabel, decision, System.currentTimeMillis())) + browserPermissionEvents).take(MaxVisiblePermissionEvents)
        browserPermissionPrompt = null
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
        val normalized = normalizeBrowserInput(incoming, browserPrivacySettings.searchEngine)
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

    Box(modifier.fillMaxSize()) {
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxSize()
                .sizeIn(maxWidth = BrowserMaxContentWidthDp.dp)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
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
        BrowserVisualStatusBar(
            tabCount = tabs.size,
            mediaCount = pageCaptures.size,
            resourceCount = pageResources.size,
            bookmarkCount = bookmarks.size,
            isPrivateProfile = activeTabIsPrivate || effectiveCookieProfile.privateMode,
            desktopMode = browserPrivacySettings.desktopModeDefault,
        )
        BrowserSessionPanel(
            sessionState = browserTabSessionState,
            tabs = tabs,
            activeTabId = activeTabId,
            history = history,
            showHistory = showHistory,
            showTabSwitcher = showTabSwitcher,
            cookieProfile = effectiveCookieProfile,
            privacySettings = browserPrivacySettings,
            showPrivacySettings = showPrivacySettings,
            onToggleHistory = { showHistory = !showHistory },
            onToggleTabSwitcher = { showTabSwitcher = !showTabSwitcher },
            onTogglePrivacySettings = { showPrivacySettings = !showPrivacySettings },
            onNewPrivateTab = ::openNewPrivateTab,
            onClosePrivateTabs = ::closePrivateTabs,
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
                if (activeTabIsPrivate) browserNavigator.clearPrivateBrowsingData()
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
            onPrivacySettingsChanged = ::updateBrowserPrivacySettings,
            onClearBrowsingData = ::clearBrowserData,
            onResetBrowserSettings = ::resetBrowserPrivacySettings,
        )
        BrowserPermissionStatusPanel(
            prompt = browserPermissionPrompt,
            events = browserPermissionEvents,
            activeTabIsPrivate = activeTabIsPrivate,
            onGrant = { prompt ->
                prompt.onGrant()
                rememberPermissionDecision(prompt, "Granted once")
            },
            onDeny = { prompt ->
                prompt.onDeny()
                rememberPermissionDecision(prompt, "Denied")
            },
            onDismiss = { prompt -> rememberPermissionDecision(prompt, "Dismissed") },
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
        BrowserResourceInspectorPanel(
            currentUrl = currentPageUrl,
            currentTitle = currentPageTitle,
            resources = pageResources,
            filter = resourceFilter,
            showInspector = showResourceInspector,
            onToggleInspector = { showResourceInspector = !showResourceInspector },
            onFilterChanged = { resourceFilter = it },
            onOpenResource = { url, title -> openBrowserEntry(url, title) },
            onAddResource = onOpenAddForUrl,
            onInspectResource = { url, title -> onMediaRequestState(url, title, currentUrlState, null) },
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (loadRequest.isNullOrBlank()) {
                BrowserStartPage(
                    history = history,
                    settings = browserPrivacySettings,
                    onOpen = { raw -> openBrowserInput(raw) },
                )
            } else {
                EmbeddedBrowser(
                    loadRequest = loadRequest,
                    classifier = classifier,
                    browserNavigator = browserNavigator,
                    cookieProfile = effectiveCookieProfile,
                    browserSettings = browserPrivacySettings,
                    onPageChanged = { url, title -> updateActiveTab(url, title) },
                    onLoadStateChanged = { browserLoadState = it },
                    onNavigationChanged = { browserChromeState = it },
                    onMediaDiscovered = { url, mimeType ->
                        if (!activeTabIsPrivate) {
                            if (sniffedUrls.none { it.equals(url, ignoreCase = true) }) sniffedUrls += url
                            onMediaRequestState(url, currentTitleState, currentUrlState, mimeType)
                        }
                    },
                    onDownloadRequested = { draft ->
                        browserDownloadDraft = draft
                        browserLoadState = BrowserLoadState.Loaded(draft.url)
                        browserChromeState = browserChromeState.copy(url = draft.sourcePageUrl ?: draft.url, title = draft.sourcePageTitle ?: browserChromeState.title, isLoading = false, progress = 100)
                    },
                    onPermissionRequested = { prompt -> browserPermissionPrompt = prompt },
                    onPermissionCanceled = { origin ->
                        if (browserPermissionPrompt?.origin == origin) browserPermissionPrompt = null
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
}

@Composable
private fun BrowserVisualStatusBar(
    tabCount: Int,
    mediaCount: Int,
    resourceCount: Int,
    bookmarkCount: Int,
    isPrivateProfile: Boolean,
    desktopMode: Boolean,
    modifier: Modifier = Modifier,
) {
    XdmListCard(compact = true, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                XdmMetadataText("Adaptive browser cockpit")
                XdmSupportingText("Flat, compact hierarchy keeps navigation, library, downloads, and media capture visible without turning Browser into another hidden tray.", maxLines = 2)
            }
            XdmMetadataText(if (desktopMode) "Desktop" else "Mobile", maxLines = 1)
        }
        XdmActionFlowRow {
            XdmMetadataText("$tabCount tab${if (tabCount == 1) "" else "s"}", maxLines = 1)
            XdmMetadataText("$mediaCount media", maxLines = 1)
            XdmMetadataText("$resourceCount resources", maxLines = 1)
            XdmMetadataText("$bookmarkCount bookmarks", maxLines = 1)
            XdmMetadataText(if (isPrivateProfile) "Private profile" else "Normal profile", maxLines = 1)
        }
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
        XdmSupportingText(if (isLoading) "Loading $progress% · $location" else "Ready · $location", maxLines = 1)
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
    privacySettings: BrowserPrivacySettings,
    showPrivacySettings: Boolean,
    onToggleHistory: () -> Unit,
    onToggleTabSwitcher: () -> Unit,
    onTogglePrivacySettings: () -> Unit,
    onNewPrivateTab: () -> Unit,
    onClosePrivateTabs: () -> Unit,
    onSelectTab: (BrowserTab) -> Unit,
    onNewTab: () -> Unit,
    onCloseActiveTab: () -> Unit,
    onSelectHistory: (BrowserHistoryEntry) -> Unit,
    onCookieProfileChanged: (BrowserCookieProfile) -> Unit,
    onPrivacySettingsChanged: (BrowserPrivacySettings) -> Unit,
    onClearBrowsingData: () -> Unit,
    onResetBrowserSettings: () -> Unit,
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
        if (sessionState.activeTabIsPrivate) {
            XdmSupportingText("Private tab active: XDM skips browser history and session restore, suppresses passive media capture, rejects cookies, disables DOM storage, and clears session cookies when private tabs close.", maxLines = 3)
        } else if (sessionState.privateTabCount > 0) {
            XdmSupportingText("${sessionState.privateTabCount} private tab${if (sessionState.privateTabCount == 1) "" else "s"} open; they are not saved into the restored browser session.", maxLines = 2)
        }
        XdmActionFlowRow {
            TextButton(onClick = onToggleTabSwitcher) { Text(if (showTabSwitcher) "Hide tabs" else "Show tabs") }
            TextButton(onClick = onNewTab) { Text("New tab") }
            TextButton(onClick = onNewPrivateTab) { Text("New private tab") }
            TextButton(onClick = onCloseActiveTab, enabled = sessionState.canCloseActiveTab) { Text(if (tabs.size <= 1) "Clear tab" else "Close tab") }
            TextButton(onClick = onClosePrivateTabs, enabled = sessionState.privateTabCount > 0) { Text("Clear private") }
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
        BrowserPrivacySettingsPanel(
            settings = privacySettings,
            showSettings = showPrivacySettings,
            onToggleSettings = onTogglePrivacySettings,
            onSettingsChanged = onPrivacySettingsChanged,
            onClearBrowsingData = onClearBrowsingData,
            onResetBrowserSettings = onResetBrowserSettings,
        )
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
private fun BrowserPrivacySettingsPanel(
    settings: BrowserPrivacySettings,
    showSettings: Boolean,
    onToggleSettings: () -> Unit,
    onSettingsChanged: (BrowserPrivacySettings) -> Unit,
    onClearBrowsingData: () -> Unit,
    onResetBrowserSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        XdmActionFlowRow {
            TextButton(onClick = onToggleSettings) { Text(if (showSettings) "Hide privacy" else "Privacy settings") }
            TextButton(onClick = onClearBrowsingData) { Text("Clear browser data") }
            TextButton(onClick = onResetBrowserSettings) { Text("Reset browser settings") }
        }
        if (showSettings) {
            XdmMetadataText("Browser settings")
            XdmSupportingText("Settings stay browser-scoped in SharedPreferences. Clearing browser data removes tabs, history, cache, DOM storage, and cookies while keeping bookmarks.", maxLines = 3)
            XdmMetadataText("Homepage")
            XdmActionFlowRow {
                BrowserHomePage.entries.forEach { home ->
                    FilterChip(
                        selected = settings.homePage == home,
                        onClick = { onSettingsChanged(settings.copy(homePage = home)) },
                        label = { Text(home.label) },
                    )
                }
            }
            XdmMetadataText("Search engine")
            XdmActionFlowRow {
                BrowserSearchEngine.entries.forEach { engine ->
                    FilterChip(
                        selected = settings.searchEngine == engine,
                        onClick = { onSettingsChanged(settings.copy(searchEngine = engine)) },
                        label = { Text(engine.label) },
                    )
                }
            }
            XdmMetadataText("Site capabilities")
            XdmSupportingText("Camera, microphone, and location requests are review-first. Private tabs warn before granting and never persist permission state.", maxLines = 2)
            XdmActionFlowRow {
                FilterChip(selected = settings.javaScriptEnabled, onClick = { onSettingsChanged(settings.copy(javaScriptEnabled = !settings.javaScriptEnabled)) }, label = { Text("JavaScript") })
                FilterChip(selected = settings.domStorageEnabled, onClick = { onSettingsChanged(settings.copy(domStorageEnabled = !settings.domStorageEnabled)) }, label = { Text("DOM storage") })
                FilterChip(selected = settings.desktopModeDefault, onClick = { onSettingsChanged(settings.copy(desktopModeDefault = !settings.desktopModeDefault)) }, label = { Text("Desktop default") })
            }
            XdmMetadataText("Cookies")
            XdmActionFlowRow {
                FilterChip(selected = settings.cookiesEnabled, onClick = { onSettingsChanged(settings.copy(cookiesEnabled = !settings.cookiesEnabled)) }, label = { Text("Cookies") })
                FilterChip(selected = settings.thirdPartyCookiesEnabled, enabled = settings.cookiesEnabled, onClick = { onSettingsChanged(settings.copy(thirdPartyCookiesEnabled = !settings.thirdPartyCookiesEnabled)) }, label = { Text("Third-party cookies") })
            }
            XdmSupportingText("Private profile and private tabs override these toggles by rejecting cookies, disabling DOM storage, skipping browser history, suppressing passive media capture, and clearing session cookies. XDM still does not persist raw cookie, token, or sensitive header handoff data.", maxLines = 3)
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
private fun BrowserPermissionStatusPanel(
    prompt: BrowserPermissionPrompt?,
    events: List<BrowserPermissionEvent>,
    activeTabIsPrivate: Boolean,
    onGrant: (BrowserPermissionPrompt) -> Unit,
    onDeny: (BrowserPermissionPrompt) -> Unit,
    onDismiss: (BrowserPermissionPrompt) -> Unit,
    modifier: Modifier = Modifier,
) {
    XdmListCard(compact = true, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                XdmMetadataText("Site permissions")
                XdmSupportingText("Camera, microphone, and location prompts are review-first and never stored as durable grants by XDM.", maxLines = 2)
            }
            XdmMetadataText(if (activeTabIsPrivate) "Private guarded" else "Ask first", maxLines = 1)
        }
        prompt?.let { request ->
            XdmCardTitle("Permission request")
            XdmSupportingText("${request.origin} wants ${request.summaryLabel}.", maxLines = 2)
            if (activeTabIsPrivate) XdmSupportingText("Private tabs reject persistence; granting is one-shot and session-scoped.", maxLines = 2)
            XdmActionFlowRow {
                Button(onClick = { onGrant(request) }) { Text("Grant once") }
                OutlinedButton(onClick = { onDeny(request) }) { Text("Deny") }
                TextButton(onClick = { onDismiss(request) }) { Text("Dismiss") }
            }
        } ?: run {
            XdmMetadataText("No pending site permission request.")
        }
        if (events.isNotEmpty()) {
            XdmMetadataText("Recent permission decisions")
            events.take(MaxVisiblePermissionEvents).forEach { event ->
                XdmSupportingText("${event.decision}: ${event.label} · ${event.origin}", maxLines = 1)
            }
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
private fun BrowserResourceInspectorPanel(
    currentUrl: String?,
    currentTitle: String?,
    resources: List<BrowserPageResourceEntry>,
    filter: BrowserResourceFilter,
    showInspector: Boolean,
    onToggleInspector: () -> Unit,
    onFilterChanged: (BrowserResourceFilter) -> Unit,
    onOpenResource: (String, String?) -> Unit,
    onAddResource: (String, String?) -> Unit,
    onInspectResource: (String, String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val filtered = remember(resources, filter) { resources.filter { filter.matches(it) }.take(MaxVisibleInspectorResources) }
    XdmListCard(compact = true, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                XdmMetadataText("Resource inspector")
                XdmSupportingText("Review page resources before sending direct files to Add Download or media candidates to the capture cockpit.", maxLines = 2)
            }
            XdmMetadataText("${resources.size} seen", maxLines = 1)
        }
        XdmActionFlowRow {
            TextButton(onClick = onToggleInspector) { Text(if (showInspector) "Hide resources" else "Inspect resources") }
            BrowserResourceFilter.entries.forEach { option ->
                FilterChip(selected = filter == option, onClick = { onFilterChanged(option) }, label = { Text(option.label) })
            }
        }
        if (showInspector) {
            if (filtered.isEmpty()) {
                XdmSupportingText("No resources match ${filter.label.lowercase(Locale.US)} yet. Open a page and XDM will list observed requests and captured media here.", maxLines = 2)
            } else {
                filtered.forEach { resource ->
                    BrowserResourceInspectorRow(
                        resource = resource,
                        onOpen = { onOpenResource(resource.url, resource.label) },
                        onAdd = { onAddResource(resource.url, resource.label) },
                        onInspect = { onInspectResource(resource.url, resource.label) },
                    )
                }
            }
            currentUrl?.takeIf { it.isNotBlank() }?.let { url ->
                XdmSupportingText("Source page: ${currentTitle?.takeIf { it.isNotBlank() } ?: hostFromUrl(url)}", maxLines = 1)
            }
        }
    }
}

@Composable
private fun BrowserResourceInspectorRow(
    resource: BrowserPageResourceEntry,
    onOpen: () -> Unit,
    onAdd: () -> Unit,
    onInspect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                XdmMetadataText(resource.label.ifBlank { hostFromUrl(resource.url) }, maxLines = 1)
                XdmSupportingText(resource.url, maxLines = 1)
            }
            XdmMetadataText(resource.kind, maxLines = 1)
        }
        XdmActionFlowRow {
            TextButton(onClick = onOpen) { Text("Open") }
            TextButton(onClick = onAdd) { Text("Add") }
            TextButton(onClick = onInspect) { Text("Inspect media") }
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
    settings: BrowserPrivacySettings,
    onOpen: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        XdmListCard(compact = false) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    XdmCardTitle("XDM Browser")
                    XdmSupportingText(
                        "Clean start page for URL entry, search, downloads, media capture, and library recall. Default search: ${settings.searchEngine.label}.",
                        maxLines = 3,
                    )
                }
                XdmMetadataText(settings.homePage.label, maxLines = 1)
            }
            XdmActionFlowRow {
                Button(onClick = { onOpen(settings.searchEngine.startPageUrl) }) { Text("Search") }
                OutlinedButton(onClick = { onOpen(settings.homePage.url ?: "https://example.com") }) { Text("Homepage") }
            }
            XdmSupportingText("Adaptive layout centers the browser cockpit on wide screens and keeps phone actions compact and thumb-reachable.", maxLines = 2)
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
    browserSettings: BrowserPrivacySettings,
    onPageChanged: (String?, String?) -> Unit,
    onLoadStateChanged: (BrowserLoadState) -> Unit,
    onNavigationChanged: (BrowserChromeState) -> Unit,
    onMediaDiscovered: (String, String?) -> Unit,
    onDownloadRequested: (BrowserDownloadBridgeDraft) -> Unit,
    onPermissionRequested: (BrowserPermissionPrompt) -> Unit,
    onPermissionCanceled: (String) -> Unit,
) {
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var lastLoaded by remember { mutableStateOf<String?>(null) }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                browserNavigator.attach(this)
                applyBrowserSettings(context, cookieProfile, browserSettings)
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

                    override fun onPermissionRequest(request: PermissionRequest?) {
                        val permissionRequest = request ?: return
                        val resources = permissionRequest.resources?.toList().orEmpty()
                        mainHandler.post {
                            onPermissionRequested(
                                BrowserPermissionPrompt(
                                    origin = permissionRequest.origin?.toString().orEmpty().ifBlank { "Unknown site" },
                                    resources = resources,
                                    onGrant = { permissionRequest.grant(resources.toTypedArray()) },
                                    onDeny = { permissionRequest.deny() },
                                ),
                            )
                        }
                    }

                    override fun onPermissionRequestCanceled(request: PermissionRequest?) {
                        mainHandler.post { onPermissionCanceled(request?.origin?.toString().orEmpty().ifBlank { "Unknown site" }) }
                    }

                    override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
                        val safeOrigin = origin.orEmpty().ifBlank { "Unknown site" }
                        mainHandler.post {
                            onPermissionRequested(
                                BrowserPermissionPrompt(
                                    origin = safeOrigin,
                                    resources = listOf(BrowserPermissionResourceLocation),
                                    onGrant = { callback?.invoke(origin, true, false) },
                                    onDeny = { callback?.invoke(origin, false, false) },
                                ),
                            )
                        }
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
            webView.applyBrowserSettings(webView.context, cookieProfile, browserSettings)
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
private fun WebView.applyBrowserSettings(context: Context, profile: BrowserCookieProfile, browserSettings: BrowserPrivacySettings) {
    // JavaScript is user-controlled. It defaults on so modern video pages can reveal media manifests,
    // but the browser still exposes no JavaScript interface to page content and keeps downloads review-first.
    val desktopMode = profile.desktopMode || browserSettings.desktopModeDefault
    val acceptCookies = profile.acceptCookies && browserSettings.cookiesEnabled
    settings.javaScriptEnabled = browserSettings.javaScriptEnabled
    settings.domStorageEnabled = browserSettings.domStorageEnabled && !profile.privateMode
    settings.cacheMode = if (profile.privateMode) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT
    settings.mediaPlaybackRequiresUserGesture = true
    settings.useWideViewPort = desktopMode
    settings.loadWithOverviewMode = desktopMode
    settings.userAgentString = if (desktopMode) DesktopUserAgent else WebSettings.getDefaultUserAgent(context)
    CookieManager.getInstance().setAcceptCookie(acceptCookies)
    CookieManager.getInstance().setAcceptThirdPartyCookies(this, acceptCookies && browserSettings.thirdPartyCookiesEnabled)
    if (profile.privateMode || !acceptCookies) CookieManager.getInstance().removeSessionCookies(null)
    CookieManager.getInstance().flush()
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
                XdmSupportingText("Review-first bridge keeps browser downloads separate from the downloader queue until you tap Add download.", maxLines = 2)
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
                    else "Review detected media in a compact cockpit, choose the best candidate, or open the full Media inbox for variant diagnostics.",
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


private data class BrowserPermissionPrompt(
    val origin: String,
    val resources: List<String>,
    val onGrant: () -> Unit,
    val onDeny: () -> Unit,
) {
    val summaryLabel: String
        get() = resources.map(::browserPermissionResourceLabel).distinct().joinToString(" + ").ifBlank { "site capability" }
}

private data class BrowserPermissionEvent(
    val origin: String,
    val label: String,
    val decision: String,
    val decidedAtEpochMs: Long,
)

private fun browserPermissionResourceLabel(resource: String): String = when (resource) {
    PermissionRequest.RESOURCE_AUDIO_CAPTURE -> "microphone"
    PermissionRequest.RESOURCE_VIDEO_CAPTURE -> "camera"
    PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID -> "protected media ID"
    BrowserPermissionResourceLocation -> "location"
    else -> resource.substringAfterLast('.').replace('_', ' ').lowercase(Locale.US).ifBlank { "site capability" }
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
    val activeTabIsPrivate: Boolean,
    val privateTabCount: Int,
) {
    val summary: String
        get() = listOf(
            if (activeTabIsPrivate) "Private" else "Normal",
            activeTabTitle.ifBlank { NewTabTitle },
            activeTabUrl.ifBlank { "New tab" },
        ).joinToString(" · ")
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

private fun normalizeBrowserInput(input: String, searchEngine: BrowserSearchEngine): String {
    val trimmed = input.trim()
    if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) return trimmed
    if (trimmed.contains('.') && !trimmed.contains(' ')) return "https://$trimmed"
    return searchEngine.searchUrl(URLEncoder.encode(trimmed, "UTF-8"))
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

    fun clearBrowsingData(context: Context) {
        current?.get()?.apply {
            stopLoading()
            clearHistory()
            clearCache(true)
            loadUrl("about:blank")
        }
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        context.cacheDir.deleteRecursively()
    }

    fun clearPrivateBrowsingData() {
        current?.get()?.apply {
            stopLoading()
            clearHistory()
            clearCache(false)
        }
        CookieManager.getInstance().removeSessionCookies(null)
        CookieManager.getInstance().flush()
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
    val isPrivate: Boolean = false,
) {
    val displayLabel: String
        get() {
            val base = title.takeIf { it.isNotBlank() }?.take(28) ?: hostFromUrl(url).take(28)
            return if (isPrivate) "Private · $base" else base
        }

    companion object {
        fun blank(isPrivate: Boolean = false): BrowserTab = BrowserTab(
            id = "tab-${System.currentTimeMillis()}",
            url = "",
            title = NewTabTitle,
            updatedAtEpochMs = System.currentTimeMillis(),
            isPrivate = isPrivate,
        )
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

private data class BrowserPrivacySettings(
    val homePage: BrowserHomePage = BrowserHomePage.StartPage,
    val searchEngine: BrowserSearchEngine = BrowserSearchEngine.DuckDuckGo,
    val javaScriptEnabled: Boolean = true,
    val domStorageEnabled: Boolean = true,
    val desktopModeDefault: Boolean = false,
    val cookiesEnabled: Boolean = true,
    val thirdPartyCookiesEnabled: Boolean = false,
)

private enum class BrowserHomePage(val label: String, val url: String?) {
    StartPage("Start page", null),
    DuckDuckGo("DuckDuckGo", "https://duckduckgo.com"),
    Example("Test page", "https://example.com"),
}

private enum class BrowserSearchEngine(val label: String, val startPageUrl: String, private val queryBase: String) {
    DuckDuckGo("DuckDuckGo", "https://duckduckgo.com", "https://duckduckgo.com/?q="),
    Brave("Brave", "https://search.brave.com", "https://search.brave.com/search?q="),
    Google("Google", "https://www.google.com", "https://www.google.com/search?q=");

    fun searchUrl(encodedQuery: String): String = queryBase + encodedQuery
}

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

    fun loadPrivacySettings(): BrowserPrivacySettings = BrowserPrivacySettings(
        homePage = prefs.getString(KeyHomePage, BrowserHomePage.StartPage.name)
            ?.let { value -> BrowserHomePage.entries.firstOrNull { it.name == value } }
            ?: BrowserHomePage.StartPage,
        searchEngine = prefs.getString(KeySearchEngine, BrowserSearchEngine.DuckDuckGo.name)
            ?.let { value -> BrowserSearchEngine.entries.firstOrNull { it.name == value } }
            ?: BrowserSearchEngine.DuckDuckGo,
        javaScriptEnabled = prefs.getBoolean(KeyJavaScriptEnabled, true),
        domStorageEnabled = prefs.getBoolean(KeyDomStorageEnabled, true),
        desktopModeDefault = prefs.getBoolean(KeyDesktopModeDefault, false),
        cookiesEnabled = prefs.getBoolean(KeyCookiesEnabled, true),
        thirdPartyCookiesEnabled = prefs.getBoolean(KeyThirdPartyCookiesEnabled, false),
    )

    fun savePrivacySettings(settings: BrowserPrivacySettings) {
        prefs.edit()
            .putString(KeyHomePage, settings.homePage.name)
            .putString(KeySearchEngine, settings.searchEngine.name)
            .putBoolean(KeyJavaScriptEnabled, settings.javaScriptEnabled)
            .putBoolean(KeyDomStorageEnabled, settings.domStorageEnabled)
            .putBoolean(KeyDesktopModeDefault, settings.desktopModeDefault)
            .putBoolean(KeyCookiesEnabled, settings.cookiesEnabled)
            .putBoolean(KeyThirdPartyCookiesEnabled, settings.thirdPartyCookiesEnabled)
            .apply()
    }

    fun clearBrowsingData() {
        prefs.edit()
            .remove(KeyTabs)
            .remove(KeyHistory)
            .remove(KeyActiveTab)
            .apply()
    }

    fun loadTabs(): List<BrowserTab> = prefs.getString(KeyTabs, null)
        ?.lineSequence()
        ?.mapNotNull(::decodeTab)
        ?.take(MaxStoredTabs)
        ?.toList()
        .orEmpty()

    fun saveTabs(tabs: List<BrowserTab>, activeTabId: String) {
        val persistentTabs = tabs.filterNot { it.isPrivate }.take(MaxStoredTabs)
        prefs.edit()
            .putString(KeyTabs, persistentTabs.joinToString("\n", transform = ::encodeTab))
            .putString(KeyActiveTab, activeTabId.takeIf { id -> persistentTabs.any { it.id == id } } ?: persistentTabs.firstOrNull()?.id)
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

    private fun encodeTab(tab: BrowserTab): String = listOf(tab.id, tab.url, tab.title, tab.updatedAtEpochMs.toString(), tab.isPrivate.toString()).joinToString("	") { encode(it) }

    private fun decodeTab(line: String): BrowserTab? {
        val parts = line.split('	')
        if (parts.size < 4) return null
        return BrowserTab(
            id = decode(parts[0]).takeIf(String::isNotBlank) ?: return null,
            url = decode(parts[1]),
            title = decode(parts[2]).ifBlank { NewTabTitle },
            updatedAtEpochMs = decode(parts[3]).toLongOrNull() ?: 0L,
            isPrivate = parts.getOrNull(4)?.let { decode(it).toBooleanStrictOrNull() } ?: false,
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
        private const val KeyHomePage = "home_page"
        private const val KeySearchEngine = "search_engine"
        private const val KeyJavaScriptEnabled = "javascript_enabled"
        private const val KeyDomStorageEnabled = "dom_storage_enabled"
        private const val KeyDesktopModeDefault = "desktop_mode_default"
        private const val KeyCookiesEnabled = "cookies_enabled"
        private const val KeyThirdPartyCookiesEnabled = "third_party_cookies_enabled"
    }
}

private enum class BrowserResourceFilter(val label: String) {
    All("All"),
    Media("Media"),
    Direct("Direct"),
    Observed("Observed"),
    Unknown("Unknown");

    fun matches(resource: BrowserPageResourceEntry): Boolean = when (this) {
        All -> true
        Media -> resource.kind in setOf("HLS", "DASH", "Progressive", "Audio", "Video")
        Direct -> resource.kind == "Direct file"
        Observed -> resource.kind == "Observed request"
        Unknown -> resource.kind == "Unknown"
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

private const val BrowserMaxContentWidthDp = 1180
private const val NewTabTitle = "New tab"
private const val MaxVisibleTabs = 8
private const val MaxVisibleHistory = 6
private const val MaxVisibleBookmarks = 8
private const val MaxVisiblePageResources = 8
private const val MaxVisibleInspectorResources = 12
private const val MaxVisibleImportLinks = 8
private const val MaxVisibleMediaGroups = 4
private const val MaxVisiblePermissionEvents = 4
private const val BrowserPermissionResourceLocation = "android.webkit.resource.LOCATION"
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
