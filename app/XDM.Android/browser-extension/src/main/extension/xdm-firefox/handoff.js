(() => {
  const CONFIG = globalThis.XdmExtensionConfig || {};
  if (globalThis.XdmHandoffV1) return;

  const TARGETS = Object.freeze({ XDM: "xdm", ONE_DM: "1dm", ASK: "ask" });
  const MEDIA_KIND_BY_MIME = Object.freeze({ video: "video", audio: "audio" });

  function safeHttpUrl(value) {
    try {
      const url = new URL(String(value || ""), document.baseURI);
      return /^(?:https?|ftp):$/.test(url.protocol) && !url.username && !url.password ? url.href : "";
    } catch (_) {
      return "";
    }
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
    const base = String(title || document.title || "download")
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

  function buildXdmCapture(input = {}) {
    const url = safeHttpUrl(input.url);
    if (!url) return "";
    const scheme = String(input.scheme || CONFIG.xdmScheme || "xdmdownload").toLowerCase();
    if (!/^[a-z][a-z0-9+.-]{1,40}$/.test(scheme)) return "";
    const params = new URLSearchParams();
    params.set("v", String(CONFIG.contractVersion || 1));
    params.set("url", url);
    const page = safeHttpUrl(input.pageUrl || location.href);
    if (page) params.set("page", page);
    const title = String(input.title || document.title || "").replace(/[\u0000-\u001f\u007f]/g, " ").trim().slice(0, 240);
    if (title) params.set("title", title);
    const filename = cleanFilename(title, url, input.mimeType);
    if (filename) params.set("filename", filename);
    const mime = String(input.mimeType || "").split(";", 1)[0].trim().toLowerCase();
    if (/^[a-z0-9!#$&^_.+-]+\/[a-z0-9!#$&^_.+-]+$/.test(mime)) params.set("mime", mime.slice(0, 120));
    params.set("kind", mediaKind(url, mime));
    if (input.stableMediaId) params.set("stableMediaId", String(input.stableMediaId).slice(0, 160));
    if (input.sessionRevision) params.set("sessionRevision", String(input.sessionRevision).slice(0, 40));
    if (input.frameUrl) {
      const frame = safeHttpUrl(input.frameUrl);
      if (frame && frame !== page) params.set("frame", frame);
    }
    // Keep custom-scheme URLs credential-thin: raw Cookie/Authorization/header bags never go into the URL.
    return `${scheme}://capture?${params.toString()}`;
  }

  function encodeSchemeData(value) {
    try { return encodeURI(String(value || "")).replace(/#/g, "%23"); }
    catch (_) { return String(value || "").replace(/#/g, "%23"); }
  }

  function buildOneDm(input = {}) {
    const url = safeHttpUrl(input.url);
    return url ? `idmdownload:${encodeSchemeData(url)}` : "idmdownload:";
  }

  function buildTargets(input = {}) {
    return Object.freeze({
      xdm: buildXdmCapture(input),
      oneDm: buildOneDm(input),
      filename: cleanFilename(input.title, input.url, input.mimeType)
    });
  }

  globalThis.XdmHandoffV1 = Object.freeze({ TARGETS, safeHttpUrl, buildXdmCapture, buildOneDm, buildTargets, cleanFilename, mediaKind });
})();
