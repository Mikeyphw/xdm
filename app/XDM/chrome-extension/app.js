"use strict";

import NativeConnector from "./connector.js";

const DEFAULT_RULES = Object.freeze({
  enabled: true,
  disabledUntilUtc: null,
  captureIncognito: false,
  minimumSizeBytes: 0,
  allowedMimeTypes: [],
  blockedMimeTypes: [],
  allowedExtensions: [],
  blockedExtensions: [],
  includedSites: [],
  excludedSites: []
});
const MAX_BATCH_ITEMS = 25;
const METADATA_TTL_MS = 120000;

export default class App {
  constructor() {
    this.rules = { ...DEFAULT_RULES };
    this.connector = new NativeConnector(status => this.onConnectorStatus(status));
    this.processingDownloads = new Set();
    this.requestMetadata = new Map();
    this.lastResult = null;
  }

  async start() {
    await this.loadRules();
    this.registerRequestMetadataCapture();
    this.registerDownloadTakeover();
    this.registerContextMenus();
    this.registerRuntimeMessages();
    chrome.alarms.create("xdm-health", { periodInMinutes: 1 });
    chrome.alarms.onAlarm.addListener(alarm => {
      if (alarm.name === "xdm-health") this.refreshHealth();
    });
    await this.refreshHealth();
  }

  async loadRules() {
    const stored = await chrome.storage.local.get("captureRules");
    this.rules = normalizeRules(stored.captureRules || DEFAULT_RULES);
  }

  async saveRules(rules) {
    this.rules = normalizeRules({ ...this.rules, ...rules });
    await chrome.storage.local.set({ captureRules: this.rules });
    await this.refreshHealth();
    return this.rules;
  }

  registerDownloadTakeover() {
    chrome.downloads.onCreated.addListener(item => {
      void this.handleCreatedDownload(item);
    });
  }

  async handleCreatedDownload(initialItem) {
    if (!initialItem?.id || this.processingDownloads.has(initialItem.id)) return;
    this.processingDownloads.add(initialItem.id);
    try {
      await delay(120);
      const [item] = await chrome.downloads.search({ id: initialItem.id });
      if (!item || item.state === "complete") return;
      const capture = await this.createCapture(item.finalUrl || item.url, {
        requestId: `download-${item.id}-${Date.now()}`,
        fileName: leafName(item.filename),
        mimeType: item.mime || null,
        fileSize: positiveSize(item.fileSize || item.totalBytes),
        referer: item.referrer || null,
        sourcePage: item.referrer || null,
        isIncognito: item.incognito === true,
        operation: "automatic",
        bypassRules: false
      });
      const localDecision = evaluateRules(capture, this.rules);
      if (!localDecision.accepted) {
        this.lastResult = localDecision;
        return;
      }

      const response = await this.connector.send("capture", { capture, rules: this.rules });
      const accepted = response.accepted === true && response.items?.[0]?.accepted !== false;
      this.lastResult = { accepted, reason: response.reason || "unknown", url: capture.url };
      if (accepted) {
        await chrome.downloads.cancel(item.id);
        await chrome.downloads.erase({ id: item.id });
      }
    } catch (error) {
      this.lastResult = { accepted: false, reason: error.message };
      this.onConnectorStatus({ connected: false, ready: false, reason: error.message });
    } finally {
      this.processingDownloads.delete(initialItem.id);
    }
  }

  registerRequestMetadataCapture() {
    chrome.webRequest.onBeforeRequest.addListener(
      details => {
        const entry = this.getMetadataEntry(details.url);
        entry.method = details.method || "GET";
        entry.requestBodyBase64 = encodeRequestBody(details.requestBody);
        entry.updatedAt = Date.now();
        this.requestMetadata.set(details.url, entry);
      },
      { urls: ["http://*/*", "https://*/*"] },
      ["requestBody"]
    );
    chrome.webRequest.onBeforeSendHeaders.addListener(
      details => {
        const entry = this.getMetadataEntry(details.url);
        for (const header of details.requestHeaders || []) {
          const name = header.name.toLowerCase();
          const value = header.value || "";
          if (name === "referer") entry.referer = value;
          else if (name === "user-agent") entry.userAgent = value;
          else if (name === "content-type") entry.requestBodyContentType = value;
          else if (["accept", "accept-language", "cache-control", "dnt", "origin", "pragma", "x-requested-with"].includes(name)) {
            entry.headers[header.name] = value;
          }
        }
        entry.updatedAt = Date.now();
        this.requestMetadata.set(details.url, entry);
      },
      { urls: ["http://*/*", "https://*/*"] },
      ["requestHeaders", "extraHeaders"]
    );
  }

  getMetadataEntry(url) {
    this.pruneMetadata();
    return this.requestMetadata.get(url) || { headers: {}, updatedAt: Date.now() };
  }

