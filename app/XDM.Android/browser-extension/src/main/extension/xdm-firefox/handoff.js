(() => {
  const CONFIG = globalThis.XdmExtensionConfig || {};
  if (globalThis.XdmHandoffV1) return;

  const TARGETS = Object.freeze({ XDM: "xdm", ONE_DM: "1dm", ASK: "ask" });
  const MEDIA_KIND_BY_MIME = Object.freeze({ video: "video", audio: "audio" });

  function safeHttpUrl(value) {
    const raw = String(value || "").trim();
    if (!raw) return "";
    try {
      const base = globalThis.document && document.baseURI ? document.baseURI : (globalThis.location && location.href ? location.href : undefined);
      const url = new URL(raw, base);
      return /^(?:https?|ftp):$/.test(url.protocol) && !url.username && !url.password ? url.href : "";
    } catch (_) {
      return "";
    }
  }

  function encodeSchemeData(value) {
    // Preserve the HTTP(S)/FTP URL shape expected by the optional 1DM custom scheme while
    // encoding whitespace/control characters and preventing a URL fragment from becoming
    // the custom-scheme fragment. XDM capture URLs never use this compatibility path.
    return encodeURI(String(value || "")).replace(/#/g, "%23");
  }

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
      .replace(/[\\/:*?"<>|\u0000-\u001f]+/g, " ")
      .replace(/\s+/g, " ")
      .trim()
      .slice(0, 140) || "download";
    return `${base}${suffix}`.slice(0, 160);
  }

  function mediaKind(url, mimeType) {
    const type = String(mimeType || "").split(";", 1)[0].toLowerCase();
    if (type.startsWith("video/")) return MEDIA_KIND_BY_MIME.video;
    if (type.startsWith("audio/")) return MEDIA_KIND_BY_MIME.audio;
    if (/\.m3u8(?:$|[?#])/i.test(url)) return "hls";
    if (/\.mpd(?:$|[?#])/i.test(url)) return "dash";
    return "media";
  }

  function buildXdmAdd(input = {}) {
    const url = safeHttpUrl(input.url);
    if (!url) return "";
    const config = globalThis.XdmExtensionConfig || CONFIG || {};
    const scheme = String(input.scheme || config.xdmScheme || "xdmdownload").toLowerCase();
    if (!/^[a-z][a-z0-9+.-]{1,40}$/.test(scheme)) return "";
    const params = new URLSearchParams();
    params.set("v", "1");
    params.set("url", url);
    const pageUrl = safeHttpUrl(input.pageUrl || "");
    if (pageUrl) params.set("page", pageUrl);
    const title = String(input.title || "").replace(/[\u0000-\u001f\u007f]/g, " ").trim().slice(0, 240);
    if (title) params.set("title", title);
    const filename = cleanFilename(input.title, url, input.mimeType);
    if (filename) params.set("filename", filename);
    const mime = String(input.mimeType || "").split(";", 1)[0].trim().toLowerCase().slice(0, 120);
    if (mime) params.set("mime", mime);
    return `${scheme}://add?${params.toString()}`;
  }

  function buildXdmCapture(_input = {}) {
    // Encrypted v2 capture is the only supported XDM media handoff. Never place the
    // media URL, page URL, signed query, headers, or credentials in a custom-scheme URI.
    return "";
  }

  function buildOneDm(input = {}) {
    const url = safeHttpUrl(input.url);
    return url ? `idmdownload:${encodeSchemeData(url)}` : "idmdownload:";
  }

  function buildTargets(input = {}) {
    return Object.freeze({
      xdm: buildXdmAdd(input),
      oneDm: buildOneDm(input),
      filename: cleanFilename(input.title, input.url, input.mimeType)
    });
  }


  function bytesToBase64Url(bytes) {
    let binary = "";
    const view = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
    for (let index = 0; index < view.length; index += 1) binary += String.fromCharCode(view[index]);
    const encoded = typeof btoa === "function"
      ? btoa(binary)
      : (typeof Buffer !== "undefined" ? Buffer.from(view).toString("base64") : "");
    return encoded.replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
  }

  function base64UrlToBytes(value) {
    const raw = String(value || "").replace(/-/g, "+").replace(/_/g, "/");
    const padded = raw + "=".repeat((4 - raw.length % 4) % 4);
    const binary = typeof atob === "function"
      ? atob(padded)
      : (typeof Buffer !== "undefined" ? Buffer.from(padded, "base64").toString("binary") : "");
    const bytes = new Uint8Array(binary.length);
    for (let index = 0; index < binary.length; index += 1) bytes[index] = binary.charCodeAt(index);
    return bytes;
  }

  function sanitizeHeaderBag(value) {
    const allowed = new Set(["authorization", "cookie", "referer", "user-agent", "origin", "accept", "range"]);
    const result = {};
    for (const [rawName, rawValue] of Object.entries(value && typeof value === "object" ? value : {})) {
      const name = String(rawName || "").trim().toLowerCase();
      if (!allowed.has(name)) continue;
      const headerValue = String(rawValue == null ? "" : rawValue).replace(/[\r\n]+/g, " ").trim().slice(0, 8192);
      if (headerValue) result[name] = headerValue;
    }
    return result;
  }

  function compactCaptureCandidate(candidate = {}, fallback = {}) {
    const url = safeHttpUrl(candidate.url);
    if (!url) return null;
    const handoff = candidate.browserHandoff && typeof candidate.browserHandoff === "object" ? candidate.browserHandoff : {};
    const proposedObservation = handoff.proposedHeaders && typeof handoff.proposedHeaders === "object" ? handoff.proposedHeaders : {};
    const finalObservation = handoff.finalHeaders && typeof handoff.finalHeaders === "object" ? handoff.finalHeaders : {};
    const proposedHeaders = sanitizeHeaderBag(proposedObservation.headers || candidate.headers || {});
    const finalHeaders = sanitizeHeaderBag(finalObservation.headers || {});
    const evidence = [];
    if (candidate.playbackObserved) evidence.push("playback");
    if (candidate.manifest) evidence.push("manifest");
    if (candidate.bodyDerived) evidence.push("response-body");
    if (/^webRequest/.test(String(candidate.source || ""))) evidence.push("web-request");
    if (/fetch/.test(String(candidate.source || ""))) evidence.push("fetch");
    if (/xhr/.test(String(candidate.source || ""))) evidence.push("xhr");
    const pageUrl = safeHttpUrl(fallback.pageUrl || candidate.tabUrl || "");
    const frameUrl = safeHttpUrl(candidate.frameUrl || "");
    const contentType = String(candidate.contentType || "").split(";", 1)[0].trim().toLowerCase().slice(0, 120);
    return {
      url,
      pageUrl,
      frameUrl,
      title: String(fallback.title || "").replace(/[\u0000-\u001f\u007f]/g, " ").trim().slice(0, 240),
      contentType,
      contentLength: Math.max(0, Number(candidate.contentLength || 0)),
      stableMediaId: String(candidate.stableMediaId || "").slice(0, 160),
      requestFingerprint: String(candidate.requestFingerprint || "").trim().replace(/[^A-Za-z0-9._:-]/g, "").slice(0, 96),
      sessionRevision: Math.max(1, Number(candidate.sessionRevision || fallback.revision || Date.now())),
      quality: candidate.quality === "possible" ? "possible" : "strong",
      reason: String(candidate.reason || "browser-media").replace(/[\u0000-\u001f\u007f]/g, " ").trim().slice(0, 96),
      streamKind: String(candidate.streamKind || mediaKind(url, contentType)).slice(0, 24),
      manifest: Boolean(candidate.manifest),
      playbackObserved: Boolean(candidate.playbackObserved),
      evidence,
      proposedHeaders,
      finalHeaders,
    };
  }

  async function buildEncryptedCaptureSession(input = {}) {
    const config = globalThis.XdmExtensionConfig || CONFIG || {};
    const scheme = String(input.scheme || config.xdmScheme || "xdmdownload").toLowerCase();
    const keyId = String(config.captureKeyId || "").trim();
    const publicKeySpki = String(config.capturePublicKeySpki || "").trim();
    const oaepHash = String(config.captureOaepHash || "").trim().toUpperCase();
    if (!keyId || !publicKeySpki || !globalThis.crypto || !crypto.subtle) return "";
    if (oaepHash !== "SHA-1" && oaepHash !== "SHA-256") return "";
    if (!/^[a-z][a-z0-9+.-]{1,40}$/.test(scheme)) return "";
    const sessionId = String(input.sessionId || "").trim();
    if (!/^[A-Za-z0-9._:-]{8,96}$/.test(sessionId)) return "";
    const now = Date.now();
    const revision = Math.max(1, Number(input.revision || now));
    const allCandidates = Array.isArray(input.candidates) ? input.candidates : [];
    const prepared = allCandidates
      .map(candidate => compactCaptureCandidate(candidate, { pageUrl: input.pageUrl, title: input.title, revision }))
      .filter(Boolean)
      .slice(0, 24);
    if (!prepared.length) return "";

    const encoder = new TextEncoder();
    const publicKey = await crypto.subtle.importKey(
      "spki",
      base64UrlToBytes(publicKeySpki),
      { name: "RSA-OAEP", hash: oaepHash },
      false,
      ["encrypt"]
    );
    const aesKey = await crypto.subtle.generateKey({ name: "AES-GCM", length: 256 }, true, ["encrypt"]);
    const rawAes = new Uint8Array(await crypto.subtle.exportKey("raw", aesKey));
    const wrappedKey = new Uint8Array(await crypto.subtle.encrypt({ name: "RSA-OAEP" }, publicKey, rawAes));
    const iv = crypto.getRandomValues(new Uint8Array(12));
    const aad = encoder.encode(`xdm-capture-v2|${sessionId}|${keyId}`);

    let candidateLimit = prepared.length;
    let encodedPayload = null;
    let truncated = Boolean(input.truncated || Number(input.totalCandidateCount || allCandidates.length) > prepared.length);
    while (candidateLimit > 0) {
      const payload = {
        v: 1,
        sid: sessionId,
        revision,
        createdAt: now,
        expiresAt: now + 5 * 60 * 1000,
        pageUrl: safeHttpUrl(input.pageUrl || ""),
        title: String(input.title || "Firefox capture").replace(/[\u0000-\u001f\u007f]/g, " ").trim().slice(0, 240),
        totalCandidateCount: Math.max(Number(input.totalCandidateCount || allCandidates.length || candidateLimit), candidateLimit),
        truncated: truncated || candidateLimit < prepared.length,
        candidates: prepared.slice(0, candidateLimit),
      };
      const bytes = encoder.encode(JSON.stringify(payload));
      if (bytes.length <= 42 * 1024) {
        encodedPayload = bytes;
        truncated = payload.truncated;
        break;
      }
      candidateLimit -= 1;
      truncated = true;
    }
    if (!encodedPayload) return "";
    const ciphertext = new Uint8Array(await crypto.subtle.encrypt({ name: "AES-GCM", iv, additionalData: aad, tagLength: 128 }, aesKey, encodedPayload));
    const params = new URLSearchParams();
    params.set("v", String(config.contractVersion || 2));
    params.set("sid", sessionId);
    params.set("kid", keyId);
    params.set("ek", bytesToBase64Url(wrappedKey));
    params.set("iv", bytesToBase64Url(iv));
    params.set("ct", bytesToBase64Url(ciphertext));
    const url = `${scheme}://capture?${params.toString()}`;
    return url.length <= 64 * 1024 ? url : "";
  }
  globalThis.XdmHandoffV1 = Object.freeze({ TARGETS, safeHttpUrl, buildXdmAdd, buildXdmCapture, buildEncryptedCaptureSession, buildOneDm, buildTargets, cleanFilename, mediaKind });
})();
