(() => {
  const CORE = globalThis.XdmDetectorCoreV1;
  if (!CORE || globalThis.XdmCandidateStoreV1) return;

  class CandidateStore {
    constructor(options = {}) {
      this.maxPerTab = Number(options.maxPerTab || 160);
      this.ttlMs = Number(options.ttlMs || 5 * 60 * 1000);
      this.buckets = new Map();
    }

    merge(tabId, candidate = {}) {
      const numericTabId = Number(tabId);
      if (!Number.isFinite(numericTabId) || numericTabId < 0) return false;
      const url = CORE.resolveUrl(candidate.url, candidate.baseUrl);
      if (!url || CORE.isLikelyAd(url) || CORE.isLikelySegment(url)) return false;

      const requestFingerprint = String(candidate.requestFingerprint || CORE.requestFingerprint({
        url,
        requestId: candidate.requestId,
        tabId: numericTabId,
        frameId: candidate.frameId,
        requestGeneration: candidate.requestGeneration,
      }) || "").trim();
      if (!requestFingerprint) return false;
      const stableMediaId = String(candidate.stableMediaId || CORE.stableMediaIdentity(url, requestFingerprint) || "").trim();
      if (!stableMediaId) return false;
      const bucket = this.buckets.get(numericTabId) || new Map();
      const previous = bucket.get(stableMediaId) || {};
      const nextConfidence = Number(candidate.confidence || 0);
      const previousConfidence = Number(previous.confidence || 0);
      const mergedQuality = candidate.quality === "strong" || previous.quality === "strong"
        ? "strong"
        : (candidate.quality || previous.quality || "possible");
      const merged = {
        url,
        contentType: candidate.contentType || previous.contentType || "",
        contentLength: Number(candidate.contentLength || previous.contentLength || 0),
        requestType: candidate.requestType || previous.requestType || "",
        frameId: Number.isFinite(Number(candidate.frameId)) ? Number(candidate.frameId) : Number(previous.frameId || 0),
        frameUrl: candidate.frameUrl || previous.frameUrl || "",
        pageUrl: candidate.pageUrl || previous.pageUrl || "",
        title: candidate.title || previous.title || "",
        durationMs: Math.max(0, Number(candidate.durationMs || previous.durationMs || 0)),
        thumbnailUrl: candidate.thumbnailUrl || previous.thumbnailUrl || "",
        contentDisposition: candidate.contentDisposition || previous.contentDisposition || "",
        source: candidate.source || previous.source || "network",
        requestId: candidate.requestId || previous.requestId || "",
        requestFingerprint,
        requestGeneration: Number(candidate.requestGeneration || previous.requestGeneration || 0),
        // Logical candidates merge retries/range requests; the newest request evidence wins while its fingerprint remains explicit.
        headers: Object.assign({}, previous.headers || {}, candidate.headers || {}),
        browserHandoff: Object.assign({}, previous.browserHandoff || {}, candidate.browserHandoff || {}),
        stableMediaId,
        requestEvidenceCount: Math.min(32, Number(previous.requestEvidenceCount || 0) + (previous.requestFingerprint === requestFingerprint ? 0 : 1)),
        sessionRevision: Math.max(Number(candidate.sessionRevision || 0), Number(previous.sessionRevision || 0), Date.now()),
        quality: mergedQuality,
        confidence: Math.max(nextConfidence, previousConfidence),
        reason: nextConfidence >= previousConfidence
          ? (candidate.reason || previous.reason || "media")
          : (previous.reason || candidate.reason || "media"),
        manifest: Boolean(candidate.manifest || previous.manifest),
        bodyDerived: Boolean(candidate.bodyDerived || previous.bodyDerived),
        playbackObserved: Boolean(candidate.playbackObserved || previous.playbackObserved),
        autoOffer: Boolean(candidate.autoOffer || previous.autoOffer),
        at: Date.now()
      };
      bucket.set(stableMediaId, merged);
      this.buckets.set(numericTabId, bucket);
      this.trim(numericTabId);
      return true;
    }

    trim(tabId) {
      const numericTabId = Number(tabId);
      const bucket = this.buckets.get(numericTabId);
      if (!bucket) return;
      const now = Date.now();
      for (const [identity, candidate] of bucket) {
        if (now - Number(candidate.at || 0) > this.ttlMs) bucket.delete(identity);
      }
      if (bucket.size > this.maxPerTab) {
        const sorted = [...bucket.values()].sort((a, b) => CORE.rankCandidate(b) - CORE.rankCandidate(a));
        bucket.clear();
        for (const item of sorted.slice(0, this.maxPerTab)) bucket.set(item.stableMediaId, item);
      }
      if (!bucket.size) this.buckets.delete(numericTabId);
    }

    best(tabId) {
      return this.snapshot(tabId, 1)[0] || null;
    }

    snapshot(tabId, limit = this.maxPerTab) {
      const numericTabId = Number(tabId);
      this.trim(numericTabId);
      const bucket = this.buckets.get(numericTabId);
      if (!bucket || !bucket.size) return [];
      const safeLimit = Math.max(1, Math.min(this.maxPerTab, Number(limit || this.maxPerTab)));
      return [...bucket.values()]
        .sort((a, b) => CORE.rankCandidate(b) - CORE.rankCandidate(a))
        .slice(0, safeLimit)
        .map(candidate => Object.assign({}, candidate, {
          headers: Object.assign({}, candidate.headers || {}),
          browserHandoff: candidate.browserHandoff ? JSON.parse(JSON.stringify(candidate.browserHandoff)) : undefined,
        }));
    }

    size(tabId) {
      this.trim(tabId);
      return (this.buckets.get(Number(tabId)) || new Map()).size;
    }

    removeTab(tabId) {
      this.buckets.delete(Number(tabId));
    }
  }

  globalThis.XdmCandidateStoreV1 = CandidateStore;
})();
