package com.mikeyphw.xdm.android

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.util.TypedValue
import android.view.View
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.ProgressBar
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.mikeyphw.xdm.android.media.MediaCaptureService
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
    private var locatorUserAgent: String? = null
    private lateinit var webView: WebView
    private lateinit var address: EditText
    private lateinit var status: TextView
    private lateinit var resultsHeader: TextView
    private lateinit var progress: ProgressBar
    private lateinit var list: ListView
    private lateinit var adapter: ArrayAdapter<String>
    private var webViewDisposed = false

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
        val pageBack = Button(this).apply { text = getString(R.string.media_locator_back) }
        val pageForward = Button(this).apply { text = getString(R.string.media_locator_forward) }
        val reload = Button(this).apply { text = getString(R.string.media_locator_reload) }
        val stop = Button(this).apply { text = getString(R.string.media_locator_stop) }
        val rescan = Button(this).apply { text = getString(R.string.media_locator_scan) }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }
        status = TextView(this).apply {
            text = getString(R.string.media_locator_initial_status)
            setTextColor(secondaryText)
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        resultsHeader = TextView(this).apply {
            text = getString(R.string.media_locator_no_candidates)
            setTextColor(primaryText)
            textSize = 16f
            setPadding(dp(16), dp(10), dp(16), dp(6))
        }
        webView = WebView(this)
        list = ListView(this)
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_2, android.R.id.text1, mutableListOf())
        list.adapter = adapter

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(close, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
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
            addView(pageBack, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(pageForward, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(reload, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val scanControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), 0, dp(8), 0)
            addView(stop, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(rescan, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(background)
            addView(topBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(addressRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(controls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(scanControls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))
            addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 3f))
            addView(resultsHeader, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 2f))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(root)
        ViewCompat.requestApplyInsets(root)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        locatorUserAgent = webView.settings.userAgentString
        enableCookieAwareRequestInterception(webView)
        webView.addJavascriptInterface(MediaObservationBridge(), JS_BRIDGE)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, LOCATOR_RUNTIME, setOf("*"))
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
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
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) injectLocatorRuntime()
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                recordNativeRequest(request)
                return null
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
                address.setText(lastUrl)
                progress.visibility = View.GONE
                status.text = getString(R.string.media_locator_renderer_stopped)
                if (detail.didCrash()) recentlyObserved.clear()
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                currentPageUrl = url
                debugRecorder.record(
                    area = DebugArea.WebView,
                    action = "page-load",
                    result = "finished",
                    safeDetails = mapOf("url" to url, "candidateCount" to located.size.toString()),
                    operationId = pageOperationId,
                )
                address.setText(url)
                progress.visibility = View.GONE
                injectLocatorRuntime(forceScan = true)
                updateLocatorStatus()
            }
        }

        close.setOnClickListener { finish() }
        go.setOnClickListener { loadAddress() }
        pageBack.setOnClickListener { if (!webViewDisposed && webView.canGoBack()) webView.goBack() }
        pageForward.setOnClickListener { if (!webViewDisposed && webView.canGoForward()) webView.goForward() }
        reload.setOnClickListener {
            if (webViewDisposed) loadAddress() else webView.reload()
        }
        stop.setOnClickListener {
            if (!webViewDisposed) webView.stopLoading()
            progress.visibility = View.GONE
            status.text = getString(R.string.media_locator_loading_stopped)
        }
        rescan.setOnClickListener {
            if (webViewDisposed) {
                loadAddress()
            } else {
                injectLocatorRuntime(forceScan = true)
                status.text = getString(R.string.media_locator_rescanning)
            }
        }
        list.setOnItemClickListener { _, _, position, _ ->
            located.values.sortedWith(compareByDescending<LocatedMedia> { it.rank }.thenBy { it.url })
                .getOrNull(position)
                ?.let(::reviewCandidate)
        }

        if (savedInstanceState != null) restoreLocatorState(savedInstanceState)
        updateCandidateHeader()
        val initial = normalizePageUrl(address.text.toString())
        if (initial != null) {
            status.text = getString(R.string.media_locator_loading)
            webView.loadUrl(initial)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_URL, currentPageUrl ?: address.text.toString())
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
            status.text = getString(R.string.media_locator_invalid_url)
            return
        }
        located.clear()
        refreshList()
        status.text = getString(R.string.media_locator_loading)
        if (webViewDisposed) {
            intent.putExtra(EXTRA_URL, normalized)
            recreate()
            return
        }
        webView.loadUrl(normalized)
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
                    val plan = withContext(Dispatchers.Default) {
                        engine.sniff(
                            MediaSniffingInput(
                                url = url,
                                mimeType = mime,
                                contentDisposition = contentDisposition,
                                contentLength = contentLength,
                                bodyPrefix = body,
                                pageUrl = authoritativePage,
                                pageTitle = title,
                                requestHeaders = primaryHeaders,
                                source = if (source == "dom") MediaSniffingSource.AppPageProbe else MediaSniffingSource.NetworkObservation,
                            ),
                        )
                    }
                    if (plan.candidates.isEmpty()) return@launch
                    val recordsById = plan.records.associateBy(MediaCaptureRecord::id)
                    val found = plan.candidates.mapNotNull { candidate ->
                        val captureId = MediaCaptureService.captureIdFor(candidate.url)
                        val record = recordsById[captureId] ?: return@mapNotNull null
                        val candidateCorrelated = nativeEvidenceFor(candidate.url)
                        val sameObservedUrl = correlationKey(candidate.url) == key
                        val candidateHeaders = if (sameObservedUrl && candidateCorrelated != null) {
                            safeInheritedSessionHeaders(authoritativePage) + correlatedRequestHeaders(candidate.url, candidateCorrelated) + jsHeaders
                        } else {
                            // URLs extracted from a response body may point at a different CDN. Do not
                            // copy the parent Authorization/Cookie set onto that child URL.
                            safeInheritedSessionHeaders(authoritativePage)
                        }
                        LocatedMedia(
                            url = candidate.url,
                            mimeType = candidate.mimeType,
                            kind = candidate.kind,
                            reason = candidate.reason + if (candidateCorrelated != null) "+native-request" else "+js-evidence",
                            pageUrl = authoritativePage,
                            pageTitle = candidate.title,
                            requestHeaders = candidateHeaders,
                            rank = candidate.rank + if (candidateCorrelated != null) 8 else 0,
                            record = record,
                            variants = plan.variants.filter { it.captureId == captureId },
                        )
                    }
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
        val previous = located[candidate.url]
        if (previous == null || candidate.rank >= previous.rank) located[candidate.url] = candidate
        while (located.size > MAX_LOCATED_CANDIDATES) {
            val weakest = located.values.minWithOrNull(compareBy<LocatedMedia> { it.rank }.thenBy { it.url }) ?: break
            located.remove(weakest.url)
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
        val labels = located.values
            .sortedWith(compareByDescending<LocatedMedia> { it.rank }.thenBy { it.url })
            .map { item ->
                val host = runCatching { URI(item.url).host }.getOrNull().orEmpty()
                "${item.kind.name.replace('_', ' ')} • ${item.mimeType ?: "type inferred"}\n$host • ${item.reason}"
            }
        adapter.clear()
        adapter.addAll(labels)
        adapter.notifyDataSetChanged()
        updateCandidateHeader()
    }

    private fun updateCandidateHeader() {
        resultsHeader.text = if (located.isEmpty()) {
            getString(R.string.media_locator_no_candidates)
        } else {
            resources.getQuantityString(R.plurals.media_locator_candidates_header, located.size, located.size)
        }
        list.visibility = if (located.isEmpty()) View.GONE else View.VISIBLE
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
        status.text = getString(R.string.media_locator_saving)
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
            ),
        )
        return JSONObject().apply {
            put("requestContextKey", requestContextKey)
            put("mime", candidate.mimeType)
            put("kind", candidate.kind.name)
            put("reason", candidate.reason.take(256))
            put("pageTitle", candidate.pageTitle)
            put("rank", candidate.rank)
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
                  bridge.observe(JSON.stringify({ ...data, url: u.href, mime, pageUrl: page(), title: title() }));
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
                    let mime = node.getAttribute('type') || '';
                    if (!mime && node.tagName === 'VIDEO') mime = 'video/unknown';
                    if (!mime && node.tagName === 'AUDIO') mime = 'audio/unknown';
                    emit({ url, mime, source: 'dom' });
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
