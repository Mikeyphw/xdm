"use strict";
const assert = require("assert");
const fs = require("fs");
const path = require("path");
const vm = require("vm");

const source = path.resolve(__dirname, "../src/main/extension/xdm-firefox");

class FakeElement {
  constructor(tagName) {
    this.tagName = String(tagName || "").toUpperCase();
    this.localName = String(tagName || "").toLowerCase();
    this.children = [];
    this.parentNode = null;
    this.dataset = {};
    this.attributes = {};
    this.listeners = {};
    this.className = "";
    this.id = "";
    this.textContent = "";
    this.innerHTML = "";
    this.isConnected = false;
    this.shadowRoot = null;
    this.onload = null;
    this.onerror = null;
    this.async = true;
    this.src = "";
    this.href = "";
  }
  appendChild(node) {
    if (node.parentNode) node.remove();
    this.children.push(node);
    node.parentNode = this;
    node.isConnected = true;
    if (node.tagName === "SCRIPT" && typeof node.onload === "function") setTimeout(() => node.onload(), 0);
    return node;
  }
  append(...nodes) { nodes.forEach(node => this.appendChild(node)); }
  replaceChildren(...nodes) {
    this.children.forEach(node => { node.parentNode = null; node.isConnected = false; });
    this.children = [];
    this.append(...nodes);
  }
  remove() {
    if (this.parentNode) this.parentNode.children = this.parentNode.children.filter(node => node !== this);
    this.parentNode = null;
    this.isConnected = false;
  }
  attachShadow() {
    this.shadowRoot = new FakeElement("shadow-root");
    this.shadowRoot.host = this;
    this.shadowRoot.isConnected = true;
    return this.shadowRoot;
  }
  setAttribute(name, value) { this.attributes[name] = String(value); }
  getAttribute(name) { return this.attributes[name]; }
  hasAttribute(name) { return Object.prototype.hasOwnProperty.call(this.attributes, name); }
  addEventListener(type, listener) { (this.listeners[type] ||= []).push(listener); }
  dispatch(type, event = {}) { for (const listener of this.listeners[type] || []) listener({ type, target: this, ...event }); }
  querySelectorAll(selector) {
    const nodes = [];
    for (const child of this.children) {
      if (selector.includes("source") && child.localName === "source" && (child.src || child.hasAttribute("src"))) nodes.push(child);
      if (selector.includes("track") && child.localName === "track" && (child.src || child.hasAttribute("src"))) nodes.push(child);
      nodes.push(...child.querySelectorAll(selector));
    }
    return nodes;
  }
}

class FakeVideoElement extends FakeElement {
  constructor() {
    super("video");
    this.paused = false;
    this.ended = false;
    this.readyState = 2;
    this.currentTime = 3;
    this.currentSrc = "";
    this.src = "";
  }
}

class FakeScriptElement extends FakeElement {
  constructor() { super("script"); }
}

function walk(root) {
  const values = [root];
  for (const child of root.children || []) values.push(...walk(child));
  return values;
}

function testSetTimeout(callback, delay, ...args) {
  const handle = setTimeout(callback, delay, ...args);
  if (handle && typeof handle.unref === "function") handle.unref();
  return handle;
}

function createContext({ includeHandoff = true, includeFab = true, videos = [] } = {}) {
  const documentListeners = {};
  const windowListeners = {};
  const documentElement = new FakeElement("html");
  const body = new FakeElement("body");
  documentElement.appendChild(body);
  const document = {
    body,
    documentElement,
    fullscreenElement: null,
    baseURI: "https://page.example/watch",
    title: "Phase 43A fixture",
    readyState: "complete",
    createElement: tag => String(tag).toLowerCase() === "script" ? new FakeScriptElement() : new FakeElement(tag),
    getElementById(id) { return walk(documentElement).find(node => node.id === id) || null; },
    querySelector() { return null; },
    querySelectorAll(selector) {
      if (selector.includes("video")) return videos;
      if (selector.includes("script")) return [];
      return [];
    },
    addEventListener(type, listener) { (documentListeners[type] ||= []).push(listener); }
  };

  const sentMessages = [];
  const context = vm.createContext({
    console,
    URL,
    URLSearchParams,
    encodeURI,
    Date,
    setTimeout: testSetTimeout,
    clearTimeout,
    Element: FakeElement,
    HTMLVideoElement: FakeVideoElement,
    HTMLScriptElement: FakeScriptElement,
    HTMLMediaElement: FakeVideoElement,
    MutationObserver: class { observe() {} },
    PerformanceObserver: class { observe() {} },
    document,
    location: { href: "https://page.example/watch", hostname: "page.example" },
    window: null,
    globalThis: null,
    browser: {
      storage: {
        local: { async get() { return {}; } },
        onChanged: { addListener() {} }
      },
      runtime: {
        getURL(file) { return `moz-extension://fixture/${file}`; },
        sendMessage(message) { sentMessages.push(message); return Promise.resolve(true); }
      }
    }
  });
  context.globalThis = context;
  context.window = context;
  context.window.top = context.window;
  context.window.addEventListener = (type, listener) => { (windowListeners[type] ||= []).push(listener); };
  context.window.postMessage = (data) => {
    for (const listener of windowListeners.message || []) listener({ source: context.window, data });
  };
  context.__listeners = { document: documentListeners, window: windowListeners };
  context.__sentMessages = sentMessages;
  context.XdmExtensionConfig = { contractVersion: 1, xdmScheme: "xdmdownload", defaultTarget: "xdm" };

  run(context, "bridge-selftest.js");
  if (includeHandoff) run(context, "handoff.js");
  if (includeFab) run(context, "fab.js");
  run(context, "frame-bridge.js");
  return context;
}

