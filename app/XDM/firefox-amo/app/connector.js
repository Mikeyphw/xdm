"use strict";

const XDM_HOST_NAME = "com.xtremedownloadmanager.xdm";
const XDM_PROTOCOL_VERSION = "2.0";
const XDM_REQUEST_TIMEOUT_MS = 25000;

class NativeConnector {
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
    return this.postAndWait({ protocolVersion: XDM_PROTOCOL_VERSION, requestId, type, sessionId: this.sessionId, ...payload });
  }

  async health() {
    try { return await this.send("health"); }
    catch (error) { return { accepted: false, reason: error.message }; }
  }

  async ensureConnected() {
    if (this.port && this.sessionId) return;
    if (this.connectPromise) return this.connectPromise;
    this.connectPromise = this.connectInternal();
    try { await this.connectPromise; }
    finally { this.connectPromise = null; }
  }

  async connectInternal() {
    const port = browser.runtime.connectNative(XDM_HOST_NAME);
    this.port = port;
    port.onMessage.addListener(message => this.handleMessage(message));
    port.onDisconnect.addListener(disconnectedPort => this.handleDisconnect(disconnectedPort?.error?.message || "native_host_disconnected"));
    this.updateStatus({ connected: true, ready: false, reason: "negotiating" });
    try {
      const hello = await this.postAndWait({
        protocolVersion: XDM_PROTOCOL_VERSION,
        requestId: this.nextRequestId(),
        type: "hello",
        client: {
          name: "Firefox",
          version: navigator.userAgent,
          extensionVersion: browser.runtime.getManifest().version,
          platform: navigator.platform
        }
      });
      if (!hello.accepted || !hello.sessionId || hello.protocolVersion !== XDM_PROTOCOL_VERSION) {
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
      }, XDM_REQUEST_TIMEOUT_MS);
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
    if (message.protocolVersion !== XDM_PROTOCOL_VERSION || typeof message.accepted !== "boolean" || !validType || !validSession) {
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

  updateStatus(status) { this.status = status; this.onStatusChanged(status); }
  nextRequestId() { this.sequence = (this.sequence + 1) % 1000000; return `f-${Date.now().toString(36)}-${this.sequence.toString(36)}`; }
}
