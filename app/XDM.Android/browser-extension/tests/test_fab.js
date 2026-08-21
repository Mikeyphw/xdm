"use strict";
const assert = require("assert");
const path = require("path");

class FakeElement {
  constructor(tagName) {
    this.tagName = String(tagName || "").toUpperCase();
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
  }
  appendChild(node) {
    if (node.parentNode) node.remove();
    this.children.push(node);
    node.parentNode = this;
    node.isConnected = true;
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
  addEventListener(type, listener) { (this.listeners[type] ||= []).push(listener); }
  dispatch(type, event = {}) { for (const listener of this.listeners[type] || []) listener({ type, target: this, ...event }); }
}

function walk(root) {
  const values = [root];
  for (const child of root.children || []) values.push(...walk(child));
  return values;
}

const documentListeners = {};
const documentElement = new FakeElement("html");
const body = new FakeElement("body");
documentElement.appendChild(body);
const document = {
  body,
  documentElement,
  fullscreenElement: null,
  createElement: tag => new FakeElement(tag),
  getElementById(id) { return walk(documentElement).find(node => node.id === id) || null; },
  addEventListener(type, listener) { (documentListeners[type] ||= []).push(listener); }
};

let timerId = 0;
const timers = new Map();
globalThis.setTimeout = (callback, delay) => { const id = ++timerId; timers.set(id, { callback, delay }); return id; };
globalThis.clearTimeout = id => timers.delete(id);
globalThis.document = document;
globalThis.XdmExtensionConfig = {
  themeMode: "dark",
  theme: {
    background: "#090B0F", surface: "#101318", raisedSurface: "#161A21", strongSurface: "#1C212A",
    text: "#E8EDF5", mutedText: "#ABB4C2", primary: "#7DB8FF", onPrimary: "#003259",
    primaryContainer: "#183B59", onPrimaryContainer: "#D2E8FF", outline: "#333A46",
    separator: "#252B35", success: "#79D49A", error: "#FFB4AB",
    fabSizePx: 56, fabCornerRadiusPx: 18, fabEdgeInsetPx: 16, fabActionGapPx: 10,
    motionFastMs: 140, motionStandardMs: 220
  }
};

require(path.join(__dirname, "..", "src", "main", "extension", "xdm-firefox", "fab.js"));
const api = globalThis.XdmLauncherUiV2;
assert(api, "themed FAB API must be exported");
assert.strictEqual(api.hostId, "__xdm_media_fab_host");

assert(api.show({
  target: "xdm",
  links: { xdm: "xdmdownload://capture?v=3&url=https%3A%2F%2Fcdn.example%2Fvideo.mp4&kind=video", oneDm: "idmdownload:https://cdn.example/master.m3u8" },
  candidateCount: 4,
  streamKind: "hls"
}));
let state = api.state();
assert.deepStrictEqual({ target: state.target, candidateCount: state.candidateCount, kind: state.kind }, { target: "xdm", candidateCount: 4, kind: "HLS" });
let host = document.getElementById(api.hostId);
assert(host && host.shadowRoot, "FAB must use an open Shadow DOM root");
let nodes = walk(host.shadowRoot);
const style = nodes.find(node => node.tagName === "STYLE");
assert(style.textContent.includes("env(safe-area-inset-bottom)"));
assert(style.textContent.includes("prefers-reduced-motion"));
assert(style.textContent.includes("width:56px;height:56px"));
assert(nodes.some(node => node.className === "xdm-badge" && node.textContent === "4"));
assert(nodes.some(node => node.className === "xdm-kind" && node.textContent === "HLS"));
const direct = nodes.find(node => node.className === "xdm-fab");
assert.strictEqual(direct.tagName, "A");
assert(String(direct.href).startsWith("xdmdownload://capture"));

assert(api.show({
  target: "ask",
  links: { xdm: "xdmdownload://capture?v=3&url=https%3A%2F%2Fcdn.example%2Fvideo.mp4&kind=video", oneDm: "idmdownload:https://cdn.example/video.mp4" },
  candidateCount: 1,
  streamKind: "video"
}));
host = document.getElementById(api.hostId);
nodes = walk(host.shadowRoot);
const askButton = nodes.find(node => node.className === "xdm-fab");
assert.strictEqual(askButton.tagName, "BUTTON");
assert.strictEqual(askButton.getAttribute("aria-haspopup"), "menu");
askButton.dispatch("click");
state = api.state();
assert.strictEqual(state.expanded, true);
host = document.getElementById(api.hostId);
nodes = walk(host.shadowRoot);
assert.strictEqual(nodes.filter(node => String(node.className).includes("xdm-choice")).length, 2);
assert.strictEqual(nodes.find(node => node.className === "xdm-menu").getAttribute("aria-hidden"), "false");

api.hide();
assert.strictEqual(document.getElementById(api.hostId), null);
assert.strictEqual(api.state(), null);
console.log("themed Shadow DOM FAB tests passed");
