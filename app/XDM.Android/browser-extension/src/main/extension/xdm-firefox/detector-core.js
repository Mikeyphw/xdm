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
  const QUALITY_STRONG = "strong";
  const QUALITY_POSSIBLE = "possible";
  const QUALITY_REJECTED = "rejected";

  function stableMediaIdentity(value) {
    try {
      const url = new URL(String(value || ""));
      url.hash = "";
      url.search = "";
      return `${url.origin}${url.pathname}`.toLowerCase();
    } catch (_) {
      return String(value || "").split(/[?#]/, 1)[0].toLowerCase();
    }
  }

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

  function hasMediaDisposition(value) {
    return /(?:^|;|\s)filename\*?\s*=.*\.(?:m3u8|mpd|mp4|m4v|webm|mkv|mov|avi|flv|mpeg|mpg|ogv|mp3|m4a|aac|flac|wav|ogg|opus)(?:["'\s;]|$)/i.test(String(value || ""));
  }

  function hasRangeContext(range, length) {
    return /bytes\s+\d+-\d+\/\d+/i.test(String(range || "")) || Number(length || 0) >= 1024 * 1024;
  }

  function makeRejected(reason) {
    return { accept: false, reason: reason || "none", quality: QUALITY_REJECTED, autoOffer: false };
  }

  function makeAccepted(reason, confidence, quality, extra = {}) {
    const bucket = quality || (confidence >= 830 ? QUALITY_STRONG : QUALITY_POSSIBLE);
    return Object.assign({
      accept: true,
      reason,
      confidence,
      quality: bucket,
      autoOffer: bucket === QUALITY_STRONG && confidence >= 830
    }, extra);
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
    const disposition = String(input && input.contentDisposition || "");
    const range = String(input && input.contentRange || "");

    if (!/^https?:/i.test(url)) return makeRejected("scheme");
    if (isLikelyAd(url)) return makeRejected("ad");
    if (isLikelySegment(url)) return makeRejected("segment");

    const manifest = isManifest(url, mime);
    const mediaMime = mime.startsWith("video/") || mime.startsWith("audio/");
    const extension = MEDIA_EXT_RE.test(url);
    const streamHint = STREAM_HINT_RE.test(url);
    const mediaRequest = type === "media";
    const xhrLike = type === "xmlhttprequest" || type === "other" || type === "fetch";
    const octet = mime === "application/octet-stream" || mime === "binary/octet-stream";
    const dispositionMedia = hasMediaDisposition(disposition);
    const rangeContext = hasRangeContext(range, length);

    let result = null;

    if (manifest) {
      result = makeAccepted("manifest", 1100, QUALITY_STRONG, { manifest: true, mediaMime: false });
    } else if (mediaMime) {
      // MIME remains authoritative, but the quality bucket makes later UI and
      // dispatch decisions explicit instead of trusting every stream-shaped URL.
      result = makeAccepted(mediaRequest ? "media-mime" : "xhr-media-mime", mediaRequest ? 980 : 930, QUALITY_STRONG, { manifest: false, mediaMime: true });
    } else if (extension) {
      result = makeAccepted("media-extension", mediaRequest ? 900 : (xhrLike ? 860 : 830), QUALITY_STRONG, { manifest: false, mediaMime: false });
    } else if (dispositionMedia) {
      result = makeAccepted("media-disposition", rangeContext ? 880 : 840, QUALITY_STRONG, { manifest: false, mediaMime: false });
    } else if (octet && mediaRequest && rangeContext) {
      result = makeAccepted("media-octet", 820, QUALITY_STRONG, { manifest: false, mediaMime: false });
    } else if (octet && (streamHint || mediaRequest || rangeContext)) {
      result = makeAccepted("possible-octet", streamHint ? 720 : 680, QUALITY_POSSIBLE, { manifest: false, mediaMime: false });
    } else if (streamHint && mediaRequest) {
      result = makeAccepted("possible-stream-hint", 660, QUALITY_POSSIBLE, { manifest: false, mediaMime: false });
    }

    if (!result) return makeRejected("none");
    if (length > 1024 * 1024) result.confidence += 20;
    if (length > 8 * 1024 * 1024) result.confidence += 20;
    result.autoOffer = result.quality === QUALITY_STRONG && (result.manifest || result.mediaMime || mediaRequest || result.confidence >= 830);
    return result;
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

  function candidateSignal(url, context) {
    const sample = String(context || "");
    if (isManifest(url, "")) return { confidence: 1050, quality: QUALITY_STRONG, reason: "body-manifest-url" };
    if (MANIFEST_KEY_RE.test(sample) && STREAM_HINT_RE.test(url)) return { confidence: 980, quality: QUALITY_STRONG, reason: "body-manifest-url" };
    if (MEDIA_EXT_RE.test(url)) return { confidence: 880, quality: QUALITY_STRONG, reason: "body-media-url" };
    if (STRONG_MEDIA_KEY_RE.test(sample) && STREAM_HINT_RE.test(url)) return { confidence: 720, quality: QUALITY_POSSIBLE, reason: "body-possible-media-url" };
    return null;
  }

  function candidateConfidence(url, context) {
    const signal = candidateSignal(url, context);
    return signal ? signal.confidence : 0;
  }

  function extractCandidatesFromText(text, baseUrl) {
    const body = decodeEscapes(String(text || "").slice(0, MAX_BODY_CHARS));
    const results = new Map();

    function add(raw, context) {
      if (results.size >= MAX_EXTRACTED) return;
      const url = resolveUrl(raw, baseUrl);
      if (!url || isLikelyAd(url) || isLikelySegment(url)) return;
      const signal = candidateSignal(url, context);
      if (!signal) return;
      const previous = results.get(url);
      if (!previous || signal.confidence > previous.confidence) {
        results.set(url, {
          url,
          confidence: signal.confidence,
          quality: signal.quality,
          reason: signal.reason
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
    if (candidate && candidate.quality === QUALITY_POSSIBLE) score -= 120;
    if (isManifest(url, candidate && candidate.contentType)) score += 120;
    if (MEDIA_EXT_RE.test(url)) score += 40;
    const age = Math.max(0, Date.now() - Number(candidate && candidate.at || 0));
    score -= Math.min(260, Math.floor(age / 1000));
    return score;
  }

  globalThis.XdmDetectorCoreV1 = Object.freeze({
    stableMediaIdentity,
    normalizeMime,
    isManifest,
    isLikelyAd,
    isLikelySegment,
    isInspectableTextMime,
    classifyResponse,
    extractCandidatesFromText,
    analyzeBody,
    rankCandidate,
    resolveUrl,
    candidateConfidence,
    hasMediaDisposition,
    hasRangeContext
  });
})();
