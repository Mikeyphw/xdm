"use strict";
const assert = require("assert");
const fs = require("fs");
const path = require("path");
const vm = require("vm");

const source = path.resolve(__dirname, "../src/main/extension/xdm-firefox");
const context = vm.createContext({
  console,
  URL,
  URLSearchParams,
  encodeURI,
  Date,
  setTimeout,
  clearTimeout,
  document: { baseURI: "https://page.example/watch", title: "Release fixture" },
  location: { href: "https://page.example/watch" },
  globalThis: null,
});
context.globalThis = context;
context.XdmExtensionConfig = {
  contractVersion: 1,
  xdmScheme: "xdmdownload",
  defaultTarget: "xdm",
};
for (const file of ["detector-core.js", "candidate-store.js", "handoff.js"]) {
  vm.runInContext(fs.readFileSync(path.join(source, file), "utf8"), context, { filename: file });
}

const core = context.XdmDetectorCoreV1;
const handoff = context.XdmHandoffV1;

for (const fixture of [
  { url: "https://cdn.example/video.mp4", type: "media", contentType: "video/mp4" },
  { url: "https://cdn.example/master", type: "fetch", contentType: "application/vnd.apple.mpegurl" },
  { url: "https://cdn.example/manifest", type: "xmlhttprequest", contentType: "application/dash+xml" },
]) {
  assert(core.classifyResponse(fixture).accept, `release fixture rejected: ${fixture.contentType}`);
}
assert(!core.classifyResponse({ url: "https://cdn.example/chunk-11.m4s", type: "media", contentType: "video/mp4" }).accept);

const signedUrl = "https://cdn.example/master.m3u8?signature=signed-value&expires=999";
const deepLink = handoff.buildXdmCapture({
  url: signedUrl,
  pageUrl: "https://page.example/watch",
  title: "Release fixture",
  mimeType: "application/vnd.apple.mpegurl",
  cookie: "must-not-be-copied",
  authorization: "must-not-be-copied",
  headers: { Cookie: "must-not-be-copied" },
});
const parsed = new URL(deepLink);
assert.strictEqual(parsed.protocol, "xdmdownload:");
assert.strictEqual(parsed.hostname, "capture");
assert.strictEqual(parsed.searchParams.get("url"), signedUrl);
assert.deepStrictEqual([...parsed.searchParams.keys()].sort(), ["filename", "kind", "mime", "page", "title", "url", "v"]);
assert(!/cookie|authorization|headers/i.test(deepLink));
assert.strictEqual(handoff.buildXdmCapture({ url: "https://user:pass@cdn.example/video.mp4" }), "");
assert.strictEqual(handoff.buildXdmCapture({ url: "javascript:alert(1)" }), "");
assert.strictEqual(handoff.buildOneDm({ url: "https://cdn.example/video.mp4" }), "idmdownload:https://cdn.example/video.mp4");

console.log("browser bridge release-gate JavaScript tests passed");
