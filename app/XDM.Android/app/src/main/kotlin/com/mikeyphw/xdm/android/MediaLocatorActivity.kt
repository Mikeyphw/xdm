package com.mikeyphw.xdm.android

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.util.TypedValue
import android.view.View
import android.os.Bundle
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.ProgressBar
import android.widget.FrameLayout
import android.widget.Toast
import android.text.TextUtils
import androidx.activity.ComponentActivity
import androidx.core.os.BundleCompat
import androidx.lifecycle.lifecycleScope
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.mikeyphw.xdm.android.media.MediaCaptureService
import com.mikeyphw.xdm.android.media.LogicalMediaGraphEngine
import com.mikeyphw.xdm.android.media.MediaObservation
import com.mikeyphw.xdm.android.media.MediaSniffingEngine
import com.mikeyphw.xdm.android.media.MediaSniffingInput
import com.mikeyphw.xdm.android.media.MediaSniffingSource
import com.mikeyphw.xdm.android.model.BrowserHandoffMediaPolicy
import com.mikeyphw.xdm.android.model.DebugArea
import com.mikeyphw.xdm.android.model.DebugRecorderProvider
import com.mikeyphw.xdm.android.model.DebugRedactor
import com.mikeyphw.xdm.android.model.DebugSeverity
import com.mikeyphw.xdm.android.model.ExternalUrlPolicy
import com.mikeyphw.xdm.android.model.NoOpDebugEventRecorder
import com.mikeyphw.xdm.android.model.MediaCaptureRecord
import com.mikeyphw.xdm.android.model.MediaThumbnailProvenance
import com.mikeyphw.xdm.android.model.MediaSourceKind
import com.mikeyphw.xdm.android.model.MediaVariant
import java.net.URI
import java.util.Locale
import java.util.Collections
import com.mikeyphw.xdm.android.scheduler.MediaRequestHandoffStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private data class MediaLocatorRequestContext(
    val sourceUrl: String,
    val pageUrl: String?,
    val headers: Map<String, String>,
    val variantUrls: Map<String, String>,
    val durationMs: Long?,
    val thumbnailUrl: String?,
    val thumbnailProvenance: MediaThumbnailProvenance,
)

private object MediaLocatorRequestContextCache {
    private const val MaxEntries = 128
    private const val TtlMs = 30L * 60L * 1000L

    private data class Entry(val context: MediaLocatorRequestContext, val savedAtEpochMs: Long)
    private val entries = object : LinkedHashMap<String, Entry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean = size > MaxEntries
    }

    @Synchronized
    fun put(key: String, context: MediaLocatorRequestContext, now: Long = System.currentTimeMillis()) {
        if (key.isBlank() || context.sourceUrl.isBlank()) return
        prune(now)
        entries[key] = Entry(
            context.copy(
                headers = context.headers.toMap(),
                variantUrls = context.variantUrls.toMap(),
            ),
            now,
        )
    }

    @Synchronized
    fun get(key: String?, now: Long = System.currentTimeMillis()): MediaLocatorRequestContext? {
        if (key.isNullOrBlank()) return null
        prune(now)
        return entries[key]?.context?.let {
            it.copy(headers = it.headers.toMap(), variantUrls = it.variantUrls.toMap())
        }
    }

    private fun prune(now: Long) {
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            if (now - iterator.next().value.savedAtEpochMs > TtlMs) iterator.remove()
        }
    }
}

/**
 * Interactive media locator for pages that need JavaScript/runtime observation.
 *
 * This intentionally mirrors the useful parts of 1DM's locator model: actual DOM media,
 * fetch/XHR response metadata, manifest signatures, and a small amount of resource evidence are
 * collected as *observations*. Every observation is then passed through MediaSniffingEngine, the
 * same evidence gate used by browser-extension and app intake. Nothing is downloaded directly.
 */
class MediaLocatorActivity : ComponentActivity() {
    private val debugRecorder by lazy {
        (applicationContext as? DebugRecorderProvider)?.debugEventRecorder ?: NoOpDebugEventRecorder
    }
    private val problemReporter by lazy {
        (applicationContext as? ProblemReporterProvider)?.problemReporter
    }
    private var pageOperationId: String? = null

    private data class LocatedMedia(
        val logicalMediaId: String,
        val url: String,
        val mimeType: String?,
        val kind: MediaSourceKind,
        val reason: String,
        val pageUrl: String?,
        val pageTitle: String?,
        val requestHeaders: Map<String, String>,
        val rank: Int,
        /** Already-classified durable record and inline-resolved variants from this exact observation. */
        val record: MediaCaptureRecord,
        val variants: List<MediaVariant>,
    )

    private data class NativeRequestEvidence(
        val url: String,
        val headers: Map<String, String>,
        val observedAtEpochMs: Long,
    )

