"use strict";

const HOST_NAME = "com.xtremedownloadmanager.xdm";
const PROTOCOL_VERSION = "2.0";
const REQUEST_TIMEOUT_MS = 25000;

export default class NativeConnector {
  constructor(onStatusChanged = () => {}) {
    this.onStatusChanged = onStatusChanged;
    this.port = null;
    this.sessionId = null;
    this.pending = new Map();
    this.connectPromise = null;
    this.sequence = 0;
    this.status = { connected: false, ready: false, reason: "not_connected", compatibility: "unknown" };
  }

  async send(type, payload = {}) {
    await this.ensureConnected();
    const requestId = this.nextRequestId();
    return this.postAndWait({ protocolVersion: PROTOCOL_VERSION, requestId, type, sessionId: this.sessionId, ...payload });
  }

  async health() {
    try { return await this.send("health"); }
    catch (error) { return { accepted: false, reason: error.message, compatibility: this.status.compatibility }; }
  }

  disconnect() {
    if (this.port) { try { this.port.disconnect(); } catch { } }
    this.handleDisconnect("disconnected");
  }

  async ensureConnected() {
    if (this.port && this.sessionId) return;
    if (this.connectPromise) return this.connectPromise;
    this.connectPromise = this.connectInternal();
    try { await this.connectPromise; }
    finally { this.connectPromise = null; }
  }

  async connectInternal() {
    const port = chrome.runtime.connectNative(HOST_NAME);
    this.port = port;
    port.onMessage.addListener(message => this.handleMessage(message));
    port.onDisconnect.addListener(() => this.handleDisconnect(chrome.runtime.lastError?.message || "native_host_disconnected"));
    this.updateStatus({ connected: true, ready: false, reason: "negotiating", compatibility: "unknown" });

    try {
      const permissions = await permissionSnapshot();
      const manifest = chrome.runtime.getManifest();
      const hello = await this.postAndWait({
        protocolVersion: PROTOCOL_VERSION,
        requestId: this.nextRequestId(),
        type: "hello",
        client: {
          name: detectBrowser(),
          version: navigator.userAgent,
          extensionVersion: manifest.version,
          platform: navigator.platform,
          extensionId: chrome.runtime.id,
          manifestVersion: manifest.manifest_version || 3,
          incognitoAllowed: permissions.incognitoAllowed,
          enhancedAccessGranted: permissions.enhancedAccessGranted,
          grantedOrigins: permissions.grantedOrigins
        }
      });
      if (!hello.accepted || !hello.sessionId || hello.protocolVersion !== PROTOCOL_VERSION) {
        throw new Error(hello.reason || "protocol_negotiation_failed");
      }
      this.sessionId = hello.sessionId;
      this.updateStatus({
        connected: true,
        ready: true,
        reason: "ready",
        hostVersion: hello.hostVersion,
        minimumExtensionVersion: hello.minimumExtensionVersion,
        compatibility: hello.compatibility || "compatible",
        capabilities: hello.capabilities || []
      });
    } catch (error) {
      try { port.disconnect(); } catch { }
      this.handleDisconnect(error.message || "protocol_negotiation_failed");
      throw error;
    }
  }

  postAndWait(message) {
    if (!this.port) return Promise.reject(new Error("native_host_not_connected"));
    return new Promise((resolve, reject) => {
      const timeout = setTimeout(() => {
        this.pending.delete(message.requestId);
        reject(new Error("native_message_timeout"));
      }, REQUEST_TIMEOUT_MS);
      this.pending.set(message.requestId, { resolve, reject, timeout, expectedType: `${message.type}-ack` });
      try { this.port.postMessage(message); }
      catch (error) {
        clearTimeout(timeout);
        this.pending.delete(message.requestId);
        reject(error);
      }
    });
  }

  handleMessage(message) {
    const pending = message?.requestId ? this.pending.get(message.requestId) : null;
    if (!pending) return;
    clearTimeout(pending.timeout);
    this.pending.delete(message.requestId);
    const validType = message.type === pending.expectedType || message.type === "protocol-error";
    const validSession = message.accepted !== true || !this.sessionId || message.sessionId === this.sessionId;
    if (message.protocolVersion !== PROTOCOL_VERSION || typeof message.accepted !== "boolean" || !validType || !validSession) {
      pending.reject(new Error("invalid_native_response"));
      return;
    }
    pending.resolve(message);
  }

  handleDisconnect(reason) {
    this.port = null;
    this.sessionId = null;
    for (const pending of this.pending.values()) {
      clearTimeout(pending.timeout);
      pending.reject(new Error(reason));
    }
    this.pending.clear();
    this.updateStatus({ connected: false, ready: false, reason, compatibility: reason === "extension_outdated" ? "extension_outdated" : "unknown" });
  }

  updateStatus(status) { this.status = status; this.onStatusChanged(status); }
  nextRequestId() { this.sequence = (this.sequence + 1) % 1000000; return `c-${Date.now().toString(36)}-${this.sequence.toString(36)}`; }
}

async function permissionSnapshot() {
  const all = await new Promise(resolve => chrome.permissions.getAll(resolve));
  const incognitoAllowed = await new Promise(resolve => chrome.extension?.isAllowedIncognitoAccess
    ? chrome.extension.isAllowedIncognitoAccess(resolve)
    : resolve(false));
  const grantedOrigins = (all.origins || []).filter(origin => /^https?:/i.test(origin));
  return {
    incognitoAllowed,
    grantedOrigins,
    enhancedAccessGranted: grantedOrigins.length > 0 && (all.permissions || []).includes("webRequest") && (all.permissions || []).includes("cookies")
  };
}

function detectBrowser() {
  const ua = navigator.userAgent;
  if (/Edg\//.test(ua)) return "Edge";
  if (/OPR\//.test(ua)) return "Opera";
  if (/Vivaldi\//.test(ua)) return "Vivaldi";
  if (/Brave/i.test(ua) || navigator.brave) return "Brave";
  if (/Firefox\//.test(ua)) return "Firefox";
  if (/Chromium\//.test(ua)) return "Chromium";
  return "Chrome";
}
