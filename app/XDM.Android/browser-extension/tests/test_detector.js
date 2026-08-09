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
assert(possibleBody.candidates.some(item => item.url === "https://cdn.example/playback/session/123" && item.quality === "possible"));
const noisy = core.analyzeBody({
  responseUrl: "https://site.example/api",
  contentType: "application/json",
  text: '{"posterUrl":"https://cdn.example/poster.jpg","url":"https://api.example/video/metadata","src":"https://cdn.example/thumb.webp"}'
});
assert.strictEqual(noisy.candidates.length, 0);
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
console.log("detector and candidate-store tests passed");
