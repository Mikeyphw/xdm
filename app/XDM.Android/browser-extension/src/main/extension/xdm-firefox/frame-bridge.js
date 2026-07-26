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

  let settings = {
    enabled: true,
    autoDetectPlayingVideos: true,
    siteMode: "all",
    blacklist: [],
    whitelist: [],
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

  function showLauncher(input = {}) {
    const isProbe = input.mode === "probe";
    const url = absoluteUrl(input.url) || (isProbe ? absoluteUrl(location.href) : "");
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
      scheme: globalThis.XdmExtensionConfig && globalThis.XdmExtensionConfig.xdmScheme
    };
    const links = globalThis.XdmHandoffV1.buildTargets(handoffInput);
    const launcherInput = {
      url,
      target,
      links,
      candidateCount: Math.max(1, Number(input.candidateCount || candidates.size || 1)),
      streamKind: input.streamKind || globalThis.XdmHandoffV1.mediaKind(url, contentType),
      label: isProbe ? "Test Android app links" : (input.label || "Media ready")
    };
    if (!input.force && lastOffer.key === key && Date.now() - lastOffer.at < DEDUPE_MS) {
      return globalThis.XdmLauncherUiV1.update(launcherInput);
    }
    lastOffer = { key, at: Date.now() };
    return globalThis.XdmLauncherUiV1.show(launcherInput);
  }

  // All-frame playback observations are aggregated by the background candidate store.
  function presentPlayingCandidate(candidate, label) {
    if (!candidate) return false;
    if (window.top === window) {
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
    sendBackground({
      type: PLAYBACK_TYPE,
      candidate: {
        url: candidate.url,
        contentType: candidate.contentType || "",
        headers: candidate.headers || {},
        source: candidate.source || "frame-playback",
        confidence: score(candidate),
        manifest: Boolean(candidate.manifest),
        reason: candidate.manifest ? "frame-manifest-playback" : "frame-video-playback"
      }
    });
    return true;
  }

  function evaluate(video) {
    if (!(video instanceof HTMLVideoElement)) return false;
    if (!settings.autoDetectPlayingVideos || !hostAllowed()) return false;
    if (encrypted.has(video) || video.paused || video.ended || video.readyState < 1) return false;
    const candidate = bestCandidate(video);
    return presentPlayingCandidate(candidate);
  }

  function evaluateAllVideos() {
    let foundPlaying = false;
    for (const video of document.querySelectorAll("video")) {
      if (!(video instanceof HTMLVideoElement)) continue;
      collectVideoSources(video);
      if (video.paused || video.ended || video.readyState < 1) continue;
      foundPlaying = true;
      evaluate(video);
    }
    return foundPlaying;
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

    const foundPlaying = evaluateAllVideos();
    const highConfidence = Boolean(input.manifest || input.playbackObserved || Number(input.rank || input.confidence || 0) >= 850);
    if (!foundPlaying && input.displayFallback && (input.autoOffer || highConfidence) && document.visibilityState !== "hidden") {
      showLauncher({
        url,
        title: input.title || document.title,
        label: input.manifest ? "Video manifest detected" : (input.bodyDerived ? "Video URL found in player response" : "Video stream detected"),
        headers: input.headers || {},
        contentType: input.contentType || "",
        candidateCount: Math.max(1, Number(input.candidateCount || candidates.size || 1)),
        streamKind: input.streamKind || (input.manifest ? (/\.mpd(?:$|[?#])/i.test(url) ? "dash" : "hls") : "")
      });
    }
    return true;
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
    if (event.source !== window || !event.data || event.data[PAGE_MARKER] !== true) return;
    const observation = event.data.observation && typeof event.data.observation === "object" ? event.data.observation : {};
    const responseUrl = observation.responseUrl || observation.requestUrl || "";
    const contentType = String(observation.contentType || "");
    const trusted = MEDIA_MIME_RE.test(contentType) || MANIFEST_MIME_RE.test(contentType) || MEDIA_RE.test(responseUrl) || STREAM_HINT_RE.test(responseUrl);
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
    script.onload = () => script.remove();
    (document.documentElement || document.head || document).appendChild(script);
  }
  try { injectPageSniffer(); } catch (_) {}

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

  globalThis[API_NAME] = Object.freeze({
    showManual(input) {
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
    },
    offerNetwork,
    rescan() {
      inlineScanDone = false;
      scanRelevantDom(document);
      scanInlinePlayerData();
      evaluateAllVideos();
      return true;
    },
    version: "1.1.0"
  });

  if (document.documentElement) start();
  else document.addEventListener("DOMContentLoaded", start, { once: true });
})();
