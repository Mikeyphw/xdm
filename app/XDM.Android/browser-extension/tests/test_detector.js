"use strict";
const assert = require("assert");
const fs = require("fs");
const vm = require("vm");
const path = require("path");
const source = path.resolve(__dirname, "../src/main/extension/xdm-firefox");
const context = vm.createContext({ console, URL, URLSearchParams, Date, setTimeout, clearTimeout, globalThis: null });
context.globalThis = context;
for (const file of ["detector-core.js", "candidate-store.js"]) {
  vm.runInContext(fs.readFileSync(path.join(source, file), "utf8"), context, { filename: file });
}
const core = context.XdmDetectorCoreV1;
let classified = core.classifyResponse({ url: "https://cdn.example/video", type: "xmlhttprequest", contentType: "video/mp4" });
assert(classified.accept);
assert.strictEqual(classified.quality, "strong");
assert(classified.autoOffer);
classified = core.classifyResponse({ url: "https://cdn.example/master", type: "fetch", contentType: "application/vnd.apple.mpegurl" });
assert(classified.manifest);
assert.strictEqual(classified.quality, "strong");
classified = core.classifyResponse({ url: "https://cdn.example/master.m3u8?token=suffix-only", type: "fetch", contentType: "" });
assert(classified.accept, "manifest suffix is retained as correlation evidence");
assert.strictEqual(classified.quality, "possible", "manifest suffix alone must not auto-offer a generic fetch");
assert.strictEqual(classified.autoOffer, false);
assert.strictEqual(classified.reason, "possible-manifest-extension");
assert(!core.classifyResponse({ url: "https://cdn.example/chunk-3.m4s", type: "media", contentType: "video/mp4" }).accept);
assert(!core.classifyResponse({ url: "https://api.example/video/metadata", type: "xmlhttprequest", contentType: "application/json", contentLength: 512 }).accept);
assert(!core.classifyResponse({ url: "https://site.example/api/stream", type: "fetch", contentType: "application/json", contentLength: 4096 }).accept);
classified = core.classifyResponse({ url: "https://cdn.example/playback", type: "fetch", contentType: "application/octet-stream", contentLength: 4096 });
assert(classified.accept);
assert.strictEqual(classified.quality, "possible");
assert.strictEqual(classified.autoOffer, false);
classified = core.classifyResponse({
  url: "https://cdn.example/blob",
  type: "xmlhttprequest",
  contentType: "application/octet-stream",
  contentLength: 0,
  contentDisposition: "attachment; filename=movie.mp4"
});
assert(classified.accept);
assert.strictEqual(classified.quality, "strong");
const body = core.analyzeBody({ responseUrl: "https://site.example/api", contentType: "application/json", text: '{"manifestUrl":"\\u0068ttps:\\/\\/cdn.example\\/master.m3u8"}' });
assert(body.candidates.some(item => item.url === "https://cdn.example/master.m3u8" && item.quality === "strong"));
const possibleBody = core.analyzeBody({
  responseUrl: "https://site.example/api",
  contentType: "application/json",
  text: '{"streamUrl":"https://cdn.example/playback/session/123"}'
});
assert.strictEqual(possibleBody.candidates.length, 0, "extensionless streamUrl JSON needs runtime response/playback evidence");
const noisy = core.analyzeBody({
  responseUrl: "https://site.example/api",
  contentType: "application/json",
  text: '{"posterUrl":"https://cdn.example/poster.jpg","url":"https://api.example/video/metadata","src":"https://cdn.example/thumb.webp"}'
});
assert.strictEqual(noisy.candidates.length, 0);

