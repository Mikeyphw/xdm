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

      const bucket = this.buckets.get(numericTabId) || new Map();
      const previous = bucket.get(url) || {};
      const nextConfidence = Number(candidate.confidence || 0);
      const previousConfidence = Number(previous.confidence || 0);
      const merged = {
        url,
        contentType: candidate.contentType || previous.contentType || "",
        contentLength: Number(candidate.contentLength || previous.contentLength || 0),
        requestType: candidate.requestType || previous.requestType || "",
        frameId: Number.isFinite(Number(candidate.frameId)) ? Number(candidate.frameId) : Number(previous.frameId || 0),
        frameUrl: candidate.frameUrl || previous.frameUrl || "",
        source: candidate.source || previous.source || "network",
        headers: Object.assign({}, previous.headers || {}, candidate.headers || {}),
        browserHandoff: Object.assign({}, previous.browserHandoff || {}, candidate.browserHandoff || {}),
        stableMediaId: candidate.stableMediaId || previous.stableMediaId || CORE.stableMediaIdentity(url),
        sessionRevision: Math.max(Number(candidate.sessionRevision || 0), Number(previous.sessionRevision || 0), Date.now()),
        quality: candidate.quality || previous.quality || "strong",
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
      bucket.set(url, merged);
      this.buckets.set(numericTabId, bucket);
      this.trim(numericTabId);
      return true;
    }

    trim(tabId) {
      const numericTabId = Number(tabId);
      const bucket = this.buckets.get(numericTabId);
      if (!bucket) return;
      const now = Date.now();
      for (const [url, candidate] of bucket) {
        if (now - Number(candidate.at || 0) > this.ttlMs) bucket.delete(url);
      }
      if (bucket.size > this.maxPerTab) {
        const sorted = [...bucket.values()].sort((a, b) => CORE.rankCandidate(b) - CORE.rankCandidate(a));
        bucket.clear();
        for (const item of sorted.slice(0, this.maxPerTab)) bucket.set(item.url, item);
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
