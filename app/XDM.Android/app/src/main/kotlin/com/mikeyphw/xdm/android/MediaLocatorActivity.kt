package com.mikeyphw.xdm.android

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.mikeyphw.xdm.android.browser.XdmBrowserDeepLinkContract
import com.mikeyphw.xdm.android.media.MediaSniffingEngine
import com.mikeyphw.xdm.android.media.MediaSniffingInput
import com.mikeyphw.xdm.android.media.MediaSniffingSource
import com.mikeyphw.xdm.android.model.MediaSourceKind
import java.net.URI
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Interactive media locator for pages that need JavaScript/runtime observation.
 *
 * This intentionally mirrors the useful parts of 1DM's locator model: actual DOM media,
 * fetch/XHR response metadata, manifest signatures, and a small amount of resource evidence are
 * collected as *observations*. Every observation is then passed through MediaSniffingEngine, the
 * same evidence gate used by browser-extension and app intake. Nothing is downloaded directly.
 */
class MediaLocatorActivity : ComponentActivity() {
    private data class LocatedMedia(
        val url: String,
        val mimeType: String?,
        val kind: MediaSourceKind,
        val reason: String,
        val pageUrl: String?,
        val pageTitle: String?,
        val requestHeaders: Map<String, String>,
        val rank: Int,
    )

