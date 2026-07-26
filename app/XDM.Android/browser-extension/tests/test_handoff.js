"use strict";
const assert = require("assert");
const fs = require("fs");
const vm = require("vm");
const path = require("path");
const source = path.resolve(__dirname, "../src/main/extension/xdm-firefox");
const context = vm.createContext({
  console, URL, URLSearchParams, encodeURI,
  document: { baseURI: "https://page.example/watch", title: "Episode 4" },
  location: { href: "https://page.example/watch" },
  globalThis: null
});
context.globalThis = context;
context.XdmExtensionConfig = { contractVersion: 1, xdmScheme: "xdmdownload", defaultTarget: "xdm" };
vm.runInContext(fs.readFileSync(path.join(source, "handoff.js"), "utf8"), context, { filename: "handoff.js" });
const handoff = context.XdmHandoffV1;
const xdm = handoff.buildXdmCapture({ url: "https://cdn.example/master.m3u8?token=abc", pageUrl: "https://page.example/watch", title: "Episode 4", mimeType: "application/vnd.apple.mpegurl" });
assert(xdm.startsWith("xdmdownload://capture?"));
const parsed = new URL(xdm);
assert.strictEqual(parsed.searchParams.get("v"), "1");
assert.strictEqual(parsed.searchParams.get("url"), "https://cdn.example/master.m3u8?token=abc");
assert.strictEqual(parsed.searchParams.get("kind"), "hls");
assert(!/cookie|authorization|extra_headers/i.test(xdm));
assert.strictEqual(handoff.buildOneDm({ url: "https://cdn.example/video.mp4" }), "idmdownload:https://cdn.example/video.mp4");
console.log("handoff tests passed");
