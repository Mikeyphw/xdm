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
    this.status = { connected: false, ready: false, reason: "not_connected" };
  }

  async send(type, payload = {}) {
    await this.ensureConnected();
    const requestId = this.nextRequestId();
    const message = {
      protocolVersion: PROTOCOL_VERSION,
      requestId,
      type,
      sessionId: this.sessionId,
      ...payload
    };
    return this.postAndWait(message);
  }

  async health() {
    try {
      return await this.send("health");
    } catch (error) {
      return { accepted: false, reason: error.message };
    }
  }

  disconnect() {
    if (this.port) {
      try { this.port.disconnect(); } catch { }
    }
    this.handleDisconnect("disconnected");
  }

  async ensureConnected() {
    if (this.port && this.sessionId) return;
    if (this.connectPromise) return this.connectPromise;
    this.connectPromise = this.connectInternal();
    try {
      await this.connectPromise;
    } finally {
      this.connectPromise = null;
    }
  }

  async connectInternal() {
    const port = chrome.runtime.connectNative(HOST_NAME);
    this.port = port;
    port.onMessage.addListener(message => this.handleMessage(message));
    port.onDisconnect.addListener(() => {
      const reason = chrome.runtime.lastError?.message || "native_host_disconnected";
      this.handleDisconnect(reason);
    });
    this.updateStatus({ connected: true, ready: false, reason: "negotiating" });

    try {
      const hello = await this.postAndWait({
        protocolVersion: PROTOCOL_VERSION,
        requestId: this.nextRequestId(),
        type: "hello",
        client: {
          name: detectBrowser(),
          version: navigator.userAgent,
          extensionVersion: chrome.runtime.getManifest().version,
          platform: navigator.platform
        }
      });
      if (!hello.accepted || !hello.sessionId || hello.protocolVersion !== PROTOCOL_VERSION) {
        throw new Error(hello.reason || "protocol_negotiation_failed");
      }
      this.sessionId = hello.sessionId;
      this.updateStatus({ connected: true, ready: true, reason: "ready", hostVersion: hello.hostVersion });
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
      try {
        this.port.postMessage(message);
      } catch (error) {
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
    this.updateStatus({ connected: false, ready: false, reason });
  }

  updateStatus(status) {
    this.status = status;
    this.onStatusChanged(status);
  }

  nextRequestId() {
    this.sequence = (this.sequence + 1) % 1000000;
    return `c-${Date.now().toString(36)}-${this.sequence.toString(36)}`;
  }
}

function detectBrowser() {
  const ua = navigator.userAgent;
  if (/Edg\//.test(ua)) return "Edge";
  if (/OPR\//.test(ua)) return "Opera";
  if (/Vivaldi\//.test(ua)) return "Vivaldi";
  if (/Brave/i.test(ua) || navigator.brave) return "Brave";
  if (/Chromium\//.test(ua)) return "Chromium";
  return "Chrome";
}