    private val engine = MediaSniffingEngine()
    private val captureService = MediaCaptureService()
    private val logicalGraph = LogicalMediaGraphEngine(sniffingEngine = engine, captureService = captureService)
    private val located = linkedMapOf<String, LocatedMedia>()
    private val requestLedgerLock = Any()
    private val requestLedger = object : LinkedHashMap<String, NativeRequestEvidence>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, NativeRequestEvidence>?): Boolean = size > MAX_NATIVE_REQUESTS
    }
    private val pendingObservationKeys = Collections.synchronizedSet(mutableSetOf<String>())
    private val recentlyObserved = object : LinkedHashMap<String, Long>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > MAX_RECENT_OBSERVATIONS
    }
    @Volatile private var currentPageUrl: String? = null
    private var currentPageTitle: String? = null
    private var locatorUserAgent: String? = null
    private lateinit var webView: WebView
    private lateinit var address: EditText
    private lateinit var status: TextView
    private lateinit var pageSummary: TextView
    private lateinit var resultsHeader: TextView
    private lateinit var progress: ProgressBar
    private lateinit var mediaFab: Button
    private lateinit var scriptButton: Button
    private var activeMediaDialog: AlertDialog? = null
    private lateinit var list: ListView
    private lateinit var adapter: CandidateAdapter
    private lateinit var faviconView: ImageView
    private lateinit var findBar: LinearLayout
    private lateinit var findText: EditText
    private var currentFavicon: Bitmap? = null
    private lateinit var pageBackButton: Button
    private lateinit var pageForwardButton: Button
    private lateinit var reloadButton: Button
    private lateinit var stopButton: Button
    private lateinit var retryButton: Button
    private lateinit var errorPanel: LinearLayout
    private lateinit var errorText: TextView
    private var webViewDisposed = false
    private var pageLoading = false
    private var resultsExpanded = true
    private var lastMainFrameError: String? = null

    // AndroidX WebKit 1.17.0 exposes COOKIE_INTERCEPT as a public feature constant, but
    // accidentally omits it from WebViewFeature.WebViewSupportFeature's @StringDef. Keep the
    // runtime feature gate and suppress only that upstream WrongConstant false positive.
    @SuppressLint("WrongConstant")
    private fun enableCookieAwareRequestInterception(webView: WebView) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.COOKIE_INTERCEPT)) {
            WebSettingsCompat.setCookiesIncludedInShouldInterceptRequest(webView.settings, true)
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "MissingOnRenderProcessGone")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val background = resolveThemeColor(android.R.attr.colorBackground, Color.rgb(18, 18, 18))
        val primaryText = resolveThemeColor(android.R.attr.textColorPrimary, Color.WHITE)
        val secondaryText = resolveThemeColor(android.R.attr.textColorSecondary, Color.LTGRAY)

        address = EditText(this).apply {
            hint = getString(R.string.media_locator_url_hint)
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_GO
            setText(savedInstanceState?.getString(STATE_URL) ?: intent.getStringExtra(EXTRA_URL).orEmpty())
        }
        val close = Button(this).apply {
            text = "←"
            contentDescription = getString(R.string.media_locator_close)
        }
        val title = TextView(this).apply {
            text = getString(R.string.media_locator_title)
            setTextColor(primaryText)
            textSize = 20f
            setPadding(dp(8), dp(12), dp(8), dp(12))
        }
        val go = Button(this).apply { text = getString(R.string.media_locator_go) }
        pageBackButton = Button(this).apply { text = getString(R.string.media_locator_back) }
        pageForwardButton = Button(this).apply { text = getString(R.string.media_locator_forward) }
        reloadButton = Button(this).apply { text = getString(R.string.media_locator_reload) }
        stopButton = Button(this).apply { text = getString(R.string.media_locator_stop) }
        val rescan = Button(this).apply { text = getString(R.string.media_locator_scan) }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }
        status = TextView(this).apply {
            text = getString(R.string.media_locator_initial_status)
            setTextColor(secondaryText)
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        pageSummary = TextView(this).apply {
            text = getString(R.string.media_locator_page_summary_empty)
            setTextColor(secondaryText)
            textSize = 13f
            setPadding(dp(16), dp(4), dp(16), dp(8))
        }
        errorText = TextView(this).apply {
            setTextColor(primaryText)
            setPadding(dp(12), dp(10), dp(12), dp(4))
        }
        retryButton = Button(this).apply { text = getString(R.string.media_locator_retry) }
        errorPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(8), dp(4), dp(8), dp(8))
            addView(errorText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(retryButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        resultsHeader = TextView(this).apply {
            text = getString(R.string.media_locator_no_candidates)
            setTextColor(primaryText)
            textSize = 16f
            setPadding(dp(16), dp(10), dp(16), dp(6))
        }
        webView = WebView(this)
        list = ListView(this)
        adapter = CandidateAdapter()
        list.adapter = adapter
        faviconView = ImageView(this).apply {
            contentDescription = getString(R.string.media_locator_favicon)
            visibility = View.GONE
            scaleType = ImageView.ScaleType.CENTER_CROP
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(close, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(faviconView, LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginStart = dp(6) })
            addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val addressRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), 0, dp(8), 0)
            addView(address, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(go, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), 0, dp(8), 0)
            addView(pageBackButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(pageForwardButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(reloadButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val findButton = Button(this).apply { text = getString(R.string.media_locator_find) }
        val shareButton = Button(this).apply { text = getString(R.string.media_locator_share) }
        val externalButton = Button(this).apply { text = getString(R.string.media_locator_open_external) }
        scriptButton = Button(this).apply { text = getString(R.string.media_locator_userscripts) }
        val browserTools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), 0, dp(8), 0)
            addView(findButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(shareButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(externalButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(scriptButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        findText = EditText(this).apply {
            hint = getString(R.string.media_locator_find_hint)
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
        }
        val findNext = Button(this).apply { text = getString(R.string.media_locator_find_next) }
        val findClose = Button(this).apply { text = getString(R.string.media_locator_find_close) }
        findBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            setPadding(dp(8), 0, dp(8), 0)
            addView(findText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(findNext, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(findClose, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val scanControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), 0, dp(8), 0)
            addView(stopButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(rescan, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f))
        }
        mediaFab = Button(this).apply {
            text = getString(R.string.media_locator_media_fab_empty)
            contentDescription = getString(R.string.media_locator_media_fab_empty)
            isEnabled = false
            visibility = View.VISIBLE
            elevation = dp(8).toFloat()
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(background)
            addView(topBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(addressRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(controls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(browserTools, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(findBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(scanControls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))
            addView(pageSummary, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(errorPanel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            // Parity04: the detected-media list is no longer a fixed viewport overlay.
            // It is opened from the floating Media button so the WebView behaves like a light browser.
        }
        val frameRoot = FrameLayout(this).apply {
            addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(
                mediaFab,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
                    setMargins(dp(16), dp(16), dp(16), dp(16))
                },
            )
        }
        ViewCompat.setOnApplyWindowInsetsListener(frameRoot) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(frameRoot)
        ViewCompat.requestApplyInsets(frameRoot)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            loadsImagesAutomatically = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        locatorUserAgent = webView.settings.userAgentString
        enableCookieAwareRequestInterception(webView)
        webView.addJavascriptInterface(MediaObservationBridge(), JS_BRIDGE)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, LOCATOR_RUNTIME, setOf("*"))
        }
        installEnabledUserscriptsAtDocumentStart()
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
                pageLoading = newProgress in 1..99
                progress.visibility = if (pageLoading) View.VISIBLE else View.GONE
                updateNavigationState()
                updatePageSummary()
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                currentPageTitle = title?.trim()?.takeIf(String::isNotBlank)
                updatePageSummary()
            }

            override fun onReceivedIcon(view: WebView, icon: Bitmap?) {
                updateFavicon(icon)
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                return if (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) {
                    address.setText(uri.toString())
                    false
                } else {
                    true
                }
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                currentPageUrl = url
                currentPageTitle = null
                updateFavicon(favicon)
                lastMainFrameError = null
                hideMainFrameError()
                pageLoading = true
                pageOperationId = "webview-${DebugRedactor.fingerprint(url + "|" + System.currentTimeMillis())}"
                debugRecorder.record(
                    area = DebugArea.WebView,
                    action = "page-load",
                    result = "started",
                    safeDetails = mapOf("url" to url),
                    operationId = pageOperationId,
                )
                address.setText(url)
                progress.visibility = View.VISIBLE
                status.text = getString(R.string.media_locator_loading)
                updateNavigationState()
                updatePageSummary()
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) injectLocatorRuntime()
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                recordNativeRequest(request)
                return null
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                super.onReceivedError(view, request, error)
                if (!request.isForMainFrame) return
                val description = error.description?.toString()?.trim().orEmpty()
                showMainFrameError(
                    kind = "network",
                    title = getString(R.string.media_locator_error_network_title),
                    detail = description.ifBlank { getString(R.string.media_locator_error_network_detail) },
                    severity = DebugSeverity.Warning,
                    notifyUser = true,
                    diagnosticCode = error.errorCode.toString(),
                )
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                super.onReceivedHttpError(view, request, errorResponse)
                if (!request.isForMainFrame || errorResponse.statusCode < 400) return
                showMainFrameError(
                    kind = "http-${errorResponse.statusCode}",
                    title = getString(R.string.media_locator_error_http_title, errorResponse.statusCode),
                    detail = getString(R.string.media_locator_error_http_detail),
                    severity = DebugSeverity.Warning,
                    notifyUser = true,
                    diagnosticCode = errorResponse.statusCode.toString(),
                )
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel()
                showMainFrameError(
                    kind = "ssl",
                    title = getString(R.string.media_locator_error_ssl_title),
                    detail = getString(R.string.media_locator_error_ssl_detail),
                    severity = DebugSeverity.Error,
                    notifyUser = true,
                    diagnosticCode = error.primaryError.toString(),
                )
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                val lastUrl = currentPageUrl ?: address.text.toString()
                debugRecorder.record(
                    area = DebugArea.WebView,
                    severity = DebugSeverity.Error,
                    action = "renderer-process",
                    result = if (detail.didCrash()) "crashed" else "terminated",
                    safeDetails = mapOf("url" to lastUrl, "didCrash" to detail.didCrash().toString()),
                    operationId = pageOperationId,
                )
                problemReporter?.report(
                    area = DebugArea.WebView,
                    severity = DebugSeverity.Error,
                    title = "Media browser renderer stopped",
                    summary = if (detail.didCrash()) "The embedded media browser renderer crashed while inspecting this page." else "Android stopped the embedded media browser renderer while inspecting this page.",
                    suggestedAction = "Reopen the page and retry. If it repeats, open Diagnostics & support and export the debug report.",
                    operationId = pageOperationId,
                    dedupeKey = "media-locator-renderer-${detail.didCrash()}",
                    notifyUser = true,
                )
                (view.parent as? ViewGroup)?.removeView(view)
                view.stopLoading()
                view.destroy()
                webViewDisposed = true
                pageLoading = false
                address.setText(lastUrl)
                progress.visibility = View.GONE
                lastMainFrameError = getString(R.string.media_locator_renderer_stopped)
                errorText.text = lastMainFrameError
                errorPanel.visibility = View.VISIBLE
                status.text = getString(R.string.media_locator_renderer_stopped)
                updateNavigationState()
                updatePageSummary()
                if (detail.didCrash()) recentlyObserved.clear()
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                currentPageUrl = url
                currentPageTitle = view.title?.trim()?.takeIf(String::isNotBlank) ?: currentPageTitle
                pageLoading = false
                debugRecorder.record(
                    area = DebugArea.WebView,
                    action = "page-load",
                    result = "finished",
                    safeDetails = mapOf("url" to url, "candidateCount" to located.size.toString()),
                    operationId = pageOperationId,
                )
                address.setText(url)
                progress.visibility = View.GONE
                if (lastMainFrameError == null) {
                    injectLocatorRuntime(forceScan = true)
                    updateLocatorStatus()
                }
                updateNavigationState()
                updatePageSummary()
                injectEnabledUserscriptsForCurrentPage()
            }
        }

        close.setOnClickListener { finish() }
        go.setOnClickListener { loadAddress() }
        address.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                loadAddress()
                true
            } else false
        }
        pageBackButton.setOnClickListener { if (!webViewDisposed && webView.canGoBack()) webView.goBack() }
        pageForwardButton.setOnClickListener { if (!webViewDisposed && webView.canGoForward()) webView.goForward() }
        reloadButton.setOnClickListener {
            if (webViewDisposed) loadAddress() else webView.reload()
        }
        stopButton.setOnClickListener {
            if (!webViewDisposed) webView.stopLoading()
            pageLoading = false
            progress.visibility = View.GONE
            status.text = getString(R.string.media_locator_loading_stopped)
            updateNavigationState()
            updatePageSummary()
        }
        retryButton.setOnClickListener {
            lastMainFrameError = null
            hideMainFrameError()
            if (webViewDisposed) loadAddress() else webView.reload()
        }
        rescan.setOnClickListener {
            if (webViewDisposed) {
                loadAddress()
            } else {
                injectLocatorRuntime(forceScan = true)
                status.text = getString(R.string.media_locator_rescanning)
            }
        }
        findButton.setOnClickListener {
            findBar.visibility = if (findBar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            if (findBar.visibility == View.VISIBLE) findText.requestFocus() else webView.clearMatches()
        }
        val runFind = {
            val query = findText.text.toString().trim()
            if (query.isNotBlank() && !webViewDisposed) webView.findAllAsync(query)
        }
        findText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { runFind(); true } else false
        }
        findNext.setOnClickListener {
            val query = findText.text.toString().trim()
            if (query.isNotBlank() && !webViewDisposed) { webView.findAllAsync(query); webView.findNext(true) }
        }
        findClose.setOnClickListener {
            if (!webViewDisposed) webView.clearMatches()
            findBar.visibility = View.GONE
        }
        shareButton.setOnClickListener { shareCurrentPage() }
        externalButton.setOnClickListener { openCurrentPageExternally() }

        resultsHeader.setOnClickListener { showMediaBottomSheet() }
        mediaFab.setOnClickListener { showMediaBottomSheet() }
        scriptButton.setOnClickListener { showUserscriptManager() }
        list.setOnItemClickListener { _, _, position, _ ->
            located.values.sortedWith(compareByDescending<LocatedMedia> { it.rank }.thenBy { it.url })
                .getOrNull(position)
                ?.let(::reviewCandidate)
        }

        if (savedInstanceState != null) restoreLocatorState(savedInstanceState)
        val webStateRestored = savedInstanceState?.getBundle(STATE_WEBVIEW)?.let { state ->
            runCatching { webView.restoreState(state) != null }.getOrDefault(false)
        } == true
        if (webStateRestored) {
            val restoredState = requireNotNull(savedInstanceState)
            currentPageUrl = webView.url ?: restoredState.getString(STATE_URL)
            currentPageTitle = restoredState.getString(STATE_TITLE)?.takeIf(String::isNotBlank) ?: webView.title
            BundleCompat.getParcelable(restoredState, STATE_FAVICON, Bitmap::class.java)?.let(::updateFavicon)
            val savedX = restoredState.getInt(STATE_SCROLL_X, 0)
            val savedY = restoredState.getInt(STATE_SCROLL_Y, 0)
            webView.post { if (!webViewDisposed) webView.scrollTo(savedX, savedY) }
            currentPageUrl?.let(address::setText)
        }
        updateCandidateHeader()
        updateNavigationState()
        updatePageSummary()
        val initial = normalizePageUrl(address.text.toString())
        if (!webStateRestored && initial != null) {
            status.text = getString(R.string.media_locator_loading)
            webView.loadUrl(initial)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_URL, currentPageUrl ?: address.text.toString())
        outState.putString(STATE_TITLE, currentPageTitle)
        outState.putInt(STATE_SCROLL_X, if (webViewDisposed) 0 else webView.scrollX)
        outState.putInt(STATE_SCROLL_Y, if (webViewDisposed) 0 else webView.scrollY)
        currentFavicon?.let { favicon -> outState.putParcelable(STATE_FAVICON, favicon.scaleDown(48)) }
        if (!webViewDisposed) Bundle().also { webState ->
            runCatching { webView.saveState(webState) }
            outState.putBundle(STATE_WEBVIEW, webState)
        }
        outState.putStringArrayList(STATE_CANDIDATES, ArrayList(located.values.take(MAX_SAVED_CANDIDATES).map(::encodeSavedCandidate)))
    }

    override fun onDestroy() {
        if (!webViewDisposed) {
            webView.removeJavascriptInterface(JS_BRIDGE)
            webView.stopLoading()
            webView.destroy()
            webViewDisposed = true
        }
        super.onDestroy()
    }

    private fun loadAddress() {
        val normalized = normalizePageUrl(address.text.toString())
        if (normalized == null) {
            showFeedbackToast(getString(R.string.media_locator_invalid_url)); status.text = getString(R.string.media_locator_invalid_url)
            return
        }
        located.clear()
        logicalGraph.clear()
        resultsExpanded = true
        lastMainFrameError = null
        hideMainFrameError()
        refreshList()
        status.text = getString(R.string.media_locator_loading)
        if (webViewDisposed) {
            intent.putExtra(EXTRA_URL, normalized)
            recreate()
            return
        }
        webView.loadUrl(normalized)
    }

    private fun updateNavigationState() {
        val active = !webViewDisposed
        pageBackButton.isEnabled = active && webView.canGoBack()
        pageForwardButton.isEnabled = active && webView.canGoForward()
        reloadButton.isEnabled = true
        stopButton.isEnabled = active && pageLoading
        retryButton.isEnabled = true
    }

    private fun updatePageSummary() {
        val page = currentPageUrl ?: normalizePageUrl(address.text.toString())
        if (page == null) {
            pageSummary.text = getString(R.string.media_locator_page_summary_empty)
            return
        }
        val uri = runCatching { URI(page) }.getOrNull()
        val host = uri?.host.orEmpty().ifBlank { getString(R.string.media_locator_unknown_host) }
        val transport = when (uri?.scheme?.lowercase(Locale.US)) {
            "https" -> getString(R.string.media_locator_https)
            "http" -> getString(R.string.media_locator_http)
            else -> getString(R.string.media_locator_unknown_transport)
        }
        val loading = if (pageLoading) getString(R.string.media_locator_page_loading_short) else getString(R.string.media_locator_page_ready_short)
        val title = currentPageTitle?.takeIf(String::isNotBlank) ?: host
        pageSummary.text = getString(R.string.media_locator_page_summary, title.take(120), host, transport, loading)
    }

    private fun hideMainFrameError() {
        errorText.text = ""
        errorPanel.visibility = View.GONE
    }

    private fun showMainFrameError(
        kind: String,
        title: String,
        detail: String,
        severity: DebugSeverity,
        notifyUser: Boolean,
        diagnosticCode: String,
    ) {
        val url = currentPageUrl ?: address.text.toString()
        pageLoading = false
        progress.visibility = View.GONE
        lastMainFrameError = "$title\n$detail"
        errorText.text = lastMainFrameError
        errorPanel.visibility = View.VISIBLE
        status.text = getString(R.string.media_locator_error_status)
        showFeedbackToast("$title. $detail")
        debugRecorder.record(
            area = DebugArea.WebView,
            severity = severity,
            action = "main-frame-load",
            result = kind,
            safeDetails = mapOf("url" to url, "code" to diagnosticCode, "detail" to detail.take(240)),
            operationId = pageOperationId,
        )
        problemReporter?.report(
            area = DebugArea.WebView,
            severity = severity,
            title = title,
            summary = detail,
            suggestedAction = getString(R.string.media_locator_error_action),
            operationId = pageOperationId,
            dedupeKey = "media-locator-$kind",
            notifyUser = notifyUser,
        )
        updateNavigationState()
        updatePageSummary()
    }

    private fun injectLocatorRuntime(forceScan: Boolean = false) {
        if (webViewDisposed) return
        val script = LOCATOR_RUNTIME + if (forceScan) ";window.__xdmLocatorScan && window.__xdmLocatorScan();" else ""
        webView.evaluateJavascript(script, null)
    }

    private inner class MediaObservationBridge {
        @JavascriptInterface
        fun observe(rawJson: String) {
            val observation = runCatching { JSONObject(rawJson) }.getOrNull() ?: return
            val url = observation.optString("url").trim().takeIf(String::isNotBlank) ?: return
            val mime = observation.optString("mime").trim().takeIf(String::isNotBlank)
            val body = observation.optString("body").takeIf(String::isNotBlank)
            val title = observation.optString("title").trim().takeIf(String::isNotBlank)
            val source = observation.optString("source").ifBlank { "runtime" }
            val jsHeaders = jsonHeaders(observation.optJSONObject("requestHeaders"))
            val contentDisposition = observation.optString("contentDisposition").trim().takeIf(String::isNotBlank)
            val contentLength = observation.optLong("contentLength", -1L).takeIf { it >= 0L }
            val durationMs = observation.optLong("durationMs", 0L).takeIf { it > 0L }
            val thumbnailUrl = normalizePageUrl(observation.optString("thumbnailUrl"))
            val thumbnailProvenance = runCatching { MediaThumbnailProvenance.valueOf(observation.optString("thumbnailProvenance")) }
                .getOrDefault(MediaThumbnailProvenance.Unknown)
                .takeIf { thumbnailUrl != null } ?: MediaThumbnailProvenance.Unknown
            val key = correlationKey(url) ?: return
            val observationKey = "$key|${if (body != null) "body" else source}"
            debugRecorder.record(
                area = DebugArea.WebView,
                severity = DebugSeverity.Trace,
                action = "media-observation",
                result = "received",
                safeDetails = mapOf("url" to url, "mime" to mime.orEmpty(), "source" to source),
                operationId = pageOperationId,
            )
            if (!reserveObservation(observationKey)) return

            lifecycleScope.launch {
                try {
                    val authoritativePage = currentPageUrl
                    val correlated = nativeEvidenceFor(url)
                    val exactHeaders = correlated?.let { correlatedRequestHeaders(url, it) }.orEmpty()
                    val inheritedHeaders = safeInheritedSessionHeaders(authoritativePage)
                    // Browser/request credentials are attached only when WebView natively observed the
                    // exact URL. JS-only candidates may still be reviewed, but cannot manufacture a
                    // credential-enriched request by calling the bridge directly.
                    val observedHeaders = if (correlated != null) exactHeaders + jsHeaders else emptyMap()
                    val primaryHeaders = inheritedHeaders + observedHeaders
                    val graphSnapshot = withContext(Dispatchers.Default) {
                        logicalGraph.observe(
                            MediaObservation(
                                url = url,
                                mimeType = mime,
                                contentLength = contentLength,
                                durationMs = durationMs,
                                thumbnailUrl = thumbnailUrl,
                                thumbnailProvenance = thumbnailProvenance,
                                bodyPrefix = body,
                                pageUrl = authoritativePage,
                                pageTitle = title ?: currentPageTitle,
                                requestHeaders = primaryHeaders,
                                source = if (source == "dom") MediaSniffingSource.AppPageProbe else MediaSniffingSource.NetworkObservation,
                                initiator = source,
                            ),
                        )
                    }
                    val repository = (application as XdmApplication).container.repository
                    graphSnapshot.evidence.lastOrNull()?.let { repository.saveMediaObservationEvidence(listOf(it)) }
                    val found = graphSnapshot.items.map { item ->
                        val nativeForCanonical = nativeEvidenceFor(item.requestUrl)
                        val exactHeaders = if (nativeForCanonical != null) {
                            safeInheritedSessionHeaders(authoritativePage) + correlatedRequestHeaders(item.requestUrl, nativeForCanonical)
                        } else {
                            item.requestHeaders
                        }
                        LocatedMedia(
                            logicalMediaId = item.logicalMediaId,
                            url = item.requestUrl,
                            mimeType = item.record.mimeType,
                            kind = item.record.kind,
                            reason = "logical-media • ${item.observationCount} observations • ${item.segmentCount} segments internal",
                            pageUrl = authoritativePage ?: item.record.pageUrl,
                            pageTitle = item.record.title,
                            requestHeaders = exactHeaders,
                            rank = item.record.logicalConfidence,
                            record = item.record,
                            variants = item.variants,
                        )
                    }
                    located.clear()
                    found.forEach(::putLocatedBounded)
                    refreshList()
                    status.text = resources.getQuantityString(
                        R.plurals.media_locator_candidates_found,
                        located.size,
                        located.size,
                    )
                } finally {
                    pendingObservationKeys.remove(observationKey)
                }
            }
        }
    }

    private fun reserveObservation(key: String): Boolean {
        val now = System.currentTimeMillis()
        synchronized(recentlyObserved) {
            val previous = recentlyObserved[key]
            if (previous != null && now - previous < OBSERVATION_DEDUPE_MS) return false
            if (pendingObservationKeys.size >= MAX_PENDING_OBSERVATIONS) return false
            recentlyObserved[key] = now
        }
        return pendingObservationKeys.add(key)
    }

    private fun putLocatedBounded(candidate: LocatedMedia) {
        val previous = located[candidate.logicalMediaId]
        if (previous == null || candidate.rank >= previous.rank) located[candidate.logicalMediaId] = candidate
        while (located.size > MAX_LOCATED_CANDIDATES) {
            val weakest = located.values.minWithOrNull(compareBy<LocatedMedia> { it.rank }.thenBy { it.url }) ?: break
            located.remove(weakest.logicalMediaId)
        }
    }

    private fun recordNativeRequest(request: WebResourceRequest) {
        val url = request.url.toString()
        val key = correlationKey(url) ?: return
        debugRecorder.record(
            area = DebugArea.WebView,
            severity = DebugSeverity.Trace,
            action = "network-request",
            result = "observed",
            safeDetails = mapOf(
                "url" to url,
                "method" to request.method,
                "mainFrame" to request.isForMainFrame.toString(),
            ),
            operationId = pageOperationId,
        )
        val headers = request.requestHeaders.entries.mapNotNull { (name, value) ->
            val trimmed = value.trim()
            if (name.isBlank() || trimmed.isBlank() || '\n' in trimmed || '\r' in trimmed) null else name to trimmed.take(8192)
        }.toMap()
        synchronized(requestLedgerLock) {
            requestLedger[key] = NativeRequestEvidence(url, headers, System.currentTimeMillis())
            pruneRequestLedgerLocked(System.currentTimeMillis())
        }
    }

    private fun nativeEvidenceFor(url: String): NativeRequestEvidence? {
        val key = correlationKey(url) ?: return null
        val now = System.currentTimeMillis()
        return synchronized(requestLedgerLock) {
            pruneRequestLedgerLocked(now)
            requestLedger[key]?.takeIf { now - it.observedAtEpochMs <= NATIVE_REQUEST_TTL_MS }
        }
    }

    private fun pruneRequestLedgerLocked(now: Long) {
        requestLedger.entries.removeAll { now - it.value.observedAtEpochMs > NATIVE_REQUEST_TTL_MS }
    }

    private fun correlationKey(raw: String): String? = runCatching {
        val uri = URI(raw.trim())
        val scheme = uri.scheme?.lowercase(Locale.US)?.takeIf { it == "http" || it == "https" } ?: return@runCatching null
        val host = uri.host?.lowercase(Locale.US)?.takeIf(String::isNotBlank) ?: return@runCatching null
        URI(scheme, null, host, uri.port, uri.rawPath?.ifBlank { "/" } ?: "/", uri.rawQuery, null).toASCIIString()
    }.getOrNull()

    private fun safeInheritedSessionHeaders(pageUrl: String?): Map<String, String> = buildMap {
        locatorUserAgent?.takeIf(String::isNotBlank)?.let { put("User-Agent", it) }
        pageUrl?.takeIf(String::isNotBlank)?.let { put("Referer", it) }
    }

    private fun refreshList() {
        adapter.replace(
            located.values.sortedWith(compareByDescending<LocatedMedia> { it.rank }.thenBy { it.url }),
        )
        updateCandidateHeader()
    }

    private inner class CandidateAdapter : BaseAdapter() {
        private var items: List<LocatedMedia> = emptyList()

        fun replace(next: List<LocatedMedia>) {
            items = next
            notifyDataSetChanged()
        }

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): LocatedMedia = items[position]
        override fun getItemId(position: Int): Long = getItem(position).record.id.hashCode().toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val item = getItem(position)
            val row = LinearLayout(this@MediaLocatorActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(8))
            }
            val artwork = ImageView(this@MediaLocatorActivity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = getString(R.string.media_locator_candidate_thumbnail, item.record.title)
                setImageResource(android.R.drawable.ic_menu_gallery)
                tag = item.record.id
            }
            val labels = LinearLayout(this@MediaLocatorActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
                val host = runCatching { URI(item.url).host }.getOrNull().orEmpty()
                addView(wrappingTextView(item.record.title.take(160), 15f, 3))
                addView(wrappingTextView(
                    getString(
                        R.string.media_locator_candidate_details,
                        item.kind.name.replace('_', ' '),
                        item.mimeType ?: getString(R.string.media_locator_type_inferred),
                        host,
                        item.reason,
                    ),
                    12f,
                    5,
                ))
            }
            row.addView(artwork, LinearLayout.LayoutParams(dp(96), dp(60)))
            row.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val request = XdmArtworkRequest(
                fileName = item.record.fileName,
                mimeType = item.record.mimeType,
                thumbnailUrl = item.record.thumbnailUrl,
                sourceUrl = item.record.sourceUrl,
                localUri = null,
            )
            XdmArtworkLoader.peek(request.cacheKey)?.let(artwork::setImageBitmap) ?: lifecycleScope.launch(Dispatchers.IO) {
                val bitmap = XdmArtworkLoader.load(applicationContext, request) ?: return@launch
                withContext(Dispatchers.Main) { if (artwork.tag == item.record.id) artwork.setImageBitmap(bitmap) }
            }
            return row
        }
    }


    private fun showMediaBottomSheet() {
        if (located.isEmpty()) {
            showFeedbackToast(getString(R.string.media_locator_no_candidates))
            return
        }
        val candidates = located.values.sortedWith(compareByDescending<LocatedMedia> { it.rank }.thenBy { it.url })
        val labels = candidates.map { candidate ->
            val host = runCatching { URI(candidate.url).host }.getOrNull().orEmpty()
            listOf(
                candidate.record.title.take(90),
                candidate.kind.name.replace('_', ' '),
                candidate.mimeType ?: getString(R.string.media_locator_type_inferred),
                host,
                if (candidate.variants.isNotEmpty()) "${candidate.variants.size} track(s)" else null,
            ).filterNotNull().joinToString(" • ")
        }.toTypedArray()
        activeMediaDialog?.dismiss()
        activeMediaDialog = AlertDialog.Builder(this)
            .setTitle(resources.getQuantityString(R.plurals.media_locator_bottom_sheet_title, candidates.size, candidates.size))
            .setItems(labels) { _, which -> candidates.getOrNull(which)?.let(::reviewCandidate) }
            .setNegativeButton(getString(R.string.media_locator_bottom_sheet_close), null)
            .setNeutralButton(getString(R.string.media_locator_scan)) { _, _ ->
                injectLocatorRuntime(forceScan = true)
                showFeedbackToast(getString(R.string.media_locator_rescanning))
            }
            .show()
    }

    private fun showUserscriptManager() {
        val store = WebViewUserscriptStore(this)
        val current = store.snapshot()
        val input = EditText(this).apply {
            hint = getString(R.string.media_locator_userscript_hint)
            setSingleLine(false)
            minLines = 8
            maxLines = 16
            setText(current.scriptSource)
        }
        val enabled = booleanArrayOf(current.enabled)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.media_locator_userscripts_title))
            .setMultiChoiceItems(arrayOf(getString(R.string.media_locator_userscripts_enable)), enabled, { _, _, checked -> enabled[0] = checked })
            .setView(input)
            .setNegativeButton(getString(R.string.media_locator_userscripts_disable)) { _, _ ->
                store.save(WebViewUserscript(enabled = false, scriptSource = ""))
                showFeedbackToast(getString(R.string.media_locator_userscripts_disabled))
            }
            .setPositiveButton(getString(R.string.media_locator_userscripts_save)) { _, _ ->
                val candidate = WebViewUserscript(enabled = enabled[0], scriptSource = input.text?.toString().orEmpty())
                val validation = WebViewUserscriptPolicy.validate(candidate)
                if (validation.accepted) {
                    store.save(candidate)
                    installEnabledUserscriptsAtDocumentStart()
                    injectEnabledUserscriptsForCurrentPage()
                    showFeedbackToast(validation.message)
                } else {
                    showFeedbackToast(validation.message)
                }
            }
            .show()
    }

    private fun installEnabledUserscriptsAtDocumentStart() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        val script = WebViewUserscriptStore(this).documentStartScriptFor(currentPageUrl)
        if (script.isBlank()) return
        runCatching { WebViewCompat.addDocumentStartJavaScript(webView, script, setOf("*")) }
            .onFailure { showFeedbackToast(getString(R.string.media_locator_userscripts_injection_failed)) }
    }

    private fun injectEnabledUserscriptsForCurrentPage() {
        val script = WebViewUserscriptStore(this).documentStartScriptFor(currentPageUrl)
        if (script.isBlank()) return
        runCatching { webView.evaluateJavascript(script, null) }
            .onFailure { showFeedbackToast(getString(R.string.media_locator_userscripts_injection_failed)) }
    }

    private fun showFeedbackToast(message: String) {
        if (message.isBlank()) return
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun wrappingTextView(textValue: String, sizeSp: Float, maxLineCount: Int): TextView = TextView(this).apply {
        text = textValue
        textSize = sizeSp
        isSingleLine = false
        maxLines = maxLineCount
        ellipsize = TextUtils.TruncateAt.END
        setHorizontallyScrolling(false)
    }

    private fun updateFavicon(icon: Bitmap?) {
        currentFavicon = icon?.scaleDown(48)
        faviconView.setImageBitmap(currentFavicon)
        faviconView.visibility = if (currentFavicon == null) View.GONE else View.VISIBLE
    }

    private fun Bitmap.scaleDown(maxEdge: Int): Bitmap {
        val largest = maxOf(width, height)
        if (largest <= maxEdge || largest <= 0) return this
        val ratio = maxEdge.toFloat() / largest
        return Bitmap.createScaledBitmap(this, (width * ratio).toInt().coerceAtLeast(1), (height * ratio).toInt().coerceAtLeast(1), true)
    }

    private fun shareCurrentPage() {
        val url = currentPageUrl ?: normalizePageUrl(address.text.toString()) ?: return
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
            currentPageTitle?.takeIf(String::isNotBlank)?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
        }
        startActivity(Intent.createChooser(share, getString(R.string.media_locator_share_chooser)))
    }

    private fun openCurrentPageExternally() {
        val url = currentPageUrl ?: normalizePageUrl(address.text.toString()) ?: return
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { showFeedbackToast(getString(R.string.media_locator_no_external_browser)); status.text = getString(R.string.media_locator_no_external_browser) }
    }

    private fun updateCandidateHeader() {
        val text = if (located.isEmpty()) {
            getString(R.string.media_locator_no_candidates)
        } else {
            resources.getQuantityString(R.plurals.media_locator_candidates_header, located.size, located.size)
        }
        resultsHeader.text = text
        resultsHeader.contentDescription = text
        val fabText = if (located.isEmpty()) getString(R.string.media_locator_media_fab_empty)
            else resources.getQuantityString(R.plurals.media_locator_media_fab, located.size, located.size)
        mediaFab.text = fabText
        mediaFab.contentDescription = fabText
        mediaFab.isEnabled = located.isNotEmpty()
        // Keep the list adapter for accessibility/testing but never pin it below the WebView.
        list.visibility = View.GONE
    }

    private fun updateLocatorStatus() {
        status.text = if (located.isEmpty()) {
            getString(R.string.media_locator_waiting_for_evidence)
        } else {
            resources.getQuantityString(R.plurals.media_locator_candidates_found, located.size, located.size)
        }
        updateCandidateHeader()
    }

    private fun resolveThemeColor(attr: Int, fallback: Int): Int {
        val value = TypedValue()
        return if (theme.resolveAttribute(attr, value, true)) {
            if (value.resourceId != 0) getColor(value.resourceId) else value.data
        } else fallback
    }

    private fun reviewCandidate(candidate: LocatedMedia) {
        val detail = buildString {
            append(candidate.kind.name.replace('_', ' '))
            candidate.mimeType?.let { append(" • ").append(it) }
            if (candidate.variants.isNotEmpty()) append(" • ").append(candidate.variants.size).append(" resolved track(s)")
            append("\n").append(candidate.reason)
        }
        AlertDialog.Builder(this)
            .setTitle("Add captured media to XDM")
            .setMessage(detail)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Add to Media") { _, _ -> persistLocatedCandidate(candidate) }
            .show()
    }

    private fun persistLocatedCandidate(candidate: LocatedMedia) {
        showFeedbackToast(getString(R.string.media_locator_saving)); status.text = getString(R.string.media_locator_saving)
        lifecycleScope.launch(Dispatchers.IO) {
            val repository = (application as XdmApplication).container.repository
            val now = System.currentTimeMillis()
            val existing = repository.findMediaCapture(candidate.record.id)
            val durable = candidate.record.copy(
                // A refreshed observation updates request/manifest evidence without severing an
                // already-created output from its logical capture.
                downloadId = existing?.downloadId,
                status = if (existing?.downloadId != null) existing.status else candidate.record.status,
                createdAtEpochMs = existing?.createdAtEpochMs ?: candidate.record.createdAtEpochMs,
                updatedAtEpochMs = now,
            )
            // Persist exactly the variants parsed from the observed response. This avoids a
            // second manifest fetch after review and replaces stale variants even when empty.
            repository.saveMediaCaptureWithVariants(durable, candidate.variants, now)
            MediaRequestHandoffStore.rememberCapture(
                captureId = durable.id,
                headers = candidate.requestHeaders,
                redactedSummary = "live locator • ${candidate.kind.name}",
                isExpiringUrl = ExternalUrlPolicy.hasCredentialBearingQuery(candidate.url),
                exactUrl = candidate.url,
                pageUrl = candidate.pageUrl,
                transferShape = BrowserHandoffMediaPolicy.classifyShape(
                    kind = durable.kind,
                    pageUrl = durable.pageUrl,
                    mimeType = durable.mimeType,
                    live = durable.manifestIsLive == true,
                    protected = durable.manifestProtected,
                ),
            )
            candidate.variants.forEach { variant ->
                MediaRequestHandoffStore.rememberVariant(
                    variantId = variant.id,
                    exactUrl = variant.url,
                    headers = candidate.requestHeaders,
                    redactedSummary = "live locator variant • ${variant.kind.name}",
                    expiresAtEpochMs = variant.expiresAtEpochMs ?: now + 24L * 60L * 60L * 1000L,
                )
            }
            withContext(Dispatchers.Main) {
                startActivity(
                    Intent(this@MediaLocatorActivity, MainActivity::class.java)
                        .setAction(MainActivity.ACTION_INTERNAL_MEDIA_CAPTURE_READY)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                )
                finish()
            }
        }
    }

    private fun correlatedRequestHeaders(url: String, evidence: NativeRequestEvidence): Map<String, String> = buildMap {
        putAll(evidence.headers)
        if (keys.none { it.equals("Cookie", ignoreCase = true) }) {
            CookieManager.getInstance().getCookie(url)?.takeIf(String::isNotBlank)?.let { put("Cookie", it) }
        }
    }

    private fun encodeSavedCandidate(candidate: LocatedMedia): String {
        // Exact request URLs can carry signed query credentials just like Cookie/Authorization.
        // Keep the whole executable request context process-local; Bundle state contains only a
        // non-secret cache key plus semantic/display metadata needed to rebuild the list.
        val requestContextKey = candidate.record.id
        MediaLocatorRequestContextCache.put(
            requestContextKey,
            MediaLocatorRequestContext(
                sourceUrl = candidate.url,
                pageUrl = candidate.pageUrl,
                headers = candidate.requestHeaders,
                variantUrls = candidate.variants.associate { it.id to it.url },
                durationMs = candidate.record.durationMs,
                thumbnailUrl = candidate.record.thumbnailUrl,
                thumbnailProvenance = candidate.record.thumbnailProvenance,
            ),
        )
        return JSONObject().apply {
            put("requestContextKey", requestContextKey)
            put("mime", candidate.mimeType)
            put("kind", candidate.kind.name)
            put("reason", candidate.reason.take(256))
            put("pageTitle", candidate.pageTitle)
            put("rank", candidate.rank)
            put("thumbnailProvenance", candidate.record.thumbnailProvenance.name)
            put("manifestRole", candidate.record.manifestRole.name)
            candidate.record.manifestIsLive?.let { put("manifestIsLive", it) }
            put("manifestProtected", candidate.record.manifestProtected)
            put("manifestProtectionScheme", candidate.record.manifestProtectionScheme)
            put("variants", JSONArray().apply {
                candidate.variants.take(MAX_SAVED_VARIANTS_PER_CANDIDATE).forEach { variant ->
                    put(JSONObject().apply {
                        put("id", variant.id)
                        put("kind", variant.kind.name)
                        put("mime", variant.mimeType)
                        variant.width?.let { put("width", it) }
                        variant.height?.let { put("height", it) }
                        variant.bitrateBitsPerSecond?.let { put("bitrate", it) }
                        put("codecs", variant.codecs)
                        put("language", variant.language)
                        put("position", variant.position)
                        put("displayLabel", variant.displayLabel)
                        variant.expiresAtEpochMs?.let { put("expiresAt", it) }
                        put("groupId", variant.groupId)
                        put("audioGroupId", variant.audioGroupId)
                        put("subtitleGroupId", variant.subtitleGroupId)
                        put("isDefault", variant.isDefault)
                        put("isAutoselect", variant.isAutoselect)
                        put("isForced", variant.isForced)
                        put("channels", variant.channels)
                        put("inStreamId", variant.inStreamId)
                    })
                }
            })
        }.toString()
    }

    private fun restoreLocatorState(state: Bundle) {
        val saved = state.getStringArrayList(STATE_CANDIDATES).orEmpty()
        saved.take(MAX_SAVED_CANDIDATES).forEach { raw ->
            val json = runCatching { JSONObject(raw) }.getOrNull() ?: return@forEach
            val requestContext = MediaLocatorRequestContextCache.get(json.optString("requestContextKey")) ?: return@forEach
            val url = requestContext.sourceUrl
            val pageUrl = requestContext.pageUrl
            val mime = json.optString("mime").takeIf(String::isNotBlank)
            val title = json.optString("pageTitle").takeIf(String::isNotBlank)
            val savedKind = runCatching { MediaSourceKind.valueOf(json.optString("kind")) }.getOrNull()
            val candidate = captureService.candidateFor(
                url,
                pageTitle = title,
                pageUrl = pageUrl,
                mimeTypeHint = mime ?: savedKind?.restoreMimeHint(),
                durationMs = requestContext.durationMs,
                thumbnailUrl = requestContext.thumbnailUrl,
                thumbnailProvenance = runCatching { MediaThumbnailProvenance.valueOf(json.optString("thumbnailProvenance")) }
                    .getOrDefault(requestContext.thumbnailProvenance),
            ) ?: return@forEach
            val base = captureService.recordFor(candidate)
            val record = base.copy(
                kind = savedKind ?: base.kind,
                // A synthetic restore MIME is classifier evidence only; do not fabricate durable metadata.
                mimeType = mime,
                manifestRole = runCatching { com.mikeyphw.xdm.android.model.MediaManifestRole.valueOf(json.optString("manifestRole")) }.getOrDefault(base.manifestRole),
                manifestIsLive = if (json.has("manifestIsLive")) json.optBoolean("manifestIsLive") else null,
                manifestProtected = json.optBoolean("manifestProtected", false),
                manifestProtectionScheme = json.optString("manifestProtectionScheme").takeIf(String::isNotBlank),
            )
            val variantsJson = json.optJSONArray("variants")
            val restoredVariants = buildList {
                if (variantsJson != null) {
                    for (index in 0 until minOf(variantsJson.length(), MAX_SAVED_VARIANTS_PER_CANDIDATE)) {
                        val item = variantsJson.optJSONObject(index) ?: continue
                        val variantId = item.optString("id").takeIf(String::isNotBlank) ?: "${record.id}:restored:$index"
                        val variantUrl = requestContext.variantUrls[variantId] ?: continue
                        val kind = runCatching { com.mikeyphw.xdm.android.model.MediaVariantKind.valueOf(item.optString("kind")) }.getOrNull() ?: continue
                        add(
                            MediaVariant(
                                id = variantId,
                                captureId = record.id,
                                url = variantUrl,
                                kind = kind,
                                mimeType = item.optString("mime").takeIf(String::isNotBlank),
                                width = item.optInt("width").takeIf { item.has("width") },
                                height = item.optInt("height").takeIf { item.has("height") },
                                bitrateBitsPerSecond = item.optLong("bitrate").takeIf { item.has("bitrate") },
                                codecs = item.optString("codecs").takeIf(String::isNotBlank),
                                language = item.optString("language").takeIf(String::isNotBlank),
                                position = item.optInt("position", index),
                                displayLabel = item.optString("displayLabel"),
                                expiresAtEpochMs = item.optLong("expiresAt").takeIf { item.has("expiresAt") },
                                groupId = item.optString("groupId").takeIf(String::isNotBlank),
                                audioGroupId = item.optString("audioGroupId").takeIf(String::isNotBlank),
                                subtitleGroupId = item.optString("subtitleGroupId").takeIf(String::isNotBlank),
                                isDefault = item.optBoolean("isDefault", false),
                                isAutoselect = item.optBoolean("isAutoselect", false),
                                isForced = item.optBoolean("isForced", false),
                                channels = item.optString("channels").takeIf(String::isNotBlank),
                                inStreamId = item.optString("inStreamId").takeIf(String::isNotBlank),
                            ),
                        )
                    }
                }
            }
            val effectiveRecord = if (restoredVariants.isEmpty()) {
                record
            } else {
                val selected = restoredVariants.first()
                record.copy(
                    variantCount = restoredVariants.size,
                    selectedVariantId = selected.id,
                    selectedVariantUrl = selected.url,
                    resolutionStatus = if (record.isPlaylist) {
                        com.mikeyphw.xdm.android.model.MediaResolutionStatus.Resolved
                    } else {
                        record.resolutionStatus
                    },
                )
            }
            putLocatedBounded(
                LocatedMedia(
                    logicalMediaId = effectiveRecord.logicalMediaId ?: LogicalMediaGraphEngine.logicalIdFor(url, pageUrl, effectiveRecord.kind),
                    url = url,
                    mimeType = mime,
                    kind = effectiveRecord.kind,
                    reason = json.optString("reason").ifBlank { "restored observation" },
                    pageUrl = pageUrl,
                    pageTitle = title,
                    requestHeaders = requestContext.headers,
                    rank = json.optInt("rank", 0),
                    record = effectiveRecord,
                    variants = restoredVariants,
                ),
            )
        }
        refreshList()
        updateLocatorStatus()
    }

    private fun MediaSourceKind.restoreMimeHint(): String? = when (this) {
        MediaSourceKind.HlsPlaylist -> "application/vnd.apple.mpegurl"
        MediaSourceKind.DashManifest -> "application/dash+xml"
        MediaSourceKind.ProgressiveMedia, MediaSourceKind.VideoStream -> "video/mp4"
        MediaSourceKind.AudioStream -> "audio/mpeg"
        MediaSourceKind.DirectFile, MediaSourceKind.Unknown -> null
    }

    private fun jsonHeaders(json: JSONObject?): Map<String, String> {
        if (json == null) return emptyMap()
        val allowed = setOf("accept", "accept-language", "authorization", "cookie", "origin", "range", "referer", "user-agent")
        return buildMap {
            json.keys().forEach { name ->
                val normalized = name.trim().lowercase(Locale.US)
                if (normalized in allowed) {
                    json.optString(name).trim().takeIf { it.isNotBlank() && !it.contains('\n') && !it.contains('\r') }?.let { put(name, it.take(8192)) }
                }
            }
        }
    }

    private fun encodeHeaderBlock(headers: Map<String, String>): String? {
        if (headers.isEmpty()) return null
        return headers.entries
            .filter { (name, value) -> name.isNotBlank() && value.isNotBlank() && !name.contains(':') && !value.contains('\n') && !value.contains('\r') }
            .take(24)
            .joinToString("\n") { (name, value) -> "${name.take(128)}: ${value.take(8192)}" }
            .take(12 * 1024)
            .takeIf(String::isNotBlank)
    }

    private fun normalizePageUrl(raw: String): String? = runCatching {
        val text = raw.trim()
        val withScheme = if ("://" in text) text else "https://$text"
        val uri = URI(withScheme)
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.rawUserInfo != null) null else uri.toASCIIString()
    }.getOrNull()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_URL = "xdm.media_locator.url"
        private const val JS_BRIDGE = "XdmMediaLocator"
        private const val STATE_URL = "xdm.media_locator.state.url"
        private const val STATE_TITLE = "xdm.media_locator.state.title"
        private const val STATE_WEBVIEW = "xdm.media_locator.state.webview"
        private const val STATE_SCROLL_X = "xdm.media_locator.state.scroll_x"
        private const val STATE_SCROLL_Y = "xdm.media_locator.state.scroll_y"
        private const val STATE_FAVICON = "xdm.media_locator.state.favicon"
        private const val STATE_CANDIDATES = "xdm.media_locator.state.candidates"
        private const val MAX_NATIVE_REQUESTS = 256
        private const val MAX_RECENT_OBSERVATIONS = 512
        private const val MAX_PENDING_OBSERVATIONS = 48
        private const val MAX_LOCATED_CANDIDATES = 128
        private const val MAX_SAVED_CANDIDATES = 64
        private const val MAX_SAVED_VARIANTS_PER_CANDIDATE = 24
        private const val NATIVE_REQUEST_TTL_MS = 90_000L
        private const val OBSERVATION_DEDUPE_MS = 750L

        fun intent(context: Context, url: String? = null): Intent = Intent(context, MediaLocatorActivity::class.java).apply {
            url?.trim()?.takeIf(String::isNotBlank)?.let { putExtra(EXTRA_URL, it) }
        }

        // Kept as a literal source string so contract tests can audit exactly what is injected.
        private val LOCATOR_RUNTIME = """
            (() => {
              if (window.__xdmLocatorInstalled) { window.__xdmLocatorScheduleScan && window.__xdmLocatorScheduleScan(); return; }
              window.__xdmLocatorInstalled = true;
              const bridge = window.XdmMediaLocator;
              const MAX_BODY = 262144;
              const MANIFEST_MIME = /(?:mpegurl|dash\+xml|\bmpd\b)/i;
              const TEXT_DISCOVERY_MIME = /^(?:application\/(?:json|ld\+json)|text\/(?:plain|json))/i;
              const HARD_NON_MEDIA = /^(?:text\/(?:html|css|javascript)|application\/(?:javascript|x-javascript)|image\/|font\/)/i;
              const page = () => location.href;
              const title = () => document.title || '';
              const artworkUrl = (value) => {
                if (!value || /^(?:blob|data|javascript):/i.test(String(value))) return '';
                try {
                  const parsed = new URL(String(value), document.baseURI);
                  return /^https?:$/.test(parsed.protocol) ? parsed.href : '';
                } catch (_) { return ''; }
              };
              let artworkCache = { at: 0, url: '', provenance: 'Unknown' };
              const pageArtwork = () => {
                const now = Date.now();
                if (now - artworkCache.at < 2000) return artworkCache;
                let found = '';
                let provenance = 'Unknown';
                try {
                  const selectors = [
                    ['meta[property="og:image"]', 'OpenGraph'], ['meta[property="og:image:url"]', 'OpenGraph'],
                    ['meta[name="twitter:image"]', 'TwitterCard'], ['meta[name="twitter:image:src"]', 'TwitterCard'],
                    ['link[rel="image_src"]', 'LinkImage']
                  ];
                  for (const [selector, source] of selectors) {
                    const node = document.querySelector(selector);
                    found = artworkUrl(node && (node.content || node.href || node.getAttribute('content') || node.getAttribute('href')));
                    if (found) { provenance = source; break; }
                  }
                } catch (_) {}
                if (!found) {
                  try {
                    const pick = (value, depth = 0) => {
                      if (depth > 4 || value == null) return '';
                      if (typeof value === 'string') return artworkUrl(value);
                      if (Array.isArray(value)) {
                        for (const item of value.slice(0, 16)) { const hit = pick(item, depth + 1); if (hit) return hit; }
                        return '';
                      }
                      if (typeof value !== 'object') return '';
                      for (const key of ['thumbnailUrl','thumbnail','image']) {
                        if (Object.prototype.hasOwnProperty.call(value, key)) { const hit = pick(value[key], depth + 1); if (hit) return hit; }
                      }
                      return '';
                    };
                    for (const script of [...document.querySelectorAll('script[type="application/ld+json"]')].slice(0, 12)) {
                      const text = String(script.textContent || '').slice(0, 131072);
                      if (!text) continue;
                      try { found = pick(JSON.parse(text)); } catch (_) {}
                      if (found) { provenance = 'JsonLd'; break; }
                    }
                  } catch (_) {}
                }
                artworkCache = { at: now, url: found || '', provenance: found ? provenance : 'Unknown' };
                return artworkCache;
              };
              const safeHeaders = (headers) => {
                const out = {};
                try {
                  new Headers(headers || {}).forEach((value, name) => {
                    if (/^(accept|accept-language|authorization|cookie|origin|range|referer|user-agent)$/i.test(name)) out[name] = String(value).slice(0, 8192);
                  });
                } catch (_) {}
                return out;
              };
              const emit = (data) => {
                try {
                  const u = new URL(data.url, location.href);
                  if (!/^https?:$/.test(u.protocol)) return;
                  const mime = String(data.mime || '').split(';')[0].trim();
                  if (HARD_NON_MEDIA.test(mime) && !data.body) return;
                  const explicitThumbnail = artworkUrl(data.thumbnailUrl);
                  const fallbackArtwork = explicitThumbnail ? { url: explicitThumbnail, provenance: data.thumbnailProvenance || 'Unknown' } : pageArtwork();
                  bridge.observe(JSON.stringify({ ...data, url: u.href, mime, thumbnailUrl: fallbackArtwork.url, thumbnailProvenance: fallbackArtwork.provenance, pageUrl: page(), title: title() }));
                } catch (_) {}
              };
              const boundedText = async (response) => {
                try {
                  const body = response && response.body;
                  if (!body || typeof body.getReader !== 'function') return '';
                  const reader = body.getReader();
                  const decoder = new TextDecoder();
                  let result = '';
                  let total = 0;
                  while (total < MAX_BODY) {
                    const step = await reader.read();
                    if (step.done) break;
                    const value = step.value || new Uint8Array(0);
                    const take = Math.min(value.byteLength, MAX_BODY - total);
                    result += decoder.decode(value.subarray(0, take), { stream: true });
                    total += take;
                    if (take < value.byteLength || total >= MAX_BODY) { try { await reader.cancel(); } catch (_) {} break; }
                  }
                  result += decoder.decode();
                  return result.slice(0, MAX_BODY);
                } catch (_) { return ''; }
              };
              const scan = () => {
                try {
                  document.querySelectorAll('video,audio,source').forEach((node) => {
                    const url = node.currentSrc || node.src || node.getAttribute('src');
                    if (!url) return;
                    const mediaNode = node.tagName === 'SOURCE' ? node.closest('video,audio') : node;
                    let mime = node.getAttribute('type') || '';
                    if (!mime && mediaNode && mediaNode.tagName === 'VIDEO') mime = 'video/unknown';
                    if (!mime && mediaNode && mediaNode.tagName === 'AUDIO') mime = 'audio/unknown';
                    const duration = Number(mediaNode && mediaNode.duration || 0);
                    const durationMs = Number.isFinite(duration) && duration > 0 ? Math.floor(duration * 1000) : 0;
                    const posterUrl = mediaNode && mediaNode.tagName === 'VIDEO' ? artworkUrl(mediaNode.poster || '') : '';
                    const artwork = posterUrl ? { url: posterUrl, provenance: 'PagePoster' } : pageArtwork();
                    emit({ url, mime, durationMs, thumbnailUrl: artwork.url, thumbnailProvenance: artwork.provenance, source: 'dom' });
                  });
                } catch (_) {}
                try {
                  performance.getEntriesByType('resource').forEach((entry) => {
                    const initiator = String(entry.initiatorType || '').toLowerCase();
                    if (!/^(video|audio|fetch|xmlhttprequest)$/.test(initiator)) return;
                    emit({ url: entry.name, mime: (initiator === 'video' || initiator === 'audio') ? initiator + '/unknown' : '', source: 'performance-' + initiator });
                  });
                } catch (_) {}
              };
              let scanTimer = 0;
              const scheduleScan = () => { clearTimeout(scanTimer); scanTimer = setTimeout(scan, 120); };
              window.__xdmLocatorScan = scan;
              window.__xdmLocatorScheduleScan = scheduleScan;

              const originalFetch = window.fetch;
              if (typeof originalFetch === 'function') {
                window.fetch = async function(input, init) {
                  const response = await originalFetch.apply(this, arguments);
                  try {
                    const mime = response.headers.get('content-type') || '';
                    const length = Number(response.headers.get('content-length') || '-1');
                    const requestHeaders = safeHeaders((init && init.headers) || (input instanceof Request ? input.headers : undefined));
                    const responseUrl = response.url || String(input);
                    emit({ url: responseUrl, mime, contentDisposition: response.headers.get('content-disposition') || '', contentLength: length, source: 'fetch', requestHeaders });
                    if (MANIFEST_MIME.test(mime) || TEXT_DISCOVERY_MIME.test(mime)) {
                      boundedText(response.clone()).then((body) => { if (body) emit({ url: responseUrl, mime, contentDisposition: response.headers.get('content-disposition') || '', contentLength: length, body, source: 'fetch-body', requestHeaders }); }).catch(() => {});
                    }
                  } catch (_) {}
                  return response;
                };
              }
              const XHR = window.XMLHttpRequest;
              if (XHR && XHR.prototype) {
                const open = XHR.prototype.open;
                const send = XHR.prototype.send;
                const setHeader = XHR.prototype.setRequestHeader;
                XHR.prototype.open = function(method, url) { this.__xdmUrl = url; this.__xdmHeaders = {}; return open.apply(this, arguments); };
                XHR.prototype.setRequestHeader = function(name, value) { try { this.__xdmHeaders[name] = value; } catch (_) {} return setHeader.apply(this, arguments); };
                XHR.prototype.send = function() {
                  this.addEventListener('loadend', () => {
                    try {
                      const mime = this.getResponseHeader('content-type') || '';
                      const length = Number(this.getResponseHeader('content-length') || '-1');
                      let body = '';
                      if ((MANIFEST_MIME.test(mime) || TEXT_DISCOVERY_MIME.test(mime)) && (!this.responseType || this.responseType === 'text')) body = String(this.responseText || '').slice(0, MAX_BODY);
                      emit({ url: this.responseURL || this.__xdmUrl, mime, contentDisposition: this.getResponseHeader('content-disposition') || '', contentLength: length, body, source: body ? 'xhr-body' : 'xhr', requestHeaders: safeHeaders(this.__xdmHeaders) });
                    } catch (_) {}
                  }, { once: true });
                  return send.apply(this, arguments);
                };
              }
              try { new MutationObserver(scheduleScan).observe(document, { subtree: true, childList: true, attributes: true, attributeFilter: ['src'] }); } catch (_) {}
              document.addEventListener('play', scheduleScan, true);
              if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', scheduleScan, { once: true });
              scheduleScan();
            })();
        """.trimIndent()
    }
}
