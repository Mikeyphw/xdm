(() => {
  const CORE = globalThis.XdmDetectorCoreV1;
  if (!CORE || globalThis.__xdmNetworkObserverV1) return;
  globalThis.__xdmNetworkObserverV1 = true;

  const STATUS_KEY = "xdmNetworkStatusV1";
  const DIAGNOSTICS_KEY = "xdmNetworkDiagnosticsV1";
  const MESSAGE_TYPE = "xdmPageObservationV1";
  const PLAYBACK_TYPE = "xdmFramePlaybackV1";
  const HEADER_ALLOWLIST = new Set(["authorization", "cookie", "referer", "user-agent", "origin", "accept", "range"]);
  const MAX_DIAGNOSTIC_TABS = 40;
  const MAX_CANDIDATES_PER_TAB = 160;
  const CANDIDATE_TTL_MS = 5 * 60 * 1000;
  const SAME_URL_SUPPRESS_MS = 90 * 1000;
  const AUTO_OFFER_THRESHOLD = 850;
  const POSSIBLE_OFFER_THRESHOLD = 700;
  const MAX_HANDOFF_CANDIDATES = 24;

  const capturedHeaders = new Map();
  const tabDispatchSessions = new Map();
  const candidateStore = new globalThis.XdmCandidateStoreV1({ maxPerTab: MAX_CANDIDATES_PER_TAB, ttlMs: CANDIDATE_TTL_MS });
  const dispatchTimers = new Map();
  const lastDispatchedByTab = new Map();
  const diagnosticCounters = new Map();

  let settings = {
    enabled: true,
    autoDetectPlayingVideos: true,
    siteMode: "all",
    blacklist: [],
    whitelist: [],
    showPossibleMediaCandidates: false
  };

  function updateSettings(next) {
    if (next && typeof next === "object") Object.assign(settings, next);
  }

  async function publishStatus(extra = {}) {
    try {
      const previous = await browser.storage.local.get(STATUS_KEY);
      await browser.storage.local.set({
        [STATUS_KEY]: Object.assign({
          active: true,
          version: "1.2.0",
          startedAt: Date.now(),
          lastError: ""
        }, previous[STATUS_KEY] || {}, extra)
      });
    } catch (_) {}
  }

  publishStatus();
  browser.storage.local.get("settings").then(result => updateSettings(result.settings)).catch(error => {
    publishStatus({ lastError: error && error.message ? error.message : String(error), lastErrorAt: Date.now() });
  });
  browser.storage.onChanged.addListener(changes => {
    if (changes.settings) updateSettings(changes.settings.newValue);
  });

  function headerValue(headers, name) {
    const wanted = String(name || "").toLowerCase();
    for (const header of headers || []) {
      if (String(header.name || "").toLowerCase() === wanted) return header.value || "";
    }
    return "";
  }

  function captureUsefulHeaders(requestHeaders) {
    const result = {};
    for (const header of requestHeaders || []) {
      const name = String(header.name || "").toLowerCase();
      if (!HEADER_ALLOWLIST.has(name)) continue;
      const value = String(header.value || "").replace(/[\r\n]+/g, " ").trim();
      if (value) result[name] = value.slice(0, 8192);
    }
    return result;
  }

  function headerObservation(kind, headers, unavailableReason = "") {
    return Object.freeze({ kind, headers: sanitizeHeaderObject(headers), unavailableReason });
  }

  function capturedHeaderPayload(captured) {
    if (!captured) {
      return Object.freeze({
        proposedHeaders: headerObservation("Unavailable", {}, "webRequest headers unavailable"),
        finalHeaders: headerObservation("Unavailable", {}, "onSendHeaders unavailable"),
      });
    }
    return Object.freeze({
      proposedHeaders: headerObservation("ProposedBeforeSend", captured.proposedHeaders || {}),
      finalHeaders: captured.finalHeadersAvailable
        ? headerObservation("FinalSent", captured.finalHeaders || {})
        : headerObservation("Unavailable", {}, captured.finalUnavailableReason || "onSendHeaders unavailable"),
      requestGeneration: Number(captured.requestGeneration || captured.at || Date.now()),
      frameUrl: captured.frameUrl || "",
      tabUrl: captured.tabUrl || "",
    });
  }

  function sanitizeHeaderObject(value) {
    const result = {};
    for (const [rawName, rawValue] of Object.entries(value && typeof value === "object" ? value : {})) {
      const name = String(rawName || "").trim().toLowerCase();
      if (!HEADER_ALLOWLIST.has(name)) continue;
      const headerValue = String(rawValue == null ? "" : rawValue).replace(/[\r\n]+/g, " ").trim();
      if (headerValue) result[name] = headerValue.slice(0, 8192);
    }
    return result;
  }

  function trimCapturedHeaders() {
    const now = Date.now();
    for (const [requestId, entry] of capturedHeaders) {
      if (now - entry.at > 180000) capturedHeaders.delete(requestId);
    }
    while (capturedHeaders.size > 600) capturedHeaders.delete(capturedHeaders.keys().next().value);
  }

  function normalizeHost(value) {
    return String(value || "").trim().toLowerCase().replace(/^www\./, "");
  }

  function tabAllowed(tabUrl) {
    if (!settings.enabled || !settings.autoDetectPlayingVideos) return false;
    if (settings.siteMode === "all") return true;
    let host = "";
    try { host = normalizeHost(new URL(tabUrl).hostname); } catch (_) { return true; }
    const list = settings.siteMode === "whitelist" ? settings.whitelist : settings.blacklist;
    const matched = (Array.isArray(list) ? list : []).some(item => {
      const domain = normalizeHost(item);
      return domain && (host === domain || host.endsWith(`.${domain}`));
    });
    return settings.siteMode === "whitelist" ? matched : !matched;
  }

  function sanitizeDiagnosticUrl(value) {
    try {
      const url = new URL(String(value || ""));
      for (const key of [...url.searchParams.keys()]) {
        if (/(?:token|auth|authorization|signature|sig|key|cookie|session|jwt|expires?|policy|credential)/i.test(key)) {
          url.searchParams.set(key, "<redacted>");
        }
      }
      url.hash = "";
      return url.href.slice(0, 2400);
    } catch (_) {
      return String(value || "").slice(0, 2400);
    }
  }

  function counterFor(tabId) {
    const key = Number(tabId);
    if (!diagnosticCounters.has(key)) {
      diagnosticCounters.set(key, {
        webResponses: 0,
        pageResponses: 0,
        bodyCandidates: 0,
        frames: new Set(),
        lastSource: ""
      });
    }
    return diagnosticCounters.get(key);
  }

  async function updateDiagnostics(tabId, payload) {
    try {
      const result = await browser.storage.local.get(DIAGNOSTICS_KEY);
      const all = result[DIAGNOSTICS_KEY] && typeof result[DIAGNOSTICS_KEY] === "object"
        ? result[DIAGNOSTICS_KEY]
        : {};
      const counter = counterFor(tabId);
      all[String(tabId)] = {
        at: Date.now(),
        url: sanitizeDiagnosticUrl(payload.url),
        contentType: payload.contentType || "",
        requestType: payload.requestType || "",
        confidence: payload.confidence || 0,
        reason: payload.reason || "",
        title: payload.title || "",
        frameId: Number.isFinite(Number(payload.frameId)) ? Number(payload.frameId) : 0,
        source: payload.source || counter.lastSource || "",
        quality: payload.quality === "possible" ? "possible" : "strong",
        webResponses: counter.webResponses,
        pageResponses: counter.pageResponses,
        bodyCandidates: counter.bodyCandidates,
        frameCount: counter.frames.size,
        candidateCount: candidateStore.size(tabId)
      };
      const entries = Object.entries(all).sort((a, b) => Number(b[1].at || 0) - Number(a[1].at || 0));
      await browser.storage.local.set({ [DIAGNOSTICS_KEY]: Object.fromEntries(entries.slice(0, MAX_DIAGNOSTIC_TABS)) });
    } catch (_) {}
  }

  function makeInjectionCode(payload) {
    return `(() => { const bridge = globalThis.__xdmInPageBridgeV1; return bridge ? bridge.offerNetwork(${JSON.stringify(payload)}) : false; })();`;
  }

  async function offerInTopFrame(tabId, payload) {
    const code = makeInjectionCode(payload);
    try {
      const result = await browser.tabs.executeScript(tabId, { code, frameId: 0, runAt: "document_idle" });
      if (result && result[0]) return true;
    } catch (_) {}

    try {
      for (const file of ["bridge-selftest.js", "generated-config.js", "handoff.js", "fab.js", "frame-bridge.js"]) {
        await browser.tabs.executeScript(tabId, { file, frameId: 0, runAt: "document_idle" });
      }
      const retry = await browser.tabs.executeScript(tabId, { code, frameId: 0, runAt: "document_idle" });
      return Boolean(retry && retry[0]);
    } catch (error) {
      publishStatus({ lastError: error && error.message ? error.message : String(error), lastErrorAt: Date.now() });
      return false;
    }
  }

  function trimTabCandidates(tabId) {
    candidateStore.trim(tabId);
  }

  function allowsQuality(quality) {
    return quality !== "possible" || settings.showPossibleMediaCandidates === true;
  }

  function mergeCandidate(tabId, candidate) {
    if (!allowsQuality(candidate && candidate.quality)) return false;
    const safe = Object.assign({}, candidate, {
      headers: sanitizeHeaderObject(candidate && candidate.headers || {}),
      browserHandoff: candidate && candidate.browserHandoff ? capturedHeaderPayload(candidate.browserHandoff) : undefined,
      stableMediaId: candidate && candidate.stableMediaId || CORE.stableMediaIdentity(candidate && candidate.url),
      sessionRevision: Date.now(),
    });
    return candidateStore.merge(tabId, safe);
  }

  function scheduleDispatch(tabId, delay = 500) {
    const numericTabId = Number(tabId);
    if (dispatchTimers.has(numericTabId)) clearTimeout(dispatchTimers.get(numericTabId));
    dispatchTimers.set(numericTabId, setTimeout(() => dispatchTab(numericTabId), delay));
  }

  function captureSessionFor(tabId) {
    const numericTabId = Number(tabId);
    const existing = tabDispatchSessions.get(numericTabId);
    if (existing) return existing;
    const randomPart = (() => {
      try {
        const bytes = crypto.getRandomValues(new Uint8Array(12));
        return [...bytes].map(value => value.toString(16).padStart(2, "0")).join("");
      } catch (_) {
        return Math.random().toString(36).slice(2) + Date.now().toString(36);
      }
    })();
    const created = { id: `browser-${numericTabId}-${randomPart}`.slice(0, 92), revision: Date.now() };
    tabDispatchSessions.set(numericTabId, created);
    return created;
  }

  function candidateStreamKind(candidate) {
    const url = String(candidate && candidate.url || "");
    const mime = CORE.normalizeMime(candidate && candidate.contentType || "");
    if (/\.mpd(?:$|[?#])/i.test(url) || mime === "application/dash+xml") return "dash";
    if (/\.m3u8(?:$|[?#])/i.test(url) || /mpegurl/i.test(mime)) return "hls";
    if (mime.startsWith("audio/")) return "audio";
    if (mime.startsWith("video/")) return "video";
    return candidate && candidate.manifest ? "hls" : "media";
  }

  async function dispatchTab(tabId) {
    dispatchTimers.delete(tabId);
    trimTabCandidates(tabId);
    const candidate = candidateStore.best(tabId);
    if (!candidate) return;

    let tab;
    try { tab = await browser.tabs.get(tabId); } catch (_) { return; }
    if (!tab || !/^https?:/i.test(tab.url || "") || !tabAllowed(tab.url)) return;

    const rank = CORE.rankCandidate(candidate);
    if (candidate.quality === "possible" && settings.showPossibleMediaCandidates !== true) return;
    if (candidate.quality === "possible" && !candidate.playbackObserved && rank < POSSIBLE_OFFER_THRESHOLD) return;
    if (candidate.quality !== "possible" && !candidate.playbackObserved && !candidate.autoOffer && rank < AUTO_OFFER_THRESHOLD) return;

    const candidateCount = candidateStore.size(tabId);
    const candidateRevision = Number(candidate.sessionRevision || 0);
    const previous = lastDispatchedByTab.get(tabId);
    if (previous && previous.url === candidate.url && previous.candidateCount === candidateCount &&
        previous.revision === candidateRevision && Date.now() - previous.at < SAME_URL_SUPPRESS_MS) return;
    lastDispatchedByTab.set(tabId, { url: candidate.url, candidateCount, revision: candidateRevision, at: Date.now() });

    const session = captureSessionFor(tabId);
    session.revision = Math.max(Number(session.revision || 0), candidateRevision, Date.now());
    const sessionCandidates = candidateStore.snapshot(tabId, MAX_HANDOFF_CANDIDATES).map(item => Object.assign({}, item, {
      streamKind: candidateStreamKind(item),
    }));
    let prebuiltXdmLink = "";
    const handoff = globalThis.XdmHandoffV1;
    if (handoff && typeof handoff.buildEncryptedCaptureSession === "function") {
      try {
        prebuiltXdmLink = await handoff.buildEncryptedCaptureSession({
          sessionId: session.id,
          revision: session.revision,
          pageUrl: tab.url,
          title: tab.title || "Detected video",
          candidates: sessionCandidates,
          totalCandidateCount: candidateCount,
          truncated: candidateCount > sessionCandidates.length,
          scheme: globalThis.XdmExtensionConfig && globalThis.XdmExtensionConfig.xdmScheme,
        });
      } catch (error) {
        publishStatus({ lastError: `Encrypted capture handoff failed: ${error && error.message ? error.message : String(error)}`, lastErrorAt: Date.now() });
      }
    }

    const payload = Object.assign({}, candidate, {
      tabUrl: tab.url,
      title: tab.title || "Detected video",
      displayFallback: true,
      contentLength: candidate.contentLength || 0,
      durationMs: candidate.durationMs || 0,
      thumbnailUrl: candidate.thumbnailUrl || "",
      candidateCount,
      streamKind: candidateStreamKind(candidate),
      rank,
      stableMediaId: candidate.stableMediaId || CORE.stableMediaIdentity(candidate.url),
      sessionRevision: candidate.sessionRevision || session.revision,
      browserHandoff: candidate.browserHandoff || null,
      captureSessionId: session.id,
      prebuiltXdmLink,
      encryptedCandidateCount: sessionCandidates.length,
    });
    await updateDiagnostics(tabId, payload);
    await offerInTopFrame(tabId, payload);
  }

  function addClassifiedResponse(tabId, details, headers, source = "webRequest", handoffContext = null) {
    const contentType = CORE.normalizeMime(details.contentType || "");
    const classification = CORE.classifyResponse({
      url: details.url,
      type: details.requestType || details.type,
      contentType,
      contentLength: details.contentLength,
      contentDisposition: details.contentDisposition || "",
      contentRange: details.contentRange || ""
    });
    if (!classification.accept || !allowsQuality(classification.quality)) return false;

    const counter = counterFor(tabId);
    counter.lastSource = source;
    if (source === "webRequest") counter.webResponses += 1;
    counter.frames.add(Number(details.frameId || 0));

    const added = mergeCandidate(tabId, {
      url: details.url,
      contentType,
      contentLength: details.contentLength,
      requestType: details.requestType || details.type || "",
      frameId: details.frameId,
      frameUrl: details.frameUrl || "",
      source,
      headers,
      browserHandoff: handoffContext,
      stableMediaId: CORE.stableMediaIdentity(details.url),
      confidence: classification.confidence,
      reason: classification.reason,
      manifest: classification.manifest,
      quality: classification.quality,
      autoOffer: classification.autoOffer,
      at: Date.now()
    });
    if (added) scheduleDispatch(tabId);
    return added;
  }

  function processPageObservation(message, sender) {
    const tabId = sender && sender.tab ? sender.tab.id : -1;
    if (tabId === -1 || !message || typeof message !== "object") return false;
    const observation = message.observation && typeof message.observation === "object" ? message.observation : {};
    const frameId = Number.isFinite(Number(sender.frameId)) ? Number(sender.frameId) : Number(observation.frameId || 0);
    const frameUrl = sender.url || observation.pageUrl || "";
    const counter = counterFor(tabId);
    counter.pageResponses += 1;
    counter.frames.add(frameId);
    counter.lastSource = observation.source || "page";

    const headers = sanitizeHeaderObject(observation.requestHeaders || {});
    const responseUrl = CORE.resolveUrl(observation.responseUrl || observation.url || observation.requestUrl, frameUrl);
    let added = false;

    if (responseUrl) {
      added = addClassifiedResponse(tabId, {
        url: responseUrl,
        contentType: observation.contentType || "",
        contentLength: Number(observation.contentLength || 0),
        requestType: observation.requestType || observation.source || "xmlhttprequest",
        contentDisposition: observation.contentDisposition || "",
        contentRange: observation.contentRange || "",
        frameId,
        frameUrl
      }, headers, `page:${observation.source || "response"}`) || added;
    }

    const bodyExcerpt = typeof observation.bodyExcerpt === "string" ? observation.bodyExcerpt : "";
    if (bodyExcerpt) {
      const analysis = CORE.analyzeBody({
        text: bodyExcerpt,
        contentType: observation.contentType || "",
        responseUrl: responseUrl || frameUrl
      });

      if (analysis.manifestBody && responseUrl) {
        const manifestReason = analysis.hlsBody ? "hls-response-body" : "dash-response-body";
        added = mergeCandidate(tabId, {
          url: responseUrl,
          contentType: observation.contentType || (analysis.hlsBody ? "application/vnd.apple.mpegurl" : "application/dash+xml"),
          requestType: observation.requestType || observation.source || "xmlhttprequest",
          frameId,
          frameUrl,
          source: `page:${observation.source || "body"}`,
          headers,
          confidence: 1180,
          reason: manifestReason,
          manifest: true,
          quality: "strong",
          bodyDerived: true,
          autoOffer: true,
          at: Date.now()
        }) || added;
      }

      for (const extracted of analysis.candidates) {
        if (!allowsQuality(extracted.quality)) continue;
        counter.bodyCandidates += 1;
        added = mergeCandidate(tabId, {
          url: extracted.url,
          contentType: "",
          requestType: observation.requestType || observation.source || "body",
          frameId,
          frameUrl,
          source: `body:${observation.source || "response"}`,
          headers,
          confidence: extracted.confidence,
          reason: extracted.reason,
          manifest: CORE.isManifest(extracted.url, ""),
          quality: extracted.quality || (extracted.confidence >= 850 ? "strong" : "possible"),
          bodyDerived: true,
          autoOffer: extracted.quality !== "possible" && extracted.confidence >= 850,
          at: Date.now()
        }) || added;
      }
    }

    if (added) scheduleDispatch(tabId, 420);
    return added;
  }

  function processFramePlayback(message, sender) {
    const tabId = sender && sender.tab ? sender.tab.id : -1;
    if (tabId === -1 || !message || !message.candidate) return false;
    const value = message.candidate;
    const frameId = Number.isFinite(Number(sender.frameId)) ? Number(sender.frameId) : 0;
    const counter = counterFor(tabId);
    counter.frames.add(frameId);
    counter.lastSource = "frame-playback";
    const added = mergeCandidate(tabId, {
      url: value.url,
      contentType: value.contentType || "",
      requestType: "media",
      frameId,
      frameUrl: sender.url || "",
      source: value.source || "frame-playback",
      headers: value.headers || {},
      confidence: Math.max(930, Number(value.confidence || value.bonus || 0)),
      reason: value.reason || "frame-playback",
      manifest: Boolean(value.manifest || CORE.isManifest(value.url, value.contentType)),
      quality: "strong",
      playbackObserved: true,
      autoOffer: true,
      at: Date.now()
    });
    if (added) scheduleDispatch(tabId, 120);
    return added;
  }

  // Register synchronously so page scripts never race an absent receiving end.
  browser.runtime.onMessage.addListener((message, sender) => {
    try {
      if (message && message.type === MESSAGE_TYPE) return processPageObservation(message, sender);
      if (message && message.type === PLAYBACK_TYPE) return processFramePlayback(message, sender);
    } catch (error) {
      publishStatus({ lastError: error && error.message ? error.message : String(error), lastErrorAt: Date.now() });
    }
    return false;
  });

  try {
    browser.webRequest.onBeforeSendHeaders.addListener(
      details => {
        if (details.tabId === -1) return;
        capturedHeaders.set(details.requestId, {
          at: Date.now(),
          requestGeneration: Date.now(),
          proposedHeaders: captureUsefulHeaders(details.requestHeaders),
          finalHeaders: {},
          finalHeadersAvailable: false,
          finalUnavailableReason: browser.webRequest.onSendHeaders ? "pending onSendHeaders" : "onSendHeaders unsupported",
          frameUrl: details.documentUrl || details.originUrl || "",
          tabUrl: details.initiator || "",
        });
        trimCapturedHeaders();
      },
      { urls: ["<all_urls>"] },
      ["requestHeaders"]
    );

    if (browser.webRequest.onSendHeaders && browser.webRequest.onSendHeaders.addListener) {
      browser.webRequest.onSendHeaders.addListener(
        details => {
          if (details.tabId === -1) return;
          const previous = capturedHeaders.get(details.requestId) || { at: Date.now(), proposedHeaders: {} };
          previous.finalHeaders = captureUsefulHeaders(details.requestHeaders);
          previous.finalHeadersAvailable = true;
          previous.finalUnavailableReason = "";
          capturedHeaders.set(details.requestId, previous);
          trimCapturedHeaders();
        },
        { urls: ["<all_urls>"] },
        ["requestHeaders"]
      );
    }

    browser.webRequest.onHeadersReceived.addListener(
      details => {
        if (details.tabId === -1 || !settings.enabled || !settings.autoDetectPlayingVideos) return;
        const captured = capturedHeaders.get(details.requestId);
        addClassifiedResponse(details.tabId, {
          url: details.url,
          contentType: headerValue(details.responseHeaders, "content-type"),
          contentLength: Number(headerValue(details.responseHeaders, "content-length") || 0),
          contentDisposition: headerValue(details.responseHeaders, "content-disposition"),
          contentRange: headerValue(details.responseHeaders, "content-range"),
          requestType: details.type || "",
          frameId: details.frameId,
          frameUrl: details.originUrl || details.documentUrl || ""
        }, captured ? (captured.finalHeadersAvailable ? captured.finalHeaders : captured.proposedHeaders) : {}, "webRequest", captured);
      },
      { urls: ["<all_urls>"] },
      ["responseHeaders"]
    );

    const clear = details => capturedHeaders.delete(details.requestId);
    browser.webRequest.onCompleted.addListener(clear, { urls: ["<all_urls>"] });
    browser.webRequest.onErrorOccurred.addListener(clear, { urls: ["<all_urls>"] });
    browser.tabs.onRemoved.addListener(tabId => {
      // Request headers are keyed by requestId. Tab removal must not accidentally delete a same-numbered request id.
      tabDispatchSessions.delete(tabId);
      candidateStore.removeTab(tabId);
      lastDispatchedByTab.delete(tabId);
      diagnosticCounters.delete(tabId);
      if (dispatchTimers.has(tabId)) clearTimeout(dispatchTimers.get(tabId));
      dispatchTimers.delete(tabId);
    });
  } catch (error) {
    publishStatus({ active: false, lastError: error && error.message ? error.message : String(error), lastErrorAt: Date.now() });
  }
})();
