const assert = require("assert");
const path = require("path");

function event() {
  const listeners = [];
  return {
    listeners,
    addListener(fn) { listeners.push(fn); }
  };
}

const storageState = {};
const executeCalls = [];
const events = {
  before: event(),
  headers: event(),
  completed: event(),
  error: event(),
  removed: event(),
  changed: event(),
  message: event()
};

globalThis.browser = {
  storage: {
    local: {
      async get(keys) {
        if (typeof keys === "string") return { [keys]: storageState[keys] };
        if (Array.isArray(keys)) return Object.fromEntries(keys.map(key => [key, storageState[key]]));
        return { ...storageState };
      },
      async set(values) { Object.assign(storageState, values); }
    },
    onChanged: events.changed
  },
  runtime: {
    onMessage: events.message
  },
  webRequest: {
    onBeforeSendHeaders: events.before,
    onHeadersReceived: events.headers,
    onCompleted: events.completed,
    onErrorOccurred: events.error
  },
  tabs: {
    onRemoved: events.removed,
    async get(tabId) { return { id: tabId, url: `https://page.example/watch/${tabId}`, title: `Example video ${tabId}` }; },
    async executeScript(tabId, options) {
      executeCalls.push({ tabId, options });
      return [true];
    }
  }
};

const source = path.join(__dirname, "..", "src", "main", "extension", "xdm-firefox");
require(path.join(source, "detector-core.js"));
require(path.join(source, "candidate-store.js"));
require(path.join(source, "network-observer.js"));

assert.strictEqual(events.before.listeners.length, 1);
assert.strictEqual(events.headers.listeners.length, 1);
assert.strictEqual(events.message.listeners.length, 1, "page observation receiver registered synchronously");

(async () => {
  events.before.listeners[0]({
    tabId: 7,
    requestId: "r1",
    requestHeaders: [
      { name: "User-Agent", value: "IronFox test" },
      { name: "Referer", value: "https://page.example/watch/7" },
      { name: "Authorization", value: "Bearer <redacted>" }
    ]
  });

  events.headers.listeners[0]({
    tabId: 7,
    frameId: 4,
    requestId: "r1",
    url: "https://cdn.example/token?id=42",
    type: "xmlhttprequest",
    responseHeaders: [
      { name: "Content-Type", value: "video/mp4" },
      { name: "Content-Length", value: "5000000" }
    ]
  });

  await new Promise(resolve => setTimeout(resolve, 750));
  assert.ok(executeCalls.some(call => call.tabId === 7 && String(call.options.code || "").includes("offerNetwork")));
  let diagnostics = storageState.xdmNetworkDiagnosticsV1;
  assert.ok(diagnostics && diagnostics["7"]);
  assert.strictEqual(diagnostics["7"].reason, "xhr-media-mime");
  assert.strictEqual(diagnostics["7"].frameCount, 1);

  const messageListener = events.message.listeners[0];
  messageListener({
    type: "xdmPageObservationV1",
    observation: {
      source: "fetch-response",
      requestType: "fetch",
      requestUrl: "https://api.example/player/config",
      responseUrl: "https://api.example/player/config",
      contentType: "application/json",
      requestHeaders: { referer: "https://page.example/watch/8" },
      bodyExcerpt: JSON.stringify({ source: "https://media.example/master.m3u8?token=abc" })
    }
  }, {
    tab: { id: 8 },
    frameId: 9,
    url: "https://embed.example/player"
  });

  await new Promise(resolve => setTimeout(resolve, 650));
  assert.ok(executeCalls.some(call => call.tabId === 8 && String(call.options.code || "").includes("master.m3u8")), "iframe body candidate dispatched to top frame");
  diagnostics = storageState.xdmNetworkDiagnosticsV1;
  assert.ok(diagnostics && diagnostics["8"]);
  assert.ok(diagnostics["8"].bodyCandidates >= 1);
  assert.strictEqual(diagnostics["8"].frameId, 9);
  assert.ok(diagnostics["8"].url.includes("%3Credacted%3E") || diagnostics["8"].url.includes("<redacted>"), "diagnostics redact signed query values");
  assert.ok(!diagnostics["8"].url.includes("token=abc"));

  const beforeSegments = executeCalls.length;
  events.headers.listeners[0]({
    tabId: 9,
    frameId: 0,
    requestId: "r2",
    url: "https://cdn.example/segment_99.m4s",
    type: "xmlhttprequest",
    responseHeaders: [{ name: "Content-Type", value: "video/mp4" }]
  });
  await new Promise(resolve => setTimeout(resolve, 650));
  assert.strictEqual(executeCalls.length, beforeSegments, "segments are ignored");

  console.log("Phase 38 background smoke tests passed");
})().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