  pruneMetadata() {
    const threshold = Date.now() - METADATA_TTL_MS;
    for (const [url, value] of this.requestMetadata) {
      if (value.updatedAt < threshold) this.requestMetadata.delete(url);
    }
  }

  async createCapture(url, options = {}) {
    const metadata = this.getMetadataEntry(url);
    const cookies = await chrome.cookies.getAll({ url });
    const method = (metadata.method || options.method || "GET").toUpperCase();
    return {
      url,
      requestId: options.requestId || `capture-${Date.now()}-${Math.random().toString(36).slice(2, 9)}`,
      fileName: options.fileName || null,
      headers: metadata.headers,
      cookie: cookies.map(cookie => `${cookie.name}=${cookie.value}`).join("; ") || null,
      referer: options.referer || metadata.referer || null,
      userAgent: metadata.userAgent || navigator.userAgent,
      browser: detectBrowser(),
      mimeType: options.mimeType || null,
      fileSize: options.fileSize ?? null,
      method,
      requestBodyBase64: method === "POST" ? metadata.requestBodyBase64 || null : null,
      requestBodyContentType: metadata.requestBodyContentType || null,
      sourcePage: options.sourcePage || null,
      operation: options.operation || "context",
      isIncognito: options.isIncognito === true,
      bypassRules: options.bypassRules === true
    };
  }

  registerContextMenus() {
    chrome.runtime.onInstalled.addListener(() => this.createContextMenus());
    this.createContextMenus();
    chrome.contextMenus.onClicked.addListener((info, tab) => void this.handleContextMenu(info, tab));
  }

  createContextMenus() {
    chrome.contextMenus.removeAll(() => {
      chrome.contextMenus.create({ id: "xdm-download", title: "Download with XDM", contexts: ["link", "image", "video", "audio", "page"] });
      chrome.contextMenus.create({ id: "xdm-download-media", title: "Download media with XDM", contexts: ["video", "audio", "image"] });
      chrome.contextMenus.create({ id: "xdm-download-all", title: "Download all links with XDM", contexts: ["page"] });
    });
  }

  async handleContextMenu(info, tab) {
    if (info.menuItemId === "xdm-download-all") {
      await this.downloadAllLinks(tab);
      return;
    }
    const url = info.linkUrl || info.srcUrl || info.pageUrl;
    if (!isHttpUrl(url)) return;
    const capture = await this.createCapture(url, {
      referer: info.pageUrl || tab?.url || null,
      sourcePage: info.pageUrl || tab?.url || null,
      operation: info.menuItemId === "xdm-download-media" ? "media" : "context",
      isIncognito: tab?.incognito === true,
      bypassRules: true
    });
    this.lastResult = await this.connector.send("capture", { capture, rules: this.rules });
  }

  async downloadAllLinks(tab) {
    if (!tab?.id) return;
    const results = await chrome.scripting.executeScript({
      target: { tabId: tab.id },
      func: () => Array.from(new Set(Array.from(document.links, link => link.href).filter(url => /^https?:/i.test(url)))).slice(0, 500)
    });
    const urls = results?.[0]?.result || [];
    for (let offset = 0; offset < urls.length; offset += MAX_BATCH_ITEMS) {
      const captures = await Promise.all(urls.slice(offset, offset + MAX_BATCH_ITEMS).map(url => this.createCapture(url, {
        referer: tab.url,
        sourcePage: tab.url,
        operation: "download-all",
        isIncognito: tab.incognito === true,
        bypassRules: true
      })));
      this.lastResult = await this.connector.send("capture-batch", { captures, rules: this.rules });
    }
  }

  registerRuntimeMessages() {
    chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
      void this.handleRuntimeMessage(message).then(sendResponse, error => sendResponse({ ok: false, reason: error.message }));
      return true;
    });
  }

  async handleRuntimeMessage(message) {
    if (message?.type === "status") {
      return { ok: true, rules: this.rules, connector: this.connector.status, lastResult: this.lastResult, extensionId: chrome.runtime.id };
    }
    if (message?.type === "save-rules") {
      return { ok: true, rules: await this.saveRules(message.rules || {}) };
    }
    if (message?.type === "disable-for") {
      const minutes = Math.max(0, Math.min(1440, Number(message.minutes) || 0));
      return { ok: true, rules: await this.saveRules({ disabledUntilUtc: minutes ? new Date(Date.now() + minutes * 60000).toISOString() : null }) };
    }
    if (message?.type === "health") {
      const health = await this.connector.health();
      return { ok: true, health };
    }
    return { ok: false, reason: "unknown_message" };
  }

  async refreshHealth() {
    const health = await this.connector.health();
    this.onConnectorStatus({ connected: health.accepted === true, ready: health.accepted === true, reason: health.reason || "unavailable" });
  }

  onConnectorStatus(status) {
    const disabled = !this.rules.enabled || (this.rules.disabledUntilUtc && Date.parse(this.rules.disabledUntilUtc) > Date.now());
    const text = disabled ? "OFF" : status.ready ? "" : "!";
    chrome.action.setBadgeText({ text });
    chrome.action.setTitle({ title: status.ready ? "XDM integration ready" : `XDM integration: ${status.reason || "unavailable"}` });
  }
}

