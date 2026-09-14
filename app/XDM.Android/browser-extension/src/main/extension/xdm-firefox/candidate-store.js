(() => {
  const CORE = globalThis.XdmDetectorCoreV1;
  if (!CORE || globalThis.XdmCandidateStoreV1) return;

  const MAX_EVIDENCE_PER_TAB = 384;




  function evidenceFrameId(candidate) {
    const value = Number(candidate && candidate.frameId);
    return Number.isFinite(value) ? value : 0;
  }

  function evidenceGeneration(candidate) {
    const value = Number(candidate && (candidate.requestGeneration || candidate.sessionRevision || candidate.at || 0));
    return Number.isFinite(value) && value > 0 ? Math.trunc(value) : 0;
  }

  function evidenceKey(candidate, exact) {
    const requestId = String(candidate && candidate.requestId || '').trim();
    if (!requestId) return '';
    return [String(exact || ''), requestId, evidenceFrameId(candidate), evidenceGeneration(candidate)].join('\n');
  }

  function cloneCandidate(candidate) {
    return Object.assign({}, candidate, {
      headers: Object.assign({}, candidate && candidate.headers || {}),
      browserHandoff: candidate && candidate.browserHandoff ? JSON.parse(JSON.stringify(candidate.browserHandoff)) : undefined,
      variantUrls: [...(candidate && candidate.variantUrls || [])],
      trackUrls: [...(candidate && candidate.trackUrls || [])],
      variantInfo: (candidate && candidate.variantInfo || []).map(item => Object.assign({}, item)),
      trackInfo: (candidate && candidate.trackInfo || []).map(item => Object.assign({}, item)),
    });
  }

  function mergeObjectArray(left, right, keyFn, limit) {
    const merged = new Map();
    for (const item of [...(left || []), ...(right || [])]) {
      const key = keyFn(item);
      if (key) merged.set(key, Object.assign({}, merged.get(key) || {}, item));
    }
    return [...merged.values()].slice(0, limit);
  }

  class CandidateStore {
    constructor(options = {}) {
      this.maxPerTab = Number(options.maxPerTab || 160);
      this.ttlMs = Number(options.ttlMs || 5 * 60 * 1000);
      this.buckets = new Map();
      this.aliases = new Map();
      this.evidence = new Map();
      this.stats = new Map();
    }

    bucketFor(tabId) {
      const key = Number(tabId);
      if (!this.buckets.has(key)) this.buckets.set(key, new Map());
      if (!this.aliases.has(key)) this.aliases.set(key, new Map());
      if (!this.evidence.has(key)) this.evidence.set(key, new Map());
      if (!this.stats.has(key)) this.stats.set(key, { rawObservations: 0, suppressedSegments: 0 });
      return this.buckets.get(key);
    }

    rootIdentity(tabId, identity) {
      const aliases = this.aliases.get(Number(tabId)) || new Map();
      let current = identity;
      const visited = new Set();
      while (aliases.has(current) && !visited.has(current)) {
        visited.add(current);
        current = aliases.get(current);
      }
      return current;
    }

    rememberEvidence(tabId, candidate) {
      const key = Number(tabId);
      const evidence = this.evidence.get(key) || new Map();
      const exact = CORE.exactRequestUrl(candidate.url);
      const compositeKey = evidenceKey(candidate, exact);
      // XAR10: privileged evidence is keyed by exact URL + requestId + frame + generation,
      // never by a single exact-URL slot that a parallel same-URL request can overwrite.
      if (exact && compositeKey) evidence.set(compositeKey, Object.assign({}, candidate, {
        exactRequestUrl: exact,
        evidenceKey: compositeKey,
        at: Date.now(),
      }));
      while (evidence.size > MAX_EVIDENCE_PER_TAB) evidence.delete(evidence.keys().next().value);
      this.evidence.set(key, evidence);
    }

    findEvidence(tabId, rawUrl, frameOrOptions = null, maxAgeMs = 45 * 1000) {
      const key = Number(tabId);
      this.trim(key);
      const exact = CORE.exactRequestUrl(CORE.resolveUrl(rawUrl, ""));
      const options = frameOrOptions && typeof frameOrOptions === "object" ? frameOrOptions : { frameId: frameOrOptions };
      const wantedFrame = options.frameId == null ? null : Number(options.frameId);
      const wantedRequestId = String(options.requestId || "").trim();
      const now = Date.now();
      const matches = [...((this.evidence.get(key) || new Map()).values())].filter(candidate => {
        if (candidate.exactRequestUrl !== exact) return false;
        if (now - Number(candidate.at || 0) > maxAgeMs) return false;
        if (wantedRequestId && String(candidate.requestId || "") !== wantedRequestId) return false;
        if (wantedFrame != null && Number.isFinite(wantedFrame) && evidenceFrameId(candidate) !== wantedFrame) return false;
        return true;
      }).sort((a, b) => evidenceGeneration(b) - evidenceGeneration(a) || Number(b.at || 0) - Number(a.at || 0));
      return matches.length ? cloneCandidate(matches[0]) : null;
    }

    clearDocumentEvidence(tabId, frameId = null) {
      const key = Number(tabId);
      const evidence = this.evidence.get(key);
      if (!evidence) return;
      const wantedFrame = frameId == null ? null : Number(frameId);
      for (const [composite, candidate] of evidence) {
        if (wantedFrame == null || !Number.isFinite(wantedFrame) || evidenceFrameId(candidate) === wantedFrame) {
          evidence.delete(composite);
        }
      }
    }

    absorb(tabId, rootId, childId) {
      const key = Number(tabId);
      const bucket = this.bucketFor(key);
      const aliases = this.aliases.get(key);
      const root = this.rootIdentity(key, rootId);
      const child = this.rootIdentity(key, childId);
      if (!root || !child || root === child) return;
      aliases.set(child, root);
      const childItem = bucket.get(child);
      const rootItem = bucket.get(root);
      if (childItem && rootItem) {
        rootItem.observationCount = Number(rootItem.observationCount || 1) + Number(childItem.observationCount || 1);
        rootItem.segmentCount = Number(rootItem.segmentCount || 0) + Number(childItem.segmentCount || 0);
        rootItem.requestEvidenceCount = Math.min(64, Number(rootItem.requestEvidenceCount || 0) + Number(childItem.requestEvidenceCount || 0));
        rootItem.variantUrls = [...new Set([...(rootItem.variantUrls || []), childItem.url, ...(childItem.variantUrls || [])])].slice(0, 32);
        rootItem.trackUrls = [...new Set([...(rootItem.trackUrls || []), ...(childItem.trackUrls || [])])].slice(0, 32);
        rootItem.variantInfo = mergeObjectArray(rootItem.variantInfo, childItem.variantInfo, item => item && item.url, 32);
        rootItem.trackInfo = mergeObjectArray(rootItem.trackInfo, childItem.trackInfo, item => item && `${item.type || "track"}|${item.groupId || ""}|${item.url || ""}`, 32);
        rootItem.confidence = Math.max(Number(rootItem.confidence || 0), Number(childItem.confidence || 0));
        bucket.delete(child);
      } else if (childItem && !rootItem) {
        bucket.delete(child);
        childItem.logicalMediaId = root;
        childItem.stableMediaId = root;
        bucket.set(root, childItem);
      }
    }

    merge(tabId, candidate = {}) {
      const numericTabId = Number(tabId);
      if (!Number.isFinite(numericTabId) || numericTabId < 0) return false;
      const bucket = this.bucketFor(numericTabId);
      const stats = this.stats.get(numericTabId);
      stats.rawObservations += 1;

      const url = CORE.resolveUrl(candidate.url, candidate.baseUrl);
      if (!url || CORE.isLikelyAd(url)) return false;
      const evidenceCandidate = Object.assign({}, candidate, { url });
      this.rememberEvidence(numericTabId, evidenceCandidate);
      if (CORE.isLikelySegment(url)) {
        stats.suppressedSegments += 1;
        const parentId = candidate.parentStableMediaId || candidate.parentManifestUrl && CORE.stableMediaIdentity(candidate.parentManifestUrl);
        const root = parentId && this.rootIdentity(numericTabId, parentId);
        const parent = root && bucket.get(root);
        if (parent) parent.segmentCount = Number(parent.segmentCount || 0) + 1;
        return false;
      }

      const requestFingerprint = String(candidate.requestFingerprint || CORE.requestFingerprint({
        url, requestId: candidate.requestId, tabId: numericTabId, frameId: candidate.frameId,
        requestGeneration: candidate.requestGeneration,
      }) || "").trim();
      if (!requestFingerprint) return false;
      const rawStable = String(candidate.logicalMediaId || candidate.stableMediaId || CORE.stableMediaIdentity(url, requestFingerprint) || "").trim();
      if (!rawStable) return false;
      const parentStable = String(candidate.parentStableMediaId || (candidate.parentManifestUrl ? CORE.stableMediaIdentity(candidate.parentManifestUrl) : "") || "").trim();
      let stableMediaId = this.rootIdentity(numericTabId, parentStable || rawStable);

      const manifestText = String(candidate.manifestText || "");
      const hls = manifestText && CORE.parseHlsRelationships ? CORE.parseHlsRelationships(manifestText, url) : null;
      if (hls && hls.isHls && hls.master) {
        // Keep the master's identity authoritative. Child aliases are installed by absorb()
        // after any already-retained child row has been merged into the master.
        stableMediaId = rawStable;
      }
      stableMediaId = this.rootIdentity(numericTabId, stableMediaId);

      const previous = bucket.get(stableMediaId) || {};
      const nextConfidence = Number(candidate.confidence || 0);
      const previousConfidence = Number(previous.confidence || 0);
      const mergedQuality = candidate.quality === "strong" || previous.quality === "strong" ? "strong" : (candidate.quality || previous.quality || "possible");
      const sameRequest = previous.requestFingerprint === requestFingerprint;
      // XAR10: a signed URL candidate with a new request fingerprint carries a new executable
      // exact URL and header proof. Do not keep the previous signed exact URL while adopting
      // newer request headers/fingerprints.
      const exactRequestUrl = sameRequest ? (previous.url || url) : url;
      const mergedHeaders = sameRequest
        ? Object.assign({}, previous.headers || {}, candidate.headers || {})
        : Object.assign({}, candidate.headers || {});
      const mergedHandoff = sameRequest
        ? Object.assign({}, previous.browserHandoff || {}, candidate.browserHandoff || {})
        : Object.assign({}, candidate.browserHandoff || {});
      const merged = {
        url: hls && hls.master ? url : exactRequestUrl,
        canonicalUrl: CORE.logicalMediaUrl(hls && hls.master ? url : (previous.canonicalUrl || exactRequestUrl)),
        logicalMediaId: stableMediaId,
        contentType: candidate.contentType || previous.contentType || "",
        contentLength: Math.max(Number(candidate.contentLength || 0), Number(previous.contentLength || 0)),
        requestType: candidate.requestType || previous.requestType || "",
        frameId: Number.isFinite(Number(candidate.frameId)) ? Number(candidate.frameId) : Number(previous.frameId || 0),
        frameUrl: candidate.frameUrl || previous.frameUrl || "",
        pageUrl: candidate.pageUrl || previous.pageUrl || "",
        title: candidate.title || previous.title || "",
        durationMs: Math.max(0, Number(candidate.durationMs || previous.durationMs || 0)),
        thumbnailUrl: candidate.thumbnailUrl || previous.thumbnailUrl || "",
        thumbnailProvenance: candidate.thumbnailProvenance || previous.thumbnailProvenance || "Unknown",
        contentDisposition: candidate.contentDisposition || previous.contentDisposition || "",
        source: candidate.source || previous.source || "network",
        requestId: candidate.requestId || previous.requestId || "",
        requestFingerprint,
        requestGeneration: Number(candidate.requestGeneration || previous.requestGeneration || 0),
        headers: mergedHeaders,
        browserHandoff: mergedHandoff,
        stableMediaId,
        requestEvidenceCount: Math.min(64, Number(previous.requestEvidenceCount || 0) + (sameRequest ? 0 : 1)),
        observationCount: Math.min(1000000, Number(previous.observationCount || 0) + 1),
        segmentCount: Number(previous.segmentCount || 0) + Number(candidate.segmentCount || 0) + Number(hls && hls.segments.length || 0),
        variantUrls: [...new Set([...(previous.variantUrls || []), ...(hls ? hls.childPlaylists : []), ...(candidate.variantUrls || [])])].slice(0, 32),
        trackUrls: [...new Set([...(previous.trackUrls || []), ...(hls ? hls.mediaTracks : []), ...(candidate.trackUrls || [])])].slice(0, 32),
        variantInfo: mergeObjectArray(previous.variantInfo, [...(hls ? hls.variantInfo : []), ...(candidate.variantInfo || [])], item => item && item.url, 32),
        trackInfo: mergeObjectArray(previous.trackInfo, [...(hls ? hls.trackInfo : []), ...(candidate.trackInfo || [])], item => item && `${item.type || "track"}|${item.groupId || ""}|${item.url || ""}`, 32),
        manifestRole: hls && hls.master ? "master" : (candidate.manifestRole || previous.manifestRole || (candidate.manifest ? "media" : "resource")),
        encryptedAes128: Boolean(previous.encryptedAes128 || hls && hls.encryptedAes128 || candidate.encryptedAes128),
        protectedMedia: Boolean(previous.protectedMedia || hls && hls.protectedMedia || candidate.protectedMedia),
        lowLatency: Boolean(previous.lowLatency || hls && hls.lowLatency || candidate.lowLatency),
        sessionRevision: Math.max(Number(candidate.sessionRevision || 0), Number(previous.sessionRevision || 0), Date.now()),
        quality: mergedQuality,
        confidence: Math.max(nextConfidence, previousConfidence),
        reason: nextConfidence >= previousConfidence ? (candidate.reason || previous.reason || "media") : (previous.reason || candidate.reason || "media"),
        manifest: Boolean(candidate.manifest || previous.manifest || hls && hls.isHls),
        bodyDerived: Boolean(candidate.bodyDerived || previous.bodyDerived),
        playbackObserved: Boolean(candidate.playbackObserved || previous.playbackObserved),
        autoOffer: Boolean(candidate.autoOffer || previous.autoOffer),
        at: Date.now()
      };
      bucket.set(stableMediaId, merged);

      if (hls && hls.isHls && hls.master) {
        for (const childUrl of [...hls.childPlaylists, ...hls.mediaTracks]) {
          const childId = CORE.stableMediaIdentity(childUrl);
          if (childId) this.absorb(numericTabId, stableMediaId, childId);
        }
      }
      if (parentStable) this.absorb(numericTabId, stableMediaId, rawStable);
      this.buckets.set(numericTabId, bucket);
      this.trim(numericTabId);
      return true;
    }

    trim(tabId) {
      const numericTabId = Number(tabId);
      const bucket = this.buckets.get(numericTabId);
      if (!bucket) return;
      const now = Date.now();
      for (const [identity, candidate] of bucket) if (now - Number(candidate.at || 0) > this.ttlMs) bucket.delete(identity);
      const evidence = this.evidence.get(numericTabId);
      if (evidence) for (const [composite, candidate] of evidence) if (now - Number(candidate.at || 0) > this.ttlMs) evidence.delete(composite);
      if (bucket.size > this.maxPerTab) {
        const sorted = [...bucket.values()].sort((a, b) => CORE.rankCandidate(b) - CORE.rankCandidate(a));
        bucket.clear();
        for (const item of sorted.slice(0, this.maxPerTab)) bucket.set(item.logicalMediaId || item.stableMediaId, item);
      }
      if (!bucket.size) this.buckets.delete(numericTabId);
    }

    best(tabId) { return this.snapshot(tabId, 1)[0] || null; }

    snapshot(tabId, limit = this.maxPerTab) {
      const numericTabId = Number(tabId);
      this.trim(numericTabId);
      const bucket = this.buckets.get(numericTabId);
      if (!bucket || !bucket.size) return [];
      const safeLimit = Math.max(1, Math.min(this.maxPerTab, Number(limit || this.maxPerTab)));
      return [...bucket.values()].sort((a, b) => CORE.rankCandidate(b) - CORE.rankCandidate(a)).slice(0, safeLimit).map(candidate => Object.assign({}, candidate, {
        headers: Object.assign({}, candidate.headers || {}),
        browserHandoff: candidate.browserHandoff ? JSON.parse(JSON.stringify(candidate.browserHandoff)) : undefined,
        variantUrls: [...(candidate.variantUrls || [])], trackUrls: [...(candidate.trackUrls || [])],
        variantInfo: (candidate.variantInfo || []).map(item => Object.assign({}, item)),
        trackInfo: (candidate.trackInfo || []).map(item => Object.assign({}, item)),
      }));
    }

    diagnostic(tabId) {
      const stats = this.stats.get(Number(tabId)) || { rawObservations: 0, suppressedSegments: 0 };
      return { rawObservations: stats.rawObservations, suppressedSegments: stats.suppressedSegments, logicalCandidates: this.size(tabId) };
    }

    size(tabId) { this.trim(tabId); return (this.buckets.get(Number(tabId)) || new Map()).size; }

    removeTab(tabId) {
      const key = Number(tabId);
      this.buckets.delete(key); this.aliases.delete(key); this.evidence.delete(key); this.stats.delete(key);
    }
  }

  globalThis.XdmCandidateStoreV1 = CandidateStore;
})();
