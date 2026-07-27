(() => {
  if (window.__xdmPageSnifferV1) return;
  window.__xdmPageSnifferV1 = true;

  const MARKER = "__xdmMediaObservationV1";
  const STATUS_MARKER = "__xdmPageSnifferStatusV1";
  let fetchWrapperActive = false;
  let xhrWrapperActive = false;
  let mediaPlayWrapperActive = false;
  let resourceObserverActive = false;
  let lastError = "";
  const MAX_BODY_BYTES = 786_432;
  const TEXT_MIME_RE = /^(?:text\/|application\/(?:json|ld\+json|javascript|x-javascript|xml|xhtml\+xml|rss\+xml|atom\+xml|vnd\.apple\.mpegurl|x-mpegurl|dash\+xml))/i;
  const RESOURCE_HINT_RE = /(?:\.(?:m3u8|mpd|mp4|m4v|webm|mkv|mov|mp3|m4a|aac|ogg)(?:$|[?#])|videoplayback|playlist|manifest|\/stream(?:\/|\?|$)|\/playback(?:\/|\?|$)|hls|dash)/i;
  const MEDIA_RESPONSE_MIME_RE = /^(?:video\/|audio\/|application\/(?:vnd\.apple\.mpegurl|x-mpegurl|dash\+xml)|audio\/(?:mpegurl|x-mpegurl))/i;
  const BODY_HINT_RE = /(?:#EXTM3U|<\s*MPD(?:\s|>)|\.m3u8|\.mpd|\.mp4|videoplayback|(?:file|video|audio|media|stream|manifest|playlist|hls|dash)(?:Url|URL|_url|_src)?["'\s]*[:=])/i;
  const REQUEST_HEADER_ALLOWLIST = new Set(["authorization", "cookie", "referer", "user-agent", "origin", "accept", "range"]);


  function rememberError(error) {
    if (!lastError) lastError = error && error.message ? error.message : String(error || "");
  }

  function publishStatus() {
    window.postMessage({
      [STATUS_MARKER]: true,
      status: {
        active: true,
        fetchWrapperActive,
        xhrWrapperActive,
        mediaPlayWrapperActive,
        resourceObserverActive,
        lastError
      }
    }, "*");
  }

  function normalizeMime(value) {
    return String(value || "").split(";", 1)[0].trim().toLowerCase();
  }

  function isInspectableMime(value, url = "") {
    const mime = normalizeMime(value);
    if (!mime || TEXT_MIME_RE.test(mime)) return true;
    return (mime === "application/octet-stream" || mime === "binary/octet-stream") &&
      /(?:m3u8|mpd|manifest|playlist|hls|dash)/i.test(String(url || ""));
  }

  function absoluteUrl(value) {
    if (!value || /^(?:blob|data|javascript|mediasource):/i.test(String(value))) return "";
    try {
      const url = new URL(String(value), document.baseURI).href;
      return /^https?:/i.test(url) ? url : "";
    } catch (_) {
      return "";
    }
  }

  function sanitizeHeaders(input) {
    const result = {};
    try {
      const headers = new Headers(input || undefined);
      headers.forEach((value, name) => {
        const key = String(name || "").toLowerCase();
        if (!REQUEST_HEADER_ALLOWLIST.has(key)) return;
        const clean = String(value || "").replace(/[\r\n]+/g, " ").trim();
        if (clean) result[key] = clean.slice(0, 8192);
      });
    } catch (_) {
      if (input && typeof input === "object" && !Array.isArray(input)) {
        for (const [name, value] of Object.entries(input)) {
          const key = String(name || "").toLowerCase();
          if (!REQUEST_HEADER_ALLOWLIST.has(key)) continue;
          const clean = String(value == null ? "" : value).replace(/[\r\n]+/g, " ").trim();
          if (clean) result[key] = clean.slice(0, 8192);
        }
      }
    }
    return result;
  }

  function publish(observation) {
    const value = observation && typeof observation === "object" ? observation : {};
    const responseUrl = absoluteUrl(value.responseUrl || value.url || value.requestUrl);
    const requestUrl = absoluteUrl(value.requestUrl || value.url || responseUrl);
    if (!responseUrl && !requestUrl) return;
    const contentType = String(value.contentType || "");
    const bodyExcerpt = typeof value.bodyExcerpt === "string" ? value.bodyExcerpt.slice(0, MAX_BODY_BYTES) : "";
    const relevant = RESOURCE_HINT_RE.test(responseUrl || requestUrl) || MEDIA_RESPONSE_MIME_RE.test(contentType) || BODY_HINT_RE.test(bodyExcerpt);
    if (!relevant) return;
    window.postMessage({
      [MARKER]: true,
      observation: {
        source: String(value.source || "page").slice(0, 80),
        requestType: String(value.requestType || value.source || "other").slice(0, 80),
        method: String(value.method || "GET").slice(0, 16),
        requestUrl,
        responseUrl: responseUrl || requestUrl,
        contentType: contentType.slice(0, 256),
        contentDisposition: String(value.contentDisposition || "").slice(0, 512),
        contentLength: Number(value.contentLength || 0),
        contentRange: String(value.contentRange || "").slice(0, 256),
        requestHeaders: sanitizeHeaders(value.requestHeaders || {}),
        bodyExcerpt,
        pageUrl: location.href
      }
    }, "*");
  }

  async function readTextPrefix(response) {
    if (!response || !isInspectableMime(response.headers && response.headers.get("content-type"), response.url || "")) return "";
    const declared = Number(response.headers && response.headers.get("content-length") || 0);
    if (declared > MAX_BODY_BYTES * 4) return "";

    const clone = response.clone();
    if (!clone.body || typeof clone.body.getReader !== "function" || typeof TextDecoder !== "function") {
      try { return String(await clone.text()).slice(0, MAX_BODY_BYTES); } catch (_) { return ""; }
    }

    const reader = clone.body.getReader();
    const decoder = new TextDecoder();
    let total = 0;
    let text = "";
    try {
      while (total < MAX_BODY_BYTES) {
        const chunk = await reader.read();
        if (chunk.done) break;
        const bytes = chunk.value || new Uint8Array();
        const remaining = Math.max(0, MAX_BODY_BYTES - total);
        const slice = bytes.byteLength > remaining ? bytes.slice(0, remaining) : bytes;
        total += slice.byteLength;
        text += decoder.decode(slice, { stream: total < MAX_BODY_BYTES });
        if (bytes.byteLength > remaining) break;
      }
      text += decoder.decode();
    } catch (_) {
      return text.slice(0, MAX_BODY_BYTES);
    } finally {
      try { await reader.cancel(); } catch (_) {}
    }
    return text.slice(0, MAX_BODY_BYTES);
  }

  function responseHeader(response, name) {
    try { return response.headers.get(name) || ""; } catch (_) { return ""; }
  }

  try {
    const nativeFetch = window.fetch;
    if (typeof nativeFetch === "function") {
      window.fetch = function xdmFetch(input, init) {
        const requestUrl = absoluteUrl(typeof input === "string" || input instanceof URL ? input : input && input.url);
        const method = String((init && init.method) || (input && input.method) || "GET").toUpperCase();
        const requestHeaders = Object.assign(
          {},
          sanitizeHeaders(input && input.headers),
          sanitizeHeaders(init && init.headers)
        );
        const promise = nativeFetch.apply(this, arguments);
        Promise.resolve(promise).then(response => {
          Promise.resolve().then(async () => {
            const contentType = responseHeader(response, "content-type");
            const bodyExcerpt = await readTextPrefix(response);
            publish({
              source: "fetch-response",
              requestType: "fetch",
              method,
              requestUrl,
              responseUrl: response && response.url || requestUrl,
              contentType,
              contentDisposition: responseHeader(response, "content-disposition"),
              contentLength: Number(responseHeader(response, "content-length") || 0),
              contentRange: responseHeader(response, "content-range"),
              requestHeaders,
              bodyExcerpt
            });
          }).catch(() => {});
        }).catch(() => {});
        return promise;
      };
      fetchWrapperActive = true;
    }
  } catch (error) { rememberError(error); }

  try {
    const metadata = new WeakMap();
    const nativeOpen = XMLHttpRequest.prototype.open;
    const nativeSetRequestHeader = XMLHttpRequest.prototype.setRequestHeader;
    const nativeSend = XMLHttpRequest.prototype.send;

    XMLHttpRequest.prototype.open = function xdmXhrOpen(method, url) {
      metadata.set(this, {
        method: String(method || "GET").toUpperCase(),
        requestUrl: absoluteUrl(url),
        requestHeaders: {},
        armed: false
      });
      return nativeOpen.apply(this, arguments);
    };

    XMLHttpRequest.prototype.setRequestHeader = function xdmXhrHeader(name, value) {
      const state = metadata.get(this);
      if (state) {
        const key = String(name || "").toLowerCase();
        if (REQUEST_HEADER_ALLOWLIST.has(key)) {
          state.requestHeaders[key] = String(value == null ? "" : value).replace(/[\r\n]+/g, " ").slice(0, 8192);
        }
      }
      return nativeSetRequestHeader.apply(this, arguments);
    };

    XMLHttpRequest.prototype.send = function xdmXhrSend() {
      const xhr = this;
      const state = metadata.get(xhr) || { method: "GET", requestUrl: "", requestHeaders: {}, armed: false };
      if (!state.armed) {
        state.armed = true;
        metadata.set(xhr, state);
        xhr.addEventListener("loadend", () => {
          try {
            const contentType = xhr.getResponseHeader("content-type") || "";
            let bodyExcerpt = "";
            if (isInspectableMime(contentType, xhr.responseURL || state.requestUrl)) {
              try {
                if (!xhr.responseType || xhr.responseType === "text") bodyExcerpt = String(xhr.responseText || "").slice(0, MAX_BODY_BYTES);
                else if (xhr.responseType === "json") bodyExcerpt = JSON.stringify(xhr.response || {}).slice(0, MAX_BODY_BYTES);
                else if (xhr.responseType === "document" && xhr.responseXML && xhr.responseXML.documentElement) {
                  bodyExcerpt = String(xhr.responseXML.documentElement.outerHTML || "").slice(0, MAX_BODY_BYTES);
                }
              } catch (_) {}
            }
            publish({
              source: "xhr-response",
              requestType: "xmlhttprequest",
              method: state.method,
              requestUrl: state.requestUrl,
              responseUrl: xhr.responseURL || state.requestUrl,
              contentType,
              contentDisposition: xhr.getResponseHeader("content-disposition") || "",
              contentLength: Number(xhr.getResponseHeader("content-length") || 0),
              contentRange: xhr.getResponseHeader("content-range") || "",
              requestHeaders: state.requestHeaders,
              bodyExcerpt
            });
          } catch (_) {}
        }, { once: true });
      }
      return nativeSend.apply(this, arguments);
    };
    xhrWrapperActive = true;
  } catch (error) { rememberError(error); }

  try {
    const nativePlay = HTMLMediaElement.prototype.play;
    HTMLMediaElement.prototype.play = function xdmMediaPlay() {
      const media = this;
      const result = nativePlay.apply(media, arguments);
      Promise.resolve(result).then(() => {
        publish({
          source: "media-play",
          requestType: "media",
          requestUrl: media.currentSrc || media.src,
          responseUrl: media.currentSrc || media.src,
          contentType: media instanceof HTMLVideoElement ? "video/unknown" : "audio/unknown"
        });
      }).catch(() => {});
      return result;
    };
    mediaPlayWrapperActive = true;
  } catch (error) { rememberError(error); }

  try {
    const observer = new PerformanceObserver(list => {
      for (const entry of list.getEntries()) {
        if (!RESOURCE_HINT_RE.test(String(entry.name || ""))) continue;
        publish({
          source: `resource:${entry.initiatorType || "unknown"}`,
          requestType: entry.initiatorType || "other",
          requestUrl: entry.name,
          responseUrl: entry.name,
          contentLength: Number(entry.transferSize || entry.encodedBodySize || 0)
        });
      }
    });
    observer.observe({ type: "resource", buffered: true });
    resourceObserverActive = true;
  } catch (error) { rememberError(error); }

  publishStatus();
})();
