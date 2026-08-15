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
context.XdmExtensionConfig = { contractVersion: 2, xdmScheme: "xdmdownload", defaultTarget: "xdm" };
vm.runInContext(fs.readFileSync(path.join(source, "handoff.js"), "utf8"), context, { filename: "handoff.js" });
const handoff = context.XdmHandoffV1;
assert.strictEqual(
  handoff.buildXdmCapture({ url: "https://cdn.example/master.m3u8?token=abc" }),
  "",
  "plaintext v1 XDM capture must stay disabled",
);
const targets = handoff.buildTargets({ url: "https://cdn.example/master.m3u8?token=abc" });
assert.match(targets.xdm, /^xdmdownload:\/\/add\?v=1&url=/, "generic direct-add target must use the separate add?v=1 contract");
assert.strictEqual(handoff.buildOneDm({ url: "https://cdn.example/video.mp4" }), "idmdownload:https://cdn.example/video.mp4");
console.log("handoff tests passed");