classified = core.classifyResponse({ url: "https://api.example/fake.mp4", type: "fetch", contentType: "application/json", contentLength: 4096 });
assert(!classified.accept, "JSON response must beat a misleading .mp4 suffix");
const misleadingJson = core.analyzeBody({
  responseUrl: "https://api.example/player",
  contentType: "application/json",
  text: '{"url":"https://cdn.example/not-media.mp4","poster":"https://cdn.example/poster.mp4","analytics":"https://cdn.example/event.m3u8"}'
});
assert.strictEqual(misleadingJson.candidates.length, 0, "generic JSON URLs must not become media by suffix alone");
const Store = context.XdmCandidateStoreV1;
const store = new Store({ maxPerTab: 4, ttlMs: 100000 });
assert(store.merge(7, { url: "https://cdn.example/video", confidence: 800, quality: "strong", frameId: 4 }));
assert(store.merge(7, { url: "https://cdn.example/master.m3u8", confidence: 1000, quality: "strong", manifest: true, frameId: 8 }));
assert(store.merge(7, { url: "https://cdn.example/playback", confidence: 720, quality: "possible", frameId: 8 }));
assert.strictEqual(store.best(7).url, "https://cdn.example/master.m3u8");
assert.strictEqual(store.size(7), 3);
const snapshot = store.snapshot(7, 3);
assert.strictEqual(snapshot.length, 3);
assert.strictEqual(snapshot[0].url, "https://cdn.example/master.m3u8");
assert(snapshot.some(item => item.url === "https://cdn.example/video"));
const promoteFingerprint = core.requestFingerprint({ url: "https://cdn.example/promote.m3u8", requestId: "promote", tabId: 8, frameId: 0, requestGeneration: 1 });
const promotionStore = new Store({ maxPerTab: 4, ttlMs: 100000 });
assert(promotionStore.merge(8, { url: "https://cdn.example/promote.m3u8", requestFingerprint: promoteFingerprint, confidence: 730, quality: "possible" }));
assert(promotionStore.merge(8, { url: "https://cdn.example/promote.m3u8", requestFingerprint: promoteFingerprint, confidence: 1040, quality: "strong", manifest: true, bodyDerived: true, autoOffer: true }));
assert(promotionStore.merge(8, { url: "https://cdn.example/promote.m3u8", requestFingerprint: promoteFingerprint, confidence: 740, quality: "possible" }));
assert.strictEqual(promotionStore.best(8).quality, "strong", "later weak observations must not downgrade promoted evidence");
assert.strictEqual(promotionStore.best(8).autoOffer, true);
const metadataFingerprint = core.requestFingerprint({ url: "https://cdn.example/movie.mp4", requestId: "metadata", tabId: 9, frameId: 0, requestGeneration: 1 });
const metadataStore = new Store({ maxPerTab: 4, ttlMs: 100000 });
assert(metadataStore.merge(9, { url: "https://cdn.example/movie.mp4", requestFingerprint: metadataFingerprint, confidence: 900, quality: "strong", durationMs: 123000, thumbnailUrl: "https://img.example/movie.jpg", pageUrl: "https://page.example/watch", title: "Movie page" }));
assert.strictEqual(metadataStore.best(9).durationMs, 123000);
assert.strictEqual(metadataStore.best(9).thumbnailUrl, "https://img.example/movie.jpg");
assert.strictEqual(metadataStore.best(9).title, "Movie page");

const signedOne = "https://cdn.example/master.m3u8?sig=one";
const signedTwo = "https://cdn.example/master.m3u8?sig=two";
const requestOne = core.requestFingerprint({ url: signedOne, requestId: "a", tabId: 7, frameId: 0, requestGeneration: 1 });
const requestTwo = core.requestFingerprint({ url: signedTwo, requestId: "b", tabId: 7, frameId: 0, requestGeneration: 1 });
assert.notStrictEqual(requestOne, requestTwo);
assert.strictEqual(core.stableMediaIdentity(signedOne, requestOne), core.stableMediaIdentity(signedTwo, requestTwo), "credential refresh must retain logical media identity");
const identityStore = new Store({ maxPerTab: 8, ttlMs: 100000 });
assert(identityStore.merge(7, { url: signedOne, requestId: "a", requestFingerprint: requestOne, confidence: 900, quality: "strong", headers: { authorization: "Bearer one" } }));
assert(identityStore.merge(7, { url: signedOne, requestId: "b", requestFingerprint: requestTwo, confidence: 910, quality: "strong", headers: { authorization: "Bearer two" } }));
assert.strictEqual(identityStore.size(7), 1, "same logical media from retries/range requests must merge into one candidate");
assert.strictEqual(identityStore.best(7).headers.authorization, "Bearer two", "freshest request evidence should replace execution headers");
assert(identityStore.best(7).requestEvidenceCount >= 2);
for (const name of ["part1.mp4", "init.mp4", "chunk.mp4"]) {
  const result=core.classifyResponse({url:`https://cdn.example/media/${name}`,type:"media",contentType:"video/mp4",contentLength:8*1024*1024});
  assert(result.accept,`${name} must remain a legitimate media candidate`);
  assert.notStrictEqual(result.reason,"segment");
}
const realSegment=core.classifyResponse({url:"https://cdn.example/media/chunk_99.m4s",type:"media",contentType:"video/iso.segment"});
assert.strictEqual(realSegment.accept,false); assert.strictEqual(realSegment.reason,"segment");

const logicalSigned = core.logicalMediaUrl("https://cdn.example/master.m3u8?sig=one&expires=123&quality=1080p&X-Amz-Credential=secret&X-Amz-Signature=deadbeef");
assert(!logicalSigned.includes("sig="));
assert(!logicalSigned.toLowerCase().includes("x-amz"));
assert(logicalSigned.includes("quality=1080p"));
const rel = core.parseHlsRelationships(`#EXTM3U
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="English",LANGUAGE="en",DEFAULT=YES,URI="audio/en.m3u8"
#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="English",LANGUAGE="en",URI="subs/en.m3u8"
#EXT-X-STREAM-INF:BANDWIDTH=2800000,RESOLUTION=1280x720,CODECS="avc1.64001f",AUDIO="audio",SUBTITLES="subs"
video/720.m3u8`, "https://cdn.example/master.m3u8");
assert.strictEqual(rel.variantInfo.length,1);
assert.strictEqual(rel.variantInfo[0].height,720);
assert.strictEqual(rel.trackInfo.length,2);
assert(rel.trackInfo.some(item => item.type === "subtitles" && item.language === "en"));

console.log("detector and candidate-store tests passed");
