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
assert(core.classifyResponse({ url: "https://cdn.example/video", type: "xmlhttprequest", contentType: "video/mp4" }).accept);
assert(core.classifyResponse({ url: "https://cdn.example/master", type: "fetch", contentType: "application/vnd.apple.mpegurl" }).manifest);
assert(!core.classifyResponse({ url: "https://cdn.example/chunk-3.m4s", type: "media", contentType: "video/mp4" }).accept);
const body = core.analyzeBody({ responseUrl: "https://site.example/api", contentType: "application/json", text: '{"manifestUrl":"\\u0068ttps:\\/\\/cdn.example\\/master.m3u8"}' });
assert(body.candidates.some(item => item.url === "https://cdn.example/master.m3u8"));
const Store = context.XdmCandidateStoreV1;
const store = new Store({ maxPerTab: 4, ttlMs: 100000 });
assert(store.merge(7, { url: "https://cdn.example/video", confidence: 800, frameId: 4 }));
assert(store.merge(7, { url: "https://cdn.example/master.m3u8", confidence: 1000, manifest: true, frameId: 8 }));
assert.strictEqual(store.best(7).url, "https://cdn.example/master.m3u8");
assert.strictEqual(store.size(7), 2);
console.log("detector and candidate-store tests passed");
