(() => {
  const CONFIG = globalThis.XdmExtensionConfig || {};
  if (globalThis.XdmHandoffV1) return;

  const TARGETS = Object.freeze({ XDM: "xdm", ONE_DM: "1dm", ASK: "ask" });
  const HEADER_ALLOWLIST = new Set(["authorization", "cookie", "referer", "user-agent", "origin", "accept", "accept-language", "range"]);
  const MAX_HEADER_BLOCK = 12 * 1024;

  function safeHttpUrl(value) {
    const raw = String(value || "").trim();
    if (!raw) return "";
    try {
      const base = globalThis.document && document.baseURI ? document.baseURI : (globalThis.location && location.href ? location.href : undefined);
      const url = new URL(raw, base);
      return /^(?:https?|ftp):$/.test(url.protocol) && !url.username && !url.password ? url.href : "";
    } catch (_) { return ""; }
  }

  function encodeSchemeData(value) { return encodeURI(String(value || "")).replace(/#/g, "%23"); }

  function cleanFilename(title, url, mimeType = "") {
    let suffix = "";
    try {
      const match = new URL(url).pathname.match(/\.(m3u8|mpd|mp4|m4v|webm|mkv|mov|avi|flv|mpeg|mpg|ogv|mp3|m4a|aac|flac|wav|ogg|opus)$/i);
      if (match && !/^(m3u8|mpd)$/i.test(match[1])) suffix = `.${match[1].toLowerCase()}`;
    } catch (_) {}
    if (!suffix) {
      const type = String(mimeType || "").split(";", 1)[0].toLowerCase();
      if (type === "video/mp4") suffix = ".mp4";
      else if (type === "audio/mpeg") suffix = ".mp3";
      else if (type === "audio/mp4") suffix = ".m4a";
    }
    const documentTitle = globalThis.document && document.title ? document.title : "";
    const base = String(title || documentTitle || "download")
      .replace(/[\\/:*?"<>|\u0000-\u001f]+/g, " ").replace(/\s+/g, " ").trim().slice(0, 140) || "download";
    return `${base}${suffix}`.slice(0, 160);
  }

  function mediaKind(url, mimeType) {
    const type = String(mimeType || "").split(";", 1)[0].toLowerCase();
    if (type.startsWith("video/")) return "video";
    if (type.startsWith("audio/")) return "audio";
    if (/\.m3u8(?:$|[?#])/i.test(url)) return "hls";
    if (/\.mpd(?:$|[?#])/i.test(url)) return "dash";
    return "media";
  }

  function validScheme(value) {
    const scheme = String(value || CONFIG.xdmScheme || "xdmdownload").toLowerCase();
    return /^[a-z][a-z0-9+.-]{1,40}$/.test(scheme) ? scheme : "";
  }

  function cleanText(value, limit) {
    return String(value || "").replace(/[\u0000-\u001f\u007f]/g, " ").trim().slice(0, limit);
  }

  function sanitizeHeaderBag(value) {
    const result = {};
    for (const [rawName, rawValue] of Object.entries(value && typeof value === "object" ? value : {})) {
      const name = String(rawName || "").trim().toLowerCase();
      if (!HEADER_ALLOWLIST.has(name)) continue;
      const headerValue = String(rawValue == null ? "" : rawValue).replace(/[\r\n\u0000-\u001f\u007f]+/g, " ").trim().slice(0, 8192);
      if (headerValue) result[name] = headerValue;
    }
    return result;
  }

  function headerBlock(value) {
    const lines = Object.entries(sanitizeHeaderBag(value)).slice(0, 24).map(([name, headerValue]) => `${name}: ${headerValue}`);
    let result = "";
    for (const line of lines) {
      const next = result ? `${result}\n${line}` : line;
      if (next.length > MAX_HEADER_BLOCK) break;
      result = next;
    }
    return result;
  }

  function candidateHeaderBags(candidate = {}) {
    const handoff = candidate.browserHandoff && typeof candidate.browserHandoff === "object" ? candidate.browserHandoff : {};
    const proposed = handoff.proposedHeaders && typeof handoff.proposedHeaders === "object" ? handoff.proposedHeaders.headers : candidate.headers;
    const finalSent = handoff.finalHeaders && typeof handoff.finalHeaders === "object" ? handoff.finalHeaders.headers : null;
    const proposedHeaders = headerBlock(proposed || {});
    const finalHeaders = headerBlock(finalSent || {});
    return { proposedHeaders, finalHeaders, rawHeaders: finalHeaders || proposedHeaders };
  }

  function buildXdmAdd(input = {}) {
    const url = safeHttpUrl(input.url); const scheme = validScheme(input.scheme);
    if (!url || !scheme) return "";
    const params = new URLSearchParams(); params.set("v", "1"); params.set("url", url);
    const pageUrl = safeHttpUrl(input.pageUrl || ""); if (pageUrl) params.set("page", pageUrl);
    const title = cleanText(input.title, 240); if (title) params.set("title", title);
    const filename = cleanFilename(input.title, url, input.mimeType); if (filename) params.set("filename", filename);
    const mime = String(input.mimeType || "").split(";", 1)[0].trim().toLowerCase().slice(0, 120); if (mime) params.set("mime", mime);
    return `${scheme}://add?${params.toString()}`;
  }

  function buildXdmCapture(input = {}) {
    const url = safeHttpUrl(input.url); const scheme = validScheme(input.scheme);
    if (!url || !scheme) return "";
    const params = new URLSearchParams();
    params.set("v", String(CONFIG.contractVersion || 3));
    params.set("url", url);
    const pageUrl = safeHttpUrl(input.pageUrl || input.tabUrl || ""); if (pageUrl) params.set("page", pageUrl);
    const frameUrl = safeHttpUrl(input.frameUrl || ""); if (frameUrl) params.set("frame", frameUrl);
    const thumbnail = safeHttpUrl(input.thumbnailUrl || ""); if (thumbnail) params.set("thumbnail", thumbnail);
    const title = cleanText(input.title, 240); if (title) params.set("title", title);
    const filename = cleanFilename(input.title, url, input.mimeType || input.contentType); if (filename) params.set("filename", filename);
    const mime = String(input.mimeType || input.contentType || "").split(";", 1)[0].trim().toLowerCase().slice(0, 120); if (mime) params.set("mime", mime);
    params.set("kind", cleanText(input.streamKind || mediaKind(url, mime), 32).toLowerCase() || "media");
    const stableMediaId = String(input.stableMediaId || "").trim().replace(/[^A-Za-z0-9._:-]/g, "").slice(0, 160); if (stableMediaId.length >= 8) params.set("stableMediaId", stableMediaId);
    const revision = Math.max(0, Number(input.sessionRevision || input.revision || 0)); if (revision) params.set("sessionRevision", String(Math.trunc(revision)));
    const length = Math.max(0, Number(input.contentLength || 0)); if (length) params.set("length", String(Math.trunc(length)));
    const duration = Math.max(0, Number(input.durationMs || 0)); if (duration) params.set("durationMs", String(Math.trunc(duration)));
    const blocks = candidateHeaderBags(input);
    if (blocks.rawHeaders) params.set("headers", blocks.rawHeaders);
    if (blocks.proposedHeaders) params.set("proposedHeaders", blocks.proposedHeaders);
    if (blocks.finalHeaders) params.set("finalHeaders", blocks.finalHeaders);
    const link = `${scheme}://capture?${params.toString()}`;
    return link.length <= 64 * 1024 ? link : "";
  }

  function compactSessionCandidate(candidate = {}, fallbackPageUrl = "", fallbackTitle = "", revision = 0) {
    const url = safeHttpUrl(candidate.url);
    if (!url) return null;
    const handoff = candidate.browserHandoff && typeof candidate.browserHandoff === "object" ? candidate.browserHandoff : {};
    const proposed = handoff.proposedHeaders && typeof handoff.proposedHeaders === "object" ? handoff.proposedHeaders.headers : candidate.headers;
    const finalSent = handoff.finalHeaders && typeof handoff.finalHeaders === "object" ? handoff.finalHeaders.headers : null;
    const evidence = Array.isArray(candidate.evidence)
      ? candidate.evidence.map(item => cleanText(item, 48)).filter(Boolean).slice(0, 8)
      : [];
    const result = {
      url,
      pageUrl: safeHttpUrl(candidate.pageUrl || candidate.tabUrl || fallbackPageUrl),
      frameUrl: safeHttpUrl(candidate.frameUrl || ""),
      title: cleanText(candidate.title || fallbackTitle, 240),
      contentType: String(candidate.contentType || candidate.mimeType || "").split(";", 1)[0].trim().toLowerCase().slice(0, 120),
      contentLength: Math.max(0, Math.trunc(Number(candidate.contentLength || 0))),
      durationMs: Math.max(0, Math.trunc(Number(candidate.durationMs || 0))),
      thumbnailUrl: safeHttpUrl(candidate.thumbnailUrl || ""),
      thumbnailProvenance: cleanText(candidate.thumbnailProvenance || "Unknown", 32).replace(/[^A-Za-z]/g, "") || "Unknown",
      stableMediaId: String(candidate.stableMediaId || "").trim().replace(/[^A-Za-z0-9._:-]/g, "").slice(0, 160),
      requestFingerprint: String(candidate.requestFingerprint || "").trim().replace(/[^A-Za-z0-9._:-]/g, "").slice(0, 96),
      sessionRevision: Math.max(1, Math.trunc(Number(candidate.sessionRevision || revision || 1))),
      quality: cleanText(candidate.quality || "strong", 24).toLowerCase() || "strong",
      reason: cleanText(candidate.reason || "browser-media", 96) || "browser-media",
      streamKind: cleanText(candidate.streamKind || mediaKind(url, candidate.contentType), 24).toLowerCase() || "media",
      manifest: Boolean(candidate.manifest || /\.(?:m3u8|mpd)(?:$|[?#])/i.test(url)),
      playbackObserved: Boolean(candidate.playbackObserved),
      evidence,
      proposedHeaders: sanitizeHeaderBag(proposed || {}),
      finalHeaders: sanitizeHeaderBag(finalSent || {}),
    };
    for (const key of ["pageUrl", "frameUrl", "title", "contentType", "thumbnailUrl", "stableMediaId", "requestFingerprint"]) {
      if (!result[key]) delete result[key];
    }
    if (!result.contentLength) delete result.contentLength;
    if (!result.durationMs) delete result.durationMs;
    if (!result.evidence.length) delete result.evidence;
    if (!Object.keys(result.proposedHeaders).length) delete result.proposedHeaders;
    if (!Object.keys(result.finalHeaders).length) delete result.finalHeaders;
    return result;
  }

  async function buildCaptureSession(input = {}) {
    const requested = (Array.isArray(input.candidates) ? input.candidates : [])
      .map(candidate => compactSessionCandidate(candidate, input.pageUrl, input.title, input.revision))
      .filter(Boolean)
      .slice(0, 24);
    const primary = requested[0] || compactSessionCandidate(input, input.pageUrl, input.title, input.revision);
    if (!primary) return "";
    const primarySource = (Array.isArray(input.candidates) ? input.candidates : []).find(item => item && safeHttpUrl(item.url)) || input;
    const base = buildXdmCapture(Object.assign({}, primarySource, {
      scheme: input.scheme,
      pageUrl: input.pageUrl || primary.pageUrl,
      title: input.title || primary.title,
      sessionRevision: input.revision || primary.sessionRevision,
      streamKind: primary.streamKind || mediaKind(primary.url, primary.contentType),
    }));
    if (!base || requested.length === 0) return base;

    const sessionId = String(input.sessionId || "").trim().replace(/[^A-Za-z0-9._:-]/g, "").slice(0, 96);
    const revision = Math.max(1, Math.trunc(Number(input.revision || primary.sessionRevision || 1)));
    if (!sessionId) return base;
    const total = Math.max(requested.length, Math.trunc(Number(input.totalCandidateCount || requested.length)));

    // URL-encoding can expand JSON substantially. Keep the strongest prefix that fits the
    // 64 KiB Android deep-link contract, and declare truncation rather than lying about count.
    for (let count = requested.length; count >= 1; count -= 1) {
      const batch = requested.slice(0, count);
      const json = JSON.stringify(batch);
      if (json.length > 52 * 1024) continue;
      const uri = new URL(base);
      uri.searchParams.set("sid", sessionId);
      uri.searchParams.set("sessionRevision", String(revision));
      uri.searchParams.set("candidateCount", String(total));
      uri.searchParams.set("capturedCandidateCount", String(count));
      uri.searchParams.set("truncated", input.truncated || total > count ? "1" : "0");
      uri.searchParams.set("candidates", json);
      const link = uri.toString();
      if (link.length <= 64 * 1024) return link;
    }
    return base;
  }

  // Compatibility symbol for old tests/callers in the same source tree. New XPIs do not encrypt.
  async function buildEncryptedCaptureSession(input = {}) { return buildCaptureSession(input); }

  function buildOneDm(input = {}) {
    const url = safeHttpUrl(input.url); return url ? `idmdownload:${encodeSchemeData(url)}` : "idmdownload:";
  }
  function buildTargets(input = {}) {
    return Object.freeze({ xdm: buildXdmAdd(input), oneDm: buildOneDm(input), filename: cleanFilename(input.title, input.url, input.mimeType) });
  }

  globalThis.XdmHandoffV1 = Object.freeze({ TARGETS, safeHttpUrl, buildXdmAdd, buildXdmCapture, buildCaptureSession, buildEncryptedCaptureSession, buildOneDm, buildTargets, cleanFilename, mediaKind, sanitizeHeaderBag });
})();
