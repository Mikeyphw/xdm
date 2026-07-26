const defaults = {
  enabled: true,
  autoDetectPlayingVideos: true,
  defaultTarget: (globalThis.XdmExtensionConfig && globalThis.XdmExtensionConfig.defaultTarget) || "xdm",
  siteMode: "all",
  blacklist: [],
  whitelist: []
};

let activeTab = null;
const NETWORK_STATUS_KEY = "xdmNetworkStatusV1";
const NETWORK_DIAGNOSTICS_KEY = "xdmNetworkDiagnosticsV1";
let diagnosticTimer = null;

function setStatus(message, kind = "") {
  const node = document.getElementById("status");
  node.textContent = message;
  node.className = `status ${kind}`.trim();
}

async function getSettings() {
  const result = await browser.storage.local.get("settings");
  return Object.assign({}, defaults, result.settings || {});
}

async function saveSettings() {
  const mode = document.getElementById("siteMode").value;
  const entries = document.getElementById("siteList").value
    .split(/\r?\n|,/)
    .map(value => value.trim().toLowerCase().replace(/^https?:\/\//, "").replace(/\/.*$/, ""))
    .filter(Boolean);
  const current = await getSettings();
  const next = Object.assign({}, current, {
    enabled: document.getElementById("enabled").checked,
    autoDetectPlayingVideos: document.getElementById("autoDetect").checked,
    defaultTarget: document.getElementById("defaultTarget").value,
    siteMode: mode
  });
  if (mode === "blacklist") next.blacklist = [...new Set(entries)];
  if (mode === "whitelist") next.whitelist = [...new Set(entries)];
  await browser.storage.local.set({ settings: next });
  setStatus("Saved. Reload existing video tabs so the updated detector can attach.", "ok");
}

function updateList(settings) {
  const mode = document.getElementById("siteMode").value;
  const values = mode === "whitelist" ? settings.whitelist : settings.blacklist;
  document.getElementById("siteList").value = mode === "all" ? "" : (values || []).join("\n");
  document.getElementById("siteCard").style.opacity = mode === "all" ? ".82" : "1";
}

function validHttpUrl(value) {
  try {
    const url = new URL(value);
    return /^https?:$/.test(url.protocol) ? url.href : "";
  } catch (_) {
    return "";
  }
}

async function ensureBridge(tabId) {
  for (const file of ["generated-config.js", "handoff.js", "fab.js", "frame-bridge.js"]) {
    await browser.tabs.executeScript(tabId, {
      file,
      allFrames: true,
      runAt: "document_idle"
    });
  }
}

async function showLauncher(input) {
  if (!activeTab || typeof activeTab.id !== "number") throw new Error("No active webpage tab is available.");
  if (!/^https?:/i.test(activeTab.url || "")) throw new Error("Open a normal HTTP or HTTPS webpage first.");
  await ensureBridge(activeTab.id);
  const payload = JSON.stringify(input || {});
  const code = `(() => { const bridge = globalThis.__xdmInPageBridgeV1; if (!bridge) return false; return bridge.showManual(${payload}); })();`;
  const results = await browser.tabs.executeScript(activeTab.id, {
    code,
    allFrames: false,
    runAt: "document_idle"
  });
  if (!results || results[0] !== true) throw new Error("The page launcher could not be created.");
  setStatus("Themed FAB added to the webpage. Close this panel, then tap it.", "ok");
}

async function handle(action) {
  try {
    if (action === "page") {
      await showLauncher({ url: activeTab.url, title: activeTab.title || "Web page", label: "Send page" });
    } else if (action === "probe") {
      await showLauncher({ mode: "probe", force: true });
    } else if (action === "manual") {
      const url = validHttpUrl(document.getElementById("manualUrl").value.trim());
      if (!url) throw new Error("Enter a complete HTTP or HTTPS URL.");
      await showLauncher({ url, title: activeTab && activeTab.title ? activeTab.title : "Download", label: "Send URL" });
    }
  } catch (error) {
    setStatus(error && error.message ? error.message : String(error), "error");
  }
}

async function refreshDiagnostics() {
  const detectorNode = document.getElementById("detectorState");
  const mediaNode = document.getElementById("lastDetectedMedia");
  if (!detectorNode || !mediaNode) return;
  try {
    const result = await browser.storage.local.get([NETWORK_STATUS_KEY, NETWORK_DIAGNOSTICS_KEY]);
    const status = result[NETWORK_STATUS_KEY] || {};
    if (status.active) {
      detectorNode.textContent = "Network observer active";
      detectorNode.className = "host ok";
    } else if (status.lastError) {
      detectorNode.textContent = `Observer error: ${status.lastError}`;
      detectorNode.className = "host error";
    } else {
      detectorNode.textContent = "Network observer has not started";
      detectorNode.className = "host error";
    }

    const all = result[NETWORK_DIAGNOSTICS_KEY] || {};
    const diagnostic = activeTab && typeof activeTab.id === "number" ? all[String(activeTab.id)] : null;
    if (diagnostic && diagnostic.url) {
      const age = Math.max(0, Math.round((Date.now() - Number(diagnostic.at || 0)) / 1000));
      const stats = `${Number(diagnostic.webResponses || 0)} net · ${Number(diagnostic.pageResponses || 0)} page · ${Number(diagnostic.bodyCandidates || 0)} body · ${Number(diagnostic.frameCount || 0)} frame(s)`;
      mediaNode.textContent = `${diagnostic.reason || "media"} · ${age}s ago · ${stats} · ${diagnostic.url}`;
      mediaNode.title = `${diagnostic.url}
${stats}`;
    } else {
      mediaNode.textContent = "No media response captured on this tab yet";
      mediaNode.removeAttribute("title");
    }
  } catch (error) {
    detectorNode.textContent = error && error.message ? error.message : String(error);
    detectorNode.className = "host error";
  }
}

(async () => {
  try {
    const settings = await getSettings();
    document.getElementById("enabled").checked = settings.enabled !== false;
    document.getElementById("autoDetect").checked = settings.autoDetectPlayingVideos !== false;
    document.getElementById("defaultTarget").value = settings.defaultTarget || "xdm";
    document.getElementById("siteMode").value = settings.siteMode || "all";
    updateList(settings);

    const tabs = await browser.tabs.query({ active: true, currentWindow: true });
    activeTab = tabs[0] || null;
    if (activeTab && /^https?:/i.test(activeTab.url || "")) {
      document.getElementById("currentHost").textContent = new URL(activeTab.url).hostname;
      document.getElementById("sendPage").disabled = false;
      document.getElementById("appTest").disabled = false;
      document.getElementById("rescan").disabled = false;
    } else {
      document.getElementById("currentHost").textContent = "Open a normal webpage to use the bridge";
    }
    await refreshDiagnostics();
    diagnosticTimer = setInterval(refreshDiagnostics, 1200);
  } catch (error) {
    setStatus(error && error.message ? error.message : String(error), "error");
  }
})();

document.getElementById("manualUrl").addEventListener("input", event => {
  document.getElementById("sendManual").disabled = !validHttpUrl(event.target.value.trim()) || !activeTab;
});
document.getElementById("siteMode").addEventListener("change", async () => updateList(await getSettings()));
document.getElementById("save").addEventListener("click", saveSettings);
document.getElementById("sendPage").addEventListener("click", () => handle("page"));
document.getElementById("appTest").addEventListener("click", () => handle("probe"));
document.getElementById("sendManual").addEventListener("click", () => handle("manual"));

window.addEventListener("unload", () => {
  if (diagnosticTimer) clearInterval(diagnosticTimer);
});

document.getElementById("rescan").addEventListener("click", async () => {
  try {
    if (!activeTab || typeof activeTab.id !== "number") throw new Error("No active webpage tab is available.");
    await ensureBridge(activeTab.id);
    const results = await browser.tabs.executeScript(activeTab.id, {
      code: `(() => { const bridge = globalThis.__xdmInPageBridgeV1; return bridge ? bridge.rescan() : false; })();`,
      allFrames: true,
      runAt: "document_idle"
    });
    if (!results || !results.some(Boolean)) throw new Error("No page detector was reachable.");
    setStatus("Rescan requested. Start or resume the video, then watch for the themed page FAB.", "ok");
    await refreshDiagnostics();
  } catch (error) {
    setStatus(error && error.message ? error.message : String(error), "error");
  }
});