function normalizeRules(value) {
  const array = input => Array.isArray(input) ? input.map(item => String(item).trim().toLowerCase()).filter(Boolean).slice(0, 256) : [];
  return {
    enabled: value?.enabled !== false,
    disabledUntilUtc: value?.disabledUntilUtc || null,
    captureIncognito: value?.captureIncognito === true,
    minimumSizeBytes: Math.max(0, Number(value?.minimumSizeBytes) || 0),
    allowedMimeTypes: array(value?.allowedMimeTypes),
    blockedMimeTypes: array(value?.blockedMimeTypes),
    allowedExtensions: array(value?.allowedExtensions).map(item => item.replace(/^\./, "")),
    blockedExtensions: array(value?.blockedExtensions).map(item => item.replace(/^\./, "")),
    includedSites: array(value?.includedSites),
    excludedSites: array(value?.excludedSites)
  };
}

function evaluateRules(capture, rules) {
  if (!rules.enabled) return { accepted: false, reason: "capture_disabled" };
  if (rules.disabledUntilUtc && Date.parse(rules.disabledUntilUtc) > Date.now()) return { accepted: false, reason: "temporarily_disabled" };
  if (capture.isIncognito && !rules.captureIncognito) return { accepted: false, reason: "incognito_disabled" };
  if (capture.bypassRules && ["context", "download-all", "media"].includes(capture.operation)) return { accepted: true, reason: "manual_capture" };
  const host = new URL(capture.url).hostname.toLowerCase();
  if (matchesSite(host, rules.excludedSites)) return { accepted: false, reason: "site_excluded" };
  if (rules.includedSites.length && !matchesSite(host, rules.includedSites)) return { accepted: false, reason: "site_not_included" };
  if (capture.fileSize != null && capture.fileSize < rules.minimumSizeBytes) return { accepted: false, reason: "below_minimum_size" };
  const mime = (capture.mimeType || "").split(";", 1)[0].toLowerCase();
  if (mime && matchesMime(mime, rules.blockedMimeTypes)) return { accepted: false, reason: "mime_blocked" };
  if (rules.allowedMimeTypes.length && (!mime || !matchesMime(mime, rules.allowedMimeTypes))) return { accepted: false, reason: "mime_not_allowed" };
  const extension = extensionOf(capture.fileName || new URL(capture.url).pathname);
  if (extension && rules.blockedExtensions.includes(extension)) return { accepted: false, reason: "extension_blocked" };
  if (rules.allowedExtensions.length && (!extension || !rules.allowedExtensions.includes(extension))) return { accepted: false, reason: "extension_not_allowed" };
  return { accepted: true, reason: "accepted" };
}

function matchesSite(host, patterns) {
  return patterns.some(raw => {
    const pattern = raw.replace(/^\*?\.?/, "");
    return host === pattern || host.endsWith(`.${pattern}`);
  });
}
function matchesMime(mime, patterns) {
  return patterns.some(pattern => pattern.endsWith("/*") ? mime.startsWith(pattern.slice(0, -1)) : mime === pattern);
}
function extensionOf(value) {
  const match = /\.([a-z0-9]{1,16})(?:$|[?#])/i.exec(value || "");
  return match ? match[1].toLowerCase() : null;
}
function encodeRequestBody(requestBody) {
  try {
    if (requestBody?.raw?.[0]?.bytes) {
      const bytes = new Uint8Array(requestBody.raw[0].bytes);
      if (bytes.byteLength > 16384) return null;
      let binary = "";
      for (const value of bytes) binary += String.fromCharCode(value);
      return btoa(binary);
    }
    if (requestBody?.formData) {
      const params = new URLSearchParams();
      for (const [name, values] of Object.entries(requestBody.formData)) for (const value of values) params.append(name, value);
      const text = params.toString();
      return text.length <= 16384 ? btoa(unescape(encodeURIComponent(text))) : null;
    }
  } catch { }
  return null;
}
function positiveSize(value) { return Number.isFinite(value) && value >= 0 ? value : null; }
function leafName(path) { return path ? path.split(/[\\/]/).pop() || null : null; }
function isHttpUrl(url) { try { return ["http:", "https:"].includes(new URL(url).protocol); } catch { return false; } }
function delay(ms) { return new Promise(resolve => setTimeout(resolve, ms)); }
function detectBrowser() {
  const ua = navigator.userAgent;
  if (/Edg\//.test(ua)) return "Edge";
  if (/OPR\//.test(ua)) return "Opera";
  if (/Vivaldi\//.test(ua)) return "Vivaldi";
  if (/Brave/i.test(ua) || navigator.brave) return "Brave";
  if (/Chromium\//.test(ua)) return "Chromium";
  return "Chrome";
}