function run(context, file) {
  vm.runInContext(fs.readFileSync(path.join(source, file), "utf8"), context, { filename: file });
}

function host(context) {
  return context.document.getElementById("__xdm_media_fab_host");
}

const plain = createContext();
const selfTest = plain.__xdmBridgeSelfTestV1.probe();
assert.strictEqual(selfTest.ok, true, "dependency-free self-test must mount a temporary host");
let manual = plain.__xdmInPageBridgeV1.showManualWithDiagnostics({ mode: "probe" });
assert.strictEqual(manual.shown, true, "probe FAB must render on a plain HTTPS page");
assert.strictEqual(manual.health.hasHandoff, true);
assert.strictEqual(manual.health.hasFab, true);
assert(host(plain) && host(plain).shadowRoot, "manual probe must leave the real FAB mounted");

const secureCaptureLink = "xdmdownload://capture?v=3&url=https%3A%2F%2Fcdn.example%2Fvideo.mp4&kind=video";
const noEncryptedFallback = createContext();
assert.strictEqual(noEncryptedFallback.__xdmInPageBridgeV1.offerNetwork({
  url: "https://cdn.example/master.m3u8",
  manifest: true,
  contentType: "application/vnd.apple.mpegurl",
  displayFallback: true,
  autoOffer: true,
  candidateCount: 1,
  streamKind: "hls"
}), false, "automatic media offer without a prebuilt direct XDM handoff must fail closed");
const networkOnly = createContext();
assert.strictEqual(networkOnly.__xdmInPageBridgeV1.offerNetwork({
  url: "https://cdn.example/master.m3u8",
  manifest: true,
  contentType: "application/vnd.apple.mpegurl",
  displayFallback: true,
  autoOffer: true,
  candidateCount: 1,
  streamKind: "hls",
  prebuiltXdmLink: secureCaptureLink
}), true, "high-confidence HLS network candidate must show with a direct-v3 XDM handoff");
assert.strictEqual(host(networkOnly).dataset.streamKind, "hls");

const encryptedBlobVideo = new FakeVideoElement();
encryptedBlobVideo.currentSrc = "blob:https://page.example/opaque";
const blockedPlayback = createContext({ videos: [encryptedBlobVideo] });
for (const listener of blockedPlayback.__listeners.document.encrypted || []) listener({ type: "encrypted", target: encryptedBlobVideo });
assert.strictEqual(blockedPlayback.__xdmInPageBridgeV1.offerNetwork({
  url: "https://cdn.example/episode/master.m3u8?token=abc",
  manifest: true,
  contentType: "application/vnd.apple.mpegurl",
  displayFallback: true,
  autoOffer: true,
  candidateCount: 2,
  streamKind: "hls",
  prebuiltXdmLink: secureCaptureLink
}), true, "blocked blob playback must not suppress the encrypted network fallback FAB");
assert(host(blockedPlayback), "fallback FAB must remain visible after blocked playback");

const missingHandoff = createContext({ includeHandoff: false });
const failed = missingHandoff.__xdmInPageBridgeV1.showManualWithDiagnostics({ mode: "probe" });
assert.strictEqual(failed.shown, false, "missing handoff must fail visibly");
assert.strictEqual(failed.health.hasHandoff, false);
assert.match(failed.health.lastError, /Handoff script is unavailable/);

console.log("Phase 43A bridge parity tests passed");
