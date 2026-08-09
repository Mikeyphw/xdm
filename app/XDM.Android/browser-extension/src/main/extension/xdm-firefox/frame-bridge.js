(() => {
  const API_NAME = "__xdmInPageBridgeV1";
  if (globalThis[API_NAME]) return;

  const PAGE_MARKER = "__xdmMediaObservationV1";
  const MESSAGE_TYPE = "xdmPageObservationV1";
  const PLAYBACK_TYPE = "xdmFramePlaybackV1";
  const CANDIDATE_TTL_MS = 5 * 60 * 1000;
  const DEDUPE_MS = 10 * 60 * 1000;
  const MEDIA_RE = /\.(?:m3u8|mpd|mp4|m4v|webm|mkv|mov|avi|flv|mpeg|mpg|ogv|mp3|m4a|aac|flac|wav|ogg|opus)(?:$|[?#])/i;
  const MANIFEST_RE = /\.(?:m3u8|mpd)(?:$|[?#])/i;
  const SEGMENT_RE = /\.(?:m4s|cmfv|cmfa|ts)(?:$|[?#])/i;
  const SEGMENT_PATH_RE = /(?:^|[\/_-])(?:seg(?:ment)?|frag(?:ment)?|chunk|part|init)(?:[\/_-]?\d+)?(?:[\/_-]|\.|$)/i;
  const AD_RE = /doubleclick|googlesyndication|googleadservices|adservice|adserver|\/ads?(?:\/|\?|$)|vast|vmap|preroll|midroll|postroll|ima3/i;
  const STREAM_HINT_RE = /(?:videoplayback|video[_-]?stream|media[_-]?stream|master(?:\.|\/|\?|$)|playlist|manifest|\/stream(?:\/|\?|$)|\/playback(?:\/|\?|$)|hls|dash)/i;
  const MEDIA_MIME_RE = /^(?:video|audio)\//i;
  const MANIFEST_MIME_RE = /^(?:application\/(?:vnd\.apple\.mpegurl|x-mpegurl|dash\+xml)|audio\/(?:mpegurl|x-mpegurl))/i;
  const MAX_DOM_NODES = 3000;
  const MAX_INLINE_TEXT = 786_432;
  const PAGE_SNIFFER_STATUS_MARKER = "__xdmPageSnifferStatusV1";

  const diagnostics = {
    startedAt: Date.now(),
    pageSnifferState: "pending",
    pageSnifferError: "",
    fetchWrapperActive: false,
    xhrWrapperActive: false,
    playbackEventsSeen: 0,
    pageObservationsSeen: 0,
    networkCandidatesSeen: 0,
    topFrameOffersAttempted: 0,
    fabShowSuccesses: 0,
    fabShowFailures: 0,
    lastError: ""
  };

  let settings = {
    enabled: true,
    autoDetectPlayingVideos: true,
    siteMode: "all",
    blacklist: [],
    whitelist: [],
    showPossibleMediaCandidates: false,
    defaultTarget: (globalThis.XdmExtensionConfig && globalThis.XdmExtensionConfig.defaultTarget) || "xdm"
  };
  const candidates = new Map();
  const encrypted = new WeakSet();
  const timers = new WeakMap();
  let lastOffer = { key: "", at: 0 };
  let inlineScanDone = false;
  const seenInlineSignatures = new Set();

  function updateSettings(next) {
    if (next && typeof next === "object") Object.assign(settings, next);
  }

  browser.storage.local.get("settings").then(result => updateSettings(result.settings)).catch(() => {});
  browser.storage.onChanged.addListener(changes => {
    if (changes.settings) updateSettings(changes.settings.newValue);
  });

  function hostAllowed() {
    if (!settings.enabled) return false;
    if (settings.siteMode === "all") return true;
    let host = "";
    try { host = location.hostname.replace(/^www\./, ""); } catch (_) { return true; }
    const list = settings.siteMode === "whitelist" ? settings.whitelist : settings.blacklist;
    const matched = (Array.isArray(list) ? list : []).some(item => host === item || host.endsWith(`.${item}`));
    return settings.siteMode === "whitelist" ? matched : !matched;
  }

  function absoluteUrl(value) {
    if (!value || /^(?:blob|data|mediasource|javascript):/i.test(String(value))) return "";
    try {
      const url = new URL(String(value), document.baseURI).href;
      return /^https?:/i.test(url) ? url : "";
    } catch (_) {
      return "";
    }
  }

  function isSegment(value) {
    const url = String(value || "");
    if (!url || MANIFEST_RE.test(url)) return false;
    let path = url;
    try { path = new URL(url).pathname; } catch (_) {}
    return SEGMENT_RE.test(url) || SEGMENT_PATH_RE.test(path);
  }

  function setLastError(error) {
    diagnostics.lastError = error && error.message ? error.message : String(error || "");
    return false;
  }

  function sendBackground(message) {
    try {
      const result = browser.runtime.sendMessage(message);
      if (result && typeof result.catch === "function") result.catch(() => {});
      return true;
    } catch (error) {
      return setLastError(error);
    }
  }

  function elementMetadata(video) {
    if (!(video instanceof HTMLVideoElement)) return {};
    const duration = Number(video.duration || 0);
    return {
      durationMs: Number.isFinite(duration) && duration > 0 ? Math.floor(duration * 1000) : 0,
      thumbnailUrl: absoluteUrl(video.poster || "")
    };
  }

  function recordCandidate(value, source, bonus = 0, metadata = {}) {
    const url = absoluteUrl(value);
    if (!url || AD_RE.test(url) || isSegment(url)) return false;
    const trusted = metadata && metadata.trusted === true;
    const contentType = String(metadata.contentType || "").toLowerCase();
    if (!trusted && !MEDIA_RE.test(url) && !MEDIA_MIME_RE.test(contentType) && !MANIFEST_MIME_RE.test(contentType)) return false;
    const previous = candidates.get(url) || {};
    candidates.set(url, {
      url,
      source: source || previous.source || "resource",
      bonus: Math.max(Number(bonus || 0), Number(previous.bonus || 0)),
      confidence: Math.max(Number(metadata.confidence || 0), Number(previous.confidence || 0)),
      contentType: metadata.contentType || previous.contentType || "",
      headers: Object.assign({}, previous.headers || {}, metadata.headers || {}),
      manifest: Boolean(metadata.manifest || MANIFEST_RE.test(url) || MANIFEST_MIME_RE.test(contentType) || previous.manifest),
      contentLength: Number(metadata.contentLength || previous.contentLength || 0),
      durationMs: Number(metadata.durationMs || previous.durationMs || 0),
      thumbnailUrl: metadata.thumbnailUrl || previous.thumbnailUrl || "",
      trusted: trusted || previous.trusted === true,
      bodyDerived: Boolean(metadata.bodyDerived || previous.bodyDerived),
      at: Date.now()
    });
    while (candidates.size > 160) candidates.delete(candidates.keys().next().value);
    return true;
  }

  function collectElementUrls(element, sourcePrefix = "dom") {
    if (!(element instanceof Element)) return;
    const attributes = [
      "src", "href", "data-src", "data-url", "data-file", "data-video", "data-video-url",
      "data-stream", "data-stream-url", "data-hls", "data-m3u8", "data-mpd", "data-playlist",
      "data-manifest", "content"
    ];
    for (const name of attributes) {
      if (!element.hasAttribute(name)) continue;
      recordCandidate(element.getAttribute(name), `${sourcePrefix}:${name}`, 300);
    }
  }

  function collectVideoSources(video) {
    const metadata = Object.assign({ trusted: true }, elementMetadata(video));
    recordCandidate(video.currentSrc, "currentSrc", 650, metadata);
    recordCandidate(video.src, "video.src", 600, metadata);
    collectElementUrls(video, "video");
    for (const source of video.querySelectorAll("source[src],track[src]")) {
      recordCandidate(source.src, source.localName, 560, { trusted: true, contentType: source.type || "" });
      collectElementUrls(source, source.localName);
    }
  }

  function score(candidate) {
    let value = Math.max(Number(candidate.bonus || 0), Number(candidate.confidence || 0));
    if (candidate.manifest || MANIFEST_RE.test(candidate.url)) value += 850;
    else if (MEDIA_RE.test(candidate.url)) value += 560;
    if (/currentSrc|video\.src|source|media-play/.test(candidate.source)) value += 300;
    if (/fetch|xhr|body/.test(candidate.source)) value += 120;
    if (candidate.bodyDerived) value += 80;
    value -= Math.min(300, Math.floor((Date.now() - candidate.at) / 1000));
    return value;
  }

  function bestCandidate(video) {
    if (video) collectVideoSources(video);
    const now = Date.now();
    const values = [];
    for (const [url, item] of candidates) {
      if (now - item.at > CANDIDATE_TTL_MS) candidates.delete(url);
      else values.push(item);
    }
    values.sort((a, b) => score(b) - score(a));
    return values[0] || null;
  }

  function dependencyHealth() {
    const handoff = globalThis.XdmHandoffV1;
    const fab = globalThis.XdmLauncherUiV1;
    const config = globalThis.XdmExtensionConfig;
    const hostId = fab && fab.hostId ? fab.hostId : "";
    const host = hostId ? document.getElementById(hostId) : null;
    let launcherUrlGenerated = false;
    if (handoff && typeof handoff.buildTargets === "function") {
      try {
        const links = handoff.buildTargets({ url: absoluteUrl(location.href), pageUrl: absoluteUrl(location.href), title: document.title });
        launcherUrlGenerated = Boolean(links && (links.xdm || links.oneDm));
      } catch (error) {
        setLastError(error);
      }
    }
    const hasBody = Boolean(document.body || document.documentElement);
    const hasHandoff = Boolean(handoff && typeof handoff.buildTargets === "function");
    const hasFab = Boolean(fab && typeof fab.show === "function" && typeof fab.update === "function");
    const generatedConfig = Boolean(config && config.xdmScheme);
    return Object.freeze({
      ok: hasBody && hasHandoff && hasFab && generatedConfig && launcherUrlGenerated,
      frame: window.top === window ? "top" : "child",
      url: String(location.href || ""),
      hasBridge: true,
      hasHandoff,
      hasFab,
      hasBody,
      hostPresent: Boolean(host && host.isConnected !== false),
      generatedConfig,
      launcherUrlGenerated,
      xdmScheme: generatedConfig ? String(config.xdmScheme || "") : "",
      defaultTarget: generatedConfig ? String(config.defaultTarget || "xdm") : "",
      pageSnifferState: diagnostics.pageSnifferState,
      pageSnifferError: diagnostics.pageSnifferError,
      fetchWrapperActive: diagnostics.fetchWrapperActive,
      xhrWrapperActive: diagnostics.xhrWrapperActive,
      playbackEventsSeen: diagnostics.playbackEventsSeen,
      pageObservationsSeen: diagnostics.pageObservationsSeen,
      networkCandidatesSeen: diagnostics.networkCandidatesSeen,
      topFrameOffersAttempted: diagnostics.topFrameOffersAttempted,
      fabShowSuccesses: diagnostics.fabShowSuccesses,
      fabShowFailures: diagnostics.fabShowFailures,
      lastError: diagnostics.lastError
    });
  }

  function showLauncher(input = {}) {
    diagnostics.topFrameOffersAttempted += 1;
    try {
      const health = dependencyHealth();
      if (!health.hasHandoff) throw new Error("Handoff script is unavailable in the page frame.");
      if (!health.hasFab) throw new Error("FAB renderer is unavailable in the page frame.");
      if (!health.hasBody) throw new Error("The page has no mountable document root.");
      if (!health.generatedConfig) throw new Error("Generated extension configuration is unavailable.");

      const isProbe = input.mode === "probe";
      const url = absoluteUrl(input.url) || (isProbe ? absoluteUrl(location.href) : "");
      if (!url) throw new Error("No safe HTTP or HTTPS launcher URL is available.");
      const key = `${isProbe ? "probe" : url}
${location.href}`;
      const target = settings.defaultTarget === "1dm" || settings.defaultTarget === "ask"
        ? settings.defaultTarget
        : "xdm";
      const contentType = input.contentType || "";
      const handoffInput = {
        url,
        title: input.title || document.title,
        mimeType: contentType,
        pageUrl: location.href,
        contentLength: input.contentLength,
        durationMs: input.durationMs,
        thumbnailUrl: input.thumbnailUrl,
        stableMediaId: input.stableMediaId,
        sessionRevision: input.sessionRevision,
        frameUrl: input.frameUrl,
        scheme: globalThis.XdmExtensionConfig && globalThis.XdmExtensionConfig.xdmScheme
      };
      const builtLinks = globalThis.XdmHandoffV1.buildTargets(handoffInput);
      const links = Object.freeze({
        xdm: input.prebuiltXdmLink || builtLinks.xdm,
        oneDm: builtLinks.oneDm,
        filename: builtLinks.filename,
      });
      if (!links || (!links.xdm && !links.oneDm)) throw new Error("Launcher URL generation failed.");
      const launcherInput = {
        url,
        target,
        links,
        candidateCount: Math.max(1, Number(input.candidateCount || candidates.size || 1)),
        streamKind: input.streamKind || globalThis.XdmHandoffV1.mediaKind(url, contentType),
        label: isProbe ? "Test Android app links" : (input.label || "Media ready")
      };
      const rendered = !input.force && lastOffer.key === key && Date.now() - lastOffer.at < DEDUPE_MS
        ? globalThis.XdmLauncherUiV1.update(launcherInput)
        : globalThis.XdmLauncherUiV1.show(launcherInput);
      if (!rendered) throw new Error("FAB renderer declined the launcher payload.");
      lastOffer = { key, at: Date.now() };
      diagnostics.fabShowSuccesses += 1;
      diagnostics.lastError = "";
      return true;
    } catch (error) {
      diagnostics.fabShowFailures += 1;
      return setLastError(error);
    }
  }

  // All-frame playback observations are aggregated by the background candidate store.
  function presentPlayingCandidate(candidate, label) {
    if (!candidate) return false;
    const handedToBackground = sendBackground({
      type: PLAYBACK_TYPE,
      candidate: {
        url: candidate.url,
        contentType: candidate.contentType || "",
        headers: candidate.headers || {},
        source: candidate.source || (window.top === window ? "top-playback" : "frame-playback"),
        confidence: score(candidate),
        manifest: Boolean(candidate.manifest),
        reason: candidate.manifest ? "frame-manifest-playback" : "frame-video-playback"
      }
    });
    // Background owns the Phase 59-61 session aggregation and encrypted handoff.
    // Only fall back to the legacy top-frame launcher when runtime messaging itself fails.
    if (handedToBackground || window.top !== window) return true;
    return showLauncher({
      url: candidate.url,
      title: document.title,
      label: label || (candidate.manifest ? "Playing stream detected" : "Playing video detected"),
      headers: candidate.headers || {},
      contentType: candidate.contentType || "",
      candidateCount: Math.max(1, candidates.size),
      streamKind: candidate.manifest ? (/\.mpd(?:$|[?#])/i.test(candidate.url) ? "dash" : "hls") : ""
    });
  }

  function evaluate(video) {
    if (!(video instanceof HTMLVideoElement)) return false;
    if (!settings.autoDetectPlayingVideos || !hostAllowed()) return false;
    if (encrypted.has(video) || video.paused || video.ended || video.readyState < 1) return false;
    const candidate = bestCandidate(video);
    return presentPlayingCandidate(candidate);
  }

  function evaluateAllVideos() {
    let seenPlaying = false;
    let offered = false;
    for (const video of document.querySelectorAll("video")) {
      if (!(video instanceof HTMLVideoElement)) continue;
      collectVideoSources(video);
      if (video.paused || video.ended || video.readyState < 1) continue;
      seenPlaying = true;
      offered = evaluate(video) || offered;
    }
    return Object.freeze({ seenPlaying, offered });
  }

  function offerNetwork(input = {}) {
    const url = absoluteUrl(input.url);
    if (!url || !settings.enabled || !settings.autoDetectPlayingVideos || !hostAllowed()) return false;
    recordCandidate(url, `network:${input.reason || input.requestType || "response"}`, Number(input.confidence || input.rank || 700), {
      trusted: true,
      confidence: Number(input.confidence || input.rank || 700),
      contentType: input.contentType || "",
      headers: input.headers || {},
      manifest: Boolean(input.manifest),
      bodyDerived: Boolean(input.bodyDerived)
    });

    diagnostics.networkCandidatesSeen += 1;
    const playback = evaluateAllVideos();
    const possible = input.quality === "possible";
    const highConfidence = Boolean(!possible && (input.manifest || input.playbackObserved || Number(input.rank || input.confidence || 0) >= 850));
    const possibleAllowed = Boolean(possible && settings.showPossibleMediaCandidates === true && Number(input.rank || input.confidence || 0) >= 700);
    if (!playback.offered && input.displayFallback && (input.autoOffer || highConfidence || possibleAllowed) && document.visibilityState !== "hidden") {
      return showLauncher({
        url,
        title: input.title || document.title,
        label: possible ? "Possible media found" : (input.manifest ? "Video manifest detected" : (input.bodyDerived ? "Video URL found in player response" : "Video stream detected")),
        headers: input.headers || {},
        contentType: input.contentType || "",
        contentLength: input.contentLength || 0,
        durationMs: input.durationMs || 0,
        thumbnailUrl: input.thumbnailUrl || "",
        stableMediaId: input.stableMediaId || "",
        sessionRevision: input.sessionRevision || Date.now(),
        frameUrl: input.frameUrl || "",
        prebuiltXdmLink: input.prebuiltXdmLink || "",
        candidateCount: Math.max(1, Number(input.candidateCount || candidates.size || 1)),
        streamKind: input.streamKind || (input.manifest ? (/\.mpd(?:$|[?#])/i.test(url) ? "dash" : "hls") : "")
      });
    }
    return playback.offered;
  }

  function schedule(video) {
    if (!(video instanceof HTMLVideoElement)) return;
    const old = timers.get(video);
    if (old) clearTimeout(old);
    const timer = setTimeout(() => {
      timers.delete(video);
      evaluate(video);
    }, 500);
    timers.set(video, timer);
  }

  function onMediaEvent(event) {
    diagnostics.playbackEventsSeen += 1;
    const video = event.target;
    if (!(video instanceof HTMLVideoElement)) return;
    if (event.type === "encrypted") {
      encrypted.add(video);
      return;
    }
    if (event.type === "timeupdate" && video.currentTime < 0.15) return;
    collectVideoSources(video);
    schedule(video);
  }

  for (const type of ["encrypted", "play", "playing", "loadedmetadata", "loadeddata", "canplay", "timeupdate", "durationchange"]) {
    document.addEventListener(type, onMediaEvent, true);
  }

  window.addEventListener("message", event => {
    if (event.source !== window || !event.data) return;
    if (event.data[PAGE_SNIFFER_STATUS_MARKER] === true) {
      const status = event.data.status && typeof event.data.status === "object" ? event.data.status : {};
      diagnostics.pageSnifferState = status.active === false ? "failed" : "active";
      diagnostics.pageSnifferError = String(status.lastError || "");
      diagnostics.fetchWrapperActive = Boolean(status.fetchWrapperActive);
      diagnostics.xhrWrapperActive = Boolean(status.xhrWrapperActive);
      return;
    }
    if (event.data[PAGE_MARKER] !== true) return;
    diagnostics.pageObservationsSeen += 1;
    const observation = event.data.observation && typeof event.data.observation === "object" ? event.data.observation : {};
    const responseUrl = observation.responseUrl || observation.requestUrl || "";
    const contentType = String(observation.contentType || "");
    const trusted = MEDIA_MIME_RE.test(contentType) || MANIFEST_MIME_RE.test(contentType) || MEDIA_RE.test(responseUrl);
    if (trusted) {
      recordCandidate(responseUrl, observation.source || "page-response", 720, {
        trusted: true,
        contentType,
        headers: observation.requestHeaders || {},
        manifest: MANIFEST_MIME_RE.test(contentType) || MANIFEST_RE.test(responseUrl)
      });
    }
    sendBackground({ type: MESSAGE_TYPE, observation });
    evaluateAllVideos();
  });

  try {
    const observer = new PerformanceObserver(list => {
      for (const entry of list.getEntries()) {
        recordCandidate(entry.name, `resource:${entry.initiatorType || "unknown"}`, 80);
      }
    });
    observer.observe({ type: "resource", buffered: true });
  } catch (_) {}

  function injectPageSniffer() {
    if (document.querySelector('script[data-xdm-page-sniffer="v1"]')) return;
    const script = document.createElement("script");
    script.src = browser.runtime.getURL("page-sniffer.js");
    script.async = false;
    script.dataset.xdmPageSniffer = "v1";
    script.onload = () => {
      diagnostics.pageSnifferState = diagnostics.pageSnifferState === "active" ? "active" : "loaded";
      script.remove();
    };
    script.onerror = () => {
      diagnostics.pageSnifferState = "failed";
      diagnostics.pageSnifferError = "The page sniffer script was blocked or could not load.";
      script.remove();
    };
    (document.documentElement || document.head || document).appendChild(script);
  }
  try { injectPageSniffer(); } catch (error) {
    diagnostics.pageSnifferState = "failed";
    diagnostics.pageSnifferError = error && error.message ? error.message : String(error);
  }

  function scanRelevantDom(root = document) {
    const selector = [
      "video", "source[src]", "audio", "track[src]",
      "link[href][as='video']", "link[href][as='audio']", "link[href][type*='mpegurl']", "link[href][type*='dash']",
      "meta[property='og:video']", "meta[property='og:video:url']", "meta[property='og:audio']",
      "[data-video-url]", "[data-stream-url]", "[data-m3u8]", "[data-mpd]", "[data-manifest]", "[data-playlist]"
    ].join(",");
    let count = 0;
    for (const element of root.querySelectorAll ? root.querySelectorAll(selector) : []) {
      if (++count > MAX_DOM_NODES) break;
      if (element instanceof HTMLVideoElement) collectVideoSources(element);
      else collectElementUrls(element, `dom:${element.localName}`);
    }
  }

  function sendInlineBody(text, source) {
    const value = String(text || "");
    if (!value || !/(?:#EXTM3U|<\s*MPD(?:\s|>)|m3u8|mpd|videoplayback|playlist|manifest|video|stream|\.mp4)/i.test(value)) return false;
    const excerpt = value.slice(0, MAX_INLINE_TEXT);
    const signature = `${excerpt.length}:${excerpt.slice(0, 180)}:${excerpt.slice(-180)}`;
    if (seenInlineSignatures.has(signature)) return false;
    seenInlineSignatures.add(signature);
    while (seenInlineSignatures.size > 100) seenInlineSignatures.delete(seenInlineSignatures.values().next().value);
    sendBackground({
      type: MESSAGE_TYPE,
      observation: {
        source: source || "dom-inline-data",
        requestType: "document",
        requestUrl: location.href,
        responseUrl: location.href,
        contentType: "text/html",
        bodyExcerpt: excerpt,
        pageUrl: location.href,
        requestHeaders: {}
      }
    });
    return true;
  }

  function scanInlinePlayerData() {
    if (inlineScanDone) return;
    inlineScanDone = true;
    let bodyExcerpt = "";
    const nodes = document.querySelectorAll("script:not([src]),script[type='application/json'],script[type='application/ld+json'],script[type='application/javascript'],script[type='text/javascript']");
    for (const node of nodes) {
      const text = String(node.textContent || "");
      if (!text || !/(?:m3u8|mpd|videoplayback|playlist|manifest|video|stream|\.mp4)/i.test(text)) continue;
      const remaining = MAX_INLINE_TEXT - bodyExcerpt.length;
      if (remaining <= 0) break;
      bodyExcerpt += `\n${text.slice(0, remaining)}`;
    }
    if (bodyExcerpt) sendInlineBody(bodyExcerpt, "dom-inline-data");
  }

  const mutationObserver = new MutationObserver(records => {
    for (const record of records) {
      if (record.type === "attributes" && record.target instanceof Element) {
        collectElementUrls(record.target, "mutated");
        if (record.target instanceof HTMLVideoElement) schedule(record.target);
        continue;
      }
      for (const node of record.addedNodes) {
        if (!(node instanceof Element)) continue;
        if (node instanceof HTMLVideoElement) {
          collectVideoSources(node);
          if (!node.paused) schedule(node);
        } else {
          collectElementUrls(node, "added");
          if (node instanceof HTMLScriptElement && !node.src) sendInlineBody(node.textContent || "", "dynamic-inline-script");
        }
        scanRelevantDom(node);
      }
    }
  });

  function start() {
    mutationObserver.observe(document.documentElement || document, {
      childList: true,
      subtree: true,
      attributes: true,
      attributeFilter: ["src", "href", "data-src", "data-url", "data-file", "data-video", "data-video-url", "data-stream", "data-stream-url", "data-hls", "data-m3u8", "data-mpd", "data-playlist", "data-manifest", "content"]
    });
    scanRelevantDom(document);
    for (const video of document.querySelectorAll("video")) {
      collectVideoSources(video);
      if (!video.paused) schedule(video);
    }
    const runInlineScan = () => {
      scanRelevantDom(document);
      scanInlinePlayerData();
      evaluateAllVideos();
    };
    if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", runInlineScan, { once: true });
    else setTimeout(runInlineScan, 0);
    window.addEventListener("load", () => setTimeout(runInlineScan, 350), { once: true });
  }

  function showManual(input) {
    const value = input && typeof input === "object" ? input : {};
    return showLauncher({
      url: value.url || "",
      title: value.title || document.title,
      label: value.label || "",
      mode: value.mode || "",
      headers: value.headers || {},
      candidateCount: value.candidateCount || 1,
      streamKind: value.streamKind || "",
      force: true
    });
  }

  globalThis[API_NAME] = Object.freeze({
    showManual,
    showManualWithDiagnostics(input) {
      const shown = showManual(input);
      return Object.freeze({ shown, health: dependencyHealth() });
    },
    health: dependencyHealth,
    offerNetwork,
    rescan() {
      inlineScanDone = false;
      scanRelevantDom(document);
      scanInlinePlayerData();
      const playback = evaluateAllVideos();
      return Object.freeze({ ok: true, playback, health: dependencyHealth() });
    },
    version: "1.2.0"
  });

  if (document.documentElement) start();
  else document.addEventListener("DOMContentLoaded", start, { once: true });
})();