    private val engine = MediaSniffingEngine()
    private val located = linkedMapOf<String, LocatedMedia>()
    private lateinit var webView: WebView
    private lateinit var address: EditText
    private lateinit var status: TextView
    private lateinit var list: ListView
    private lateinit var adapter: ArrayAdapter<String>

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        address = EditText(this).apply {
            hint = getString(R.string.media_locator_url_hint)
            setSingleLine(true)
            setText(intent.getStringExtra(EXTRA_URL).orEmpty())
        }
        val go = Button(this).apply { text = getString(R.string.media_locator_locate) }
        val rescan = Button(this).apply { text = getString(R.string.media_locator_rescan) }
        status = TextView(this).apply {
            text = getString(R.string.media_locator_initial_status)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        webView = WebView(this)
        list = ListView(this)
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_2, android.R.id.text1, mutableListOf())
        list.adapter = adapter

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(go, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(rescan, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            addView(address, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 3f))
            addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 2f))
        }
        setContentView(root)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false

            // The WebView exists only as an isolated media-observation runtime.
            // Do not allow it to become a local-file/content browser.
            allowFileAccess = false
            allowContentAccess = false

            // No popup/general-browser window surface.
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)

            // Do not permit HTTPS pages to downgrade media/resource requests to
            // cleartext HTTP inside the locator.
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        webView.addJavascriptInterface(MediaObservationBridge(), JS_BRIDGE)
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

            override fun onPageFinished(view: WebView, url: String) {
                address.setText(url)
                injectLocatorRuntime()
                status.text = if (located.isEmpty()) {
                    getString(R.string.media_locator_waiting_for_evidence)
                } else {
                    resources.getQuantityString(
                        R.plurals.media_locator_candidates_found,
                        located.size,
                        located.size,
                    )
                }
            }
        }

        go.setOnClickListener { loadAddress() }
        rescan.setOnClickListener {
            injectLocatorRuntime(forceScan = true)
            status.text = getString(R.string.media_locator_rescanning)
        }
        list.setOnItemClickListener { _, _, position, _ ->
            located.values.sortedWith(compareByDescending<LocatedMedia> { it.rank }.thenBy { it.url })
                .getOrNull(position)
                ?.let(::reviewCandidate)
        }

        if (address.text.toString().isNotBlank()) loadAddress()
    }

    override fun onDestroy() {
        webView.removeJavascriptInterface(JS_BRIDGE)
        webView.stopLoading()
        webView.destroy()
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
        webView.loadUrl(normalized)
    }

    private fun injectLocatorRuntime(forceScan: Boolean = false) {
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
            val pageUrl = observation.optString("pageUrl").trim().takeIf(String::isNotBlank)
            val title = observation.optString("title").trim().takeIf(String::isNotBlank)
            val source = observation.optString("source").ifBlank { "runtime" }
            val requestHeaders = jsonHeaders(observation.optJSONObject("requestHeaders"))
            val contentDisposition = observation.optString("contentDisposition").trim().takeIf(String::isNotBlank)
            val contentLength = observation.optLong("contentLength", -1L).takeIf { it >= 0L }

            lifecycleScope.launch {
                val mergedHeaders = requestHeaders + browserSessionHeaders(url, pageUrl)
                val plan = withContext(Dispatchers.Default) {
                    engine.sniff(
                        MediaSniffingInput(
                            url = url,
                            mimeType = mime,
                            contentDisposition = contentDisposition,
                            contentLength = contentLength,
                            bodyPrefix = body,
                            pageUrl = pageUrl,
                            pageTitle = title,
                            requestHeaders = mergedHeaders,
                            source = if (source == "dom") MediaSniffingSource.AppPageProbe else MediaSniffingSource.NetworkObservation,
                        ),
                    )
                }
                if (plan.candidates.isEmpty()) return@launch
                val found = plan.candidates.map { candidate ->
                    LocatedMedia(
                        url = candidate.url,
                        mimeType = candidate.mimeType,
                        kind = candidate.kind,
                        reason = candidate.reason,
                        pageUrl = candidate.pageUrl,
                        pageTitle = candidate.title,
                        requestHeaders = mergedHeaders,
                        rank = candidate.rank,
                    )
                }
                found.forEach { candidate ->
                    val previous = located[candidate.url]
                    if (previous == null || candidate.rank >= previous.rank) located[candidate.url] = candidate
                }
                refreshList()
                status.text = resources.getQuantityString(
                    R.plurals.media_locator_candidates_found,
                    located.size,
                    located.size,
                )
            }
        }
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
    }

    private fun reviewCandidate(candidate: LocatedMedia) {
        val builder = Uri.Builder()
            .scheme(BuildConfig.XDM_BROWSER_SCHEME)
            .authority(XdmBrowserDeepLinkContract.CaptureHost)
            .appendQueryParameter(XdmBrowserDeepLinkContract.VersionParameter, XdmBrowserDeepLinkContract.CurrentVersion.toString())
            .appendQueryParameter(XdmBrowserDeepLinkContract.UrlParameter, candidate.url)
            .appendQueryParameter(XdmBrowserDeepLinkContract.MediaKindParameter, candidate.kind.name.lowercase(Locale.US))
        candidate.mimeType?.let { builder.appendQueryParameter(XdmBrowserDeepLinkContract.MimeTypeParameter, it) }
        candidate.pageUrl?.let { builder.appendQueryParameter(XdmBrowserDeepLinkContract.PageUrlParameter, it) }
        candidate.pageTitle?.let { builder.appendQueryParameter(XdmBrowserDeepLinkContract.PageTitleParameter, it) }
        encodeHeaderBlock(candidate.requestHeaders)?.let {
            builder.appendQueryParameter(XdmBrowserDeepLinkContract.RawHeadersParameter, it)
        }
        startActivity(
            Intent(Intent.ACTION_VIEW, builder.build(), this, ExternalHandoffReviewActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
    }

    private fun browserSessionHeaders(mediaUrl: String, pageUrl: String?): Map<String, String> = buildMap {
        CookieManager.getInstance().getCookie(mediaUrl)?.takeIf(String::isNotBlank)?.let { put("Cookie", it) }
        webView.settings.userAgentString?.takeIf(String::isNotBlank)?.let { put("User-Agent", it) }
        pageUrl?.takeIf(String::isNotBlank)?.let { put("Referer", it) }
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

        fun intent(context: Context, url: String? = null): Intent = Intent(context, MediaLocatorActivity::class.java).apply {
            url?.trim()?.takeIf(String::isNotBlank)?.let { putExtra(EXTRA_URL, it) }
        }

        // Kept as a literal source string so contract tests can audit exactly what is injected.
        private val LOCATOR_RUNTIME = """
            (() => {
              if (window.__xdmLocatorInstalled) { window.__xdmLocatorScan && window.__xdmLocatorScan(); return; }
              window.__xdmLocatorInstalled = true;
              const bridge = window.XdmMediaLocator;
              const MAX_BODY = 262144;
              const MEDIA_MIME = /^(?:video|audio)\//i;
              const MANIFEST_MIME = /(?:mpegurl|dash\+xml|\bmpd\b)/i;
              const HARD_NON_MEDIA = /^(?:application\/(?:json|ld\+json|javascript|x-javascript)|text\/(?:html|css|javascript)|image\/|font\/)/i;
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
              const scan = () => {
                document.querySelectorAll('video,audio,source').forEach((node) => {
                  const url = node.currentSrc || node.src || node.getAttribute('src');
                  if (!url) return;
                  let mime = node.getAttribute('type') || '';
                  if (!mime && node.tagName === 'VIDEO') mime = 'video/unknown';
                  if (!mime && node.tagName === 'AUDIO') mime = 'audio/unknown';
                  emit({ url, mime, source: 'dom' });
                });
                try {
                  performance.getEntriesByType('resource').forEach((entry) => {
                    const initiator = String(entry.initiatorType || '').toLowerCase();
                    if (initiator !== 'video' && initiator !== 'audio') return;
                    emit({ url: entry.name, mime: initiator + '/unknown', source: 'performance-media' });
                  });
                } catch (_) {}
              };
              window.__xdmLocatorScan = scan;
              const originalFetch = window.fetch;
              if (typeof originalFetch === 'function') {
                window.fetch = async function(input, init) {
                  const response = await originalFetch.apply(this, arguments);
                  try {
                    const mime = response.headers.get('content-type') || '';
                    const length = Number(response.headers.get('content-length') || '-1');
                    const requestHeaders = safeHeaders((init && init.headers) || (input instanceof Request ? input.headers : undefined));
                    emit({ url: response.url || String(input), mime, contentDisposition: response.headers.get('content-disposition') || '', contentLength: length, source: 'fetch', requestHeaders });
                    const inspectBody = MANIFEST_MIME.test(mime) || ((/^(?:application\/json|text\/plain)/i.test(mime)) && length >= 0 && length <= MAX_BODY);
                    if (inspectBody) response.clone().text().then((body) => emit({ url: response.url || String(input), mime, contentDisposition: response.headers.get('content-disposition') || '', contentLength: length, body: body.slice(0, MAX_BODY), source: 'fetch', requestHeaders })).catch(() => {});
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
                      if ((MANIFEST_MIME.test(mime) || (/^(?:application\/json|text\/plain)/i.test(mime) && length >= 0 && length <= MAX_BODY)) && (!this.responseType || this.responseType === 'text')) body = String(this.responseText || '').slice(0, MAX_BODY);
                      emit({ url: this.responseURL || this.__xdmUrl, mime, contentDisposition: this.getResponseHeader('content-disposition') || '', contentLength: length, body, source: 'xhr', requestHeaders: safeHeaders(this.__xdmHeaders) });
                    } catch (_) {}
                  }, { once: true });
                  return send.apply(this, arguments);
                };
              }
              new MutationObserver(scan).observe(document.documentElement || document, { subtree: true, childList: true, attributes: true, attributeFilter: ['src'] });
              document.addEventListener('play', scan, true);
              scan();
            })();
        """.trimIndent()
    }
}
