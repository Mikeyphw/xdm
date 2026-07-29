(() => {
  const MEDIA_EXT_RE = /\.(?:m3u8|mpd|mp4|m4v|webm|mkv|mov|avi|flv|mpeg|mpg|ogv|mp3|m4a|aac|flac|wav|ogg|opus)(?:$|[?#])/i;
  const MANIFEST_EXT_RE = /\.(?:m3u8|mpd)(?:$|[?#])/i;
  const SEGMENT_EXT_RE = /\.(?:m4s|cmfv|cmfa|ts)(?:$|[?#])/i;
  const SEGMENT_PATH_RE = /(?:^|[\/_-])(?:seg(?:ment)?|frag(?:ment)?|chunk|part|init)(?:[\/_-]?\d+)?(?:[\/_-]|\.|$)/i;
  const AD_RE = /doubleclick|googlesyndication|googleadservices|adservice|adserver|\/ads?(?:\/|\?|$)|vast|vmap|preroll|midroll|postroll|ima3/i;
  const STREAM_HINT_RE = /(?:videoplayback|video[_-]?stream|media[_-]?stream|master(?:\.|\/|\?|$)|playlist|manifest|\/stream(?:\/|\?|$)|\/playback(?:\/|\?|$)|hls|dash)/i;
  const TEXT_MIME_RE = /^(?:text\/|application\/(?:json|ld\+json|javascript|x-javascript|xml|xhtml\+xml|rss\+xml|atom\+xml|vnd\.apple\.mpegurl|x-mpegurl|dash\+xml))/i;
  const MANIFEST_KEY_RE = /(?:^|["'\s{,])(?:manifest|playlist|hls|dash|m3u8|mpd)(?:url|src)?["'\s]*[:=]/i;
  const STRONG_MEDIA_KEY_RE = /(?:^|["'\s{,])(?:file|video|audio|media|stream|mp4)(?:url|src)?["'\s]*[:=]/i;
  const MAX_BODY_CHARS = 786_432;
  const MAX_EXTRACTED = 80;

  function normalizeMime(value) {
    return String(value || "").split(";", 1)[0].trim().toLowerCase();
  }

  function isManifest(url, mime) {
    const type = normalizeMime(mime);
    return MANIFEST_EXT_RE.test(String(url || "")) ||
      type === "application/vnd.apple.mpegurl" ||
      type === "application/x-mpegurl" ||
      type === "audio/mpegurl" ||
      type === "audio/x-mpegurl" ||
      type === "application/dash+xml";
  }

  function isLikelyAd(url) {
    return AD_RE.test(String(url || ""));
  }

  function isLikelySegment(url) {
    const value = String(url || "");
    if (!value || isManifest(value, "")) return false;
    let pathname = value;
    try { pathname = new URL(value).pathname; } catch (_) {}
    return SEGMENT_EXT_RE.test(value) || SEGMENT_PATH_RE.test(pathname);
  }

  function isInspectableTextMime(mime) {
    const type = normalizeMime(mime);
    return !type || TEXT_MIME_RE.test(type);
  }

  function classifyResponse(input) {
    const url = String(input && input.url || "");
    const type = String(input && input.type || "").toLowerCase();
    const mime = normalizeMime(input && input.contentType);
    const length = Number(input && input.contentLength || 0);

    if (!/^https?:/i.test(url)) return { accept: false, reason: "scheme" };
    if (isLikelyAd(url)) return { accept: false, reason: "ad" };
    if (isLikelySegment(url)) return { accept: false, reason: "segment" };

    const manifest = isManifest(url, mime);
    const mediaMime = mime.startsWith("video/") || mime.startsWith("audio/");
    const extension = MEDIA_EXT_RE.test(url);
    const streamHint = STREAM_HINT_RE.test(url);
    const mediaRequest = type === "media";
    const xhrLike = type === "xmlhttprequest" || type === "other" || type === "fetch";
    const octet = mime === "application/octet-stream" || mime === "binary/octet-stream";

    let accept = false;
    let confidence = 0;
    let reason = "none";

    if (manifest) {
      accept = true;
      confidence = 1100;
      reason = "manifest";
    } else if (mediaMime) {
      // 1DM+ treats MIME as authoritative regardless of whether the player used
      // media, fetch, XHR, or an extensionless endpoint.
      accept = true;
      confidence = mediaRequest ? 980 : 930;
      reason = mediaRequest ? "media-mime" : "xhr-media-mime";
    } else if (extension) {
      accept = true;
      confidence = mediaRequest ? 880 : (xhrLike ? 820 : 760);
      reason = "media-extension";
    } else if (octet && (mediaRequest || streamHint || length >= 1024 * 1024)) {
      accept = true;
      confidence = streamHint ? 810 : (mediaRequest ? 760 : 700);
      reason = "media-octet";
    } else if (streamHint && mediaRequest) {
      accept = true;
      confidence = 680;
      reason = "stream-hint";
    }

    if (!accept) return { accept: false, reason };
    if (length > 1024 * 1024) confidence += 20;
    if (length > 8 * 1024 * 1024) confidence += 20;

    return {
      accept: true,
      reason,
      confidence,
      manifest,
      mediaMime,
      autoOffer: manifest || mediaMime || mediaRequest || confidence >= 830
    };
  }

  function decodeEscapes(value) {
    return String(value || "")
      .replace(/\\+u([0-9a-f]{4})/gi, (_, hex) => String.fromCharCode(parseInt(hex, 16)))
      .replace(/\\+\//g, "/")
      .replace(/&amp;|&#0*38;|&#x0*26;/gi, "&")
      .replace(/&quot;|&#0*34;|&#x0*22;/gi, '"');
  }

  function resolveUrl(value, baseUrl) {
    let cleaned = decodeEscapes(value)
      .replace(/^["'`\s]+|["'`\s,;)}\]]+$/g, "")
      .trim();
    if (/^https?%3a%2f%2f/i.test(cleaned)) {
      try { cleaned = decodeURIComponent(cleaned); } catch (_) {}
    }
    if (!cleaned || /^(?:blob|data|javascript|mediasource):/i.test(cleaned)) return "";
    try {
      const url = new URL(cleaned, baseUrl || undefined).href;
      return /^https?:/i.test(url) ? url : "";
    } catch (_) {
      return "";
    }
  }

  function candidateConfidence(url, context) {
    if (isManifest(url, "")) return 1050;
    if (MEDIA_EXT_RE.test(url)) return 880;
    if (MANIFEST_KEY_RE.test(context || "")) return 980;
    if (STRONG_MEDIA_KEY_RE.test(context || "") && STREAM_HINT_RE.test(url)) return 860;
    if (STRONG_MEDIA_KEY_RE.test(context || "") && MEDIA_EXT_RE.test(url)) return 840;
    return 0;
  }

  function extractCandidatesFromText(text, baseUrl) {
    const body = decodeEscapes(String(text || "").slice(0, MAX_BODY_CHARS));
    const results = new Map();

    function add(raw, context) {
      if (results.size >= MAX_EXTRACTED) return;
      const url = resolveUrl(raw, baseUrl);
      if (!url || isLikelyAd(url) || isLikelySegment(url)) return;
      const confidence = candidateConfidence(url, context);
      if (!confidence) return;
      const previous = results.get(url);
      if (!previous || confidence > previous.confidence) {
        results.set(url, {
          url,
          confidence,
          reason: isManifest(url, "") ? "body-manifest-url" : "body-media-url"
        });
      }
    }

    // Player configuration keys often contain extensionless or relative URLs.
    const keyedValueRe = /["'`](manifest|playlist|hls|dash|m3u8|mpd|file|video|audio|media|stream|mp4)(?:Url|URL|_url|_src|url|src)?["'`]\s*[:=]\s*["'`]([^"'`\r\n]{1,2200})["'`]/gi;
    let match;
    while ((match = keyedValueRe.exec(body)) && results.size < MAX_EXTRACTED) {
      const start = Math.max(0, match.index - 40);
      const end = Math.min(body.length, keyedValueRe.lastIndex + 40);
      add(match[2], body.slice(start, end));
    }

    // Absolute and protocol-relative URLs, including JSON-escaped forms.
    const absoluteRe = /(?:https?:\\?\/\\?\/|\/\/)[^\s"'<>\\]+/gi;
    while ((match = absoluteRe.exec(body)) && results.size < MAX_EXTRACTED) {
      const start = Math.max(0, match.index - 90);
      const end = Math.min(body.length, absoluteRe.lastIndex + 90);
      add(match[0], body.slice(start, end));
    }

    const encodedAbsoluteRe = /https?%3a%2f%2f[^\s"'<>\\]+/gi;
    while ((match = encodedAbsoluteRe.exec(body)) && results.size < MAX_EXTRACTED) {
      const start = Math.max(0, match.index - 90);
      const end = Math.min(body.length, encodedAbsoluteRe.lastIndex + 90);
      add(match[0], body.slice(start, end));
    }

    // Quoted relative paths that look like manifests or media resources.
    const relativeRe = /["'`]((?:\.{0,2}\/|\/)[^"'`\s<>]{1,1400}(?:\.m3u8|\.mpd|\.mp4|\.m4v|\.webm|\.mkv|\.mov|\.mp3|\.m4a|\.aac|\.ogg|\/manifest|\/playlist|\/videoplayback|\/stream)[^"'`\s<>]*)["'`]/gi;
    while ((match = relativeRe.exec(body)) && results.size < MAX_EXTRACTED) {
      const start = Math.max(0, match.index - 90);
      const end = Math.min(body.length, relativeRe.lastIndex + 90);
      add(match[1], body.slice(start, end));
    }

    return [...results.values()].sort((a, b) => b.confidence - a.confidence);
  }

  function analyzeBody(input) {
    const text = String(input && input.text || "").slice(0, MAX_BODY_CHARS);
    const trimmed = text.trimStart();
    const contentType = normalizeMime(input && input.contentType);
    const responseUrl = String(input && input.responseUrl || "");
    const hlsBody = /^#EXTM3U(?:\r?\n|$)/i.test(trimmed);
    const dashBody = /<\s*MPD(?:\s|>)/i.test(trimmed) || contentType === "application/dash+xml";
    const manifestBody = hlsBody || dashBody;
    const candidates = extractCandidatesFromText(text, responseUrl);

    return {
      manifestBody,
      hlsBody,
      dashBody,
      candidates,
      inspectable: isInspectableTextMime(contentType)
    };
  }

  function rankCandidate(candidate) {
    let score = Number(candidate && candidate.confidence || 0);
    const url = String(candidate && candidate.url || "");
    if (candidate && candidate.manifest) score += 180;
    if (candidate && candidate.bodyDerived) score += 70;
    if (candidate && candidate.playbackObserved) score += 90;
    if (isManifest(url, candidate && candidate.contentType)) score += 120;
    if (MEDIA_EXT_RE.test(url)) score += 40;
    const age = Math.max(0, Date.now() - Number(candidate && candidate.at || 0));
    score -= Math.min(260, Math.floor(age / 1000));
    return score;
  }

  globalThis.XdmDetectorCoreV1 = Object.freeze({
    normalizeMime,
    isManifest,
    isLikelyAd,
    isLikelySegment,
    isInspectableTextMime,
    classifyResponse,
    extractCandidatesFromText,
    analyzeBody,
    rankCandidate,
    resolveUrl
  });
})();
