"use strict";

let currentHost = "";
let currentStatus = null;

document.addEventListener("DOMContentLoaded", async () => {
  currentStatus = await send({ type: "status" });
  document.getElementById("extensionId").textContent = currentStatus.extensionId || chrome.runtime.id;
  renderRules(currentStatus.rules || {});
  renderHealth(currentStatus.connector);
  renderPermissions(currentStatus.permissions || {});
  renderPending(currentStatus.pending || []);
  await loadCurrentSite();

  document.getElementById("save").addEventListener("click", async () => {
    const response = await send({ type: "save-rules", rules: collectRules() });
    showResult(response.ok ? "Rules saved." : response.reason || "Could not save rules.");
  });
  document.getElementById("disable").addEventListener("click", async () => {
    await send({ type: "disable-for", minutes: 10 });
    showResult("Automatic capture disabled for 10 minutes.");
  });
  document.getElementById("enableNow").addEventListener("click", async () => {
    const response = await send({ type: "save-rules", rules: { enabled: true, disabledUntilUtc: null } });
    renderRules(response.rules || collectRules());
    showResult("Capture enabled.");
  });
  document.getElementById("enabled").addEventListener("change", async event => {
    await send({ type: "save-rules", rules: { enabled: event.target.checked } });
  });
  document.getElementById("currentSiteMode").addEventListener("change", async event => {
    if (!currentHost) return;
    const response = await send({ type: "save-site-policy", host: currentHost, mode: event.target.value });
    currentStatus.rules = response.rules || currentStatus.rules;
    showResult(`Saved ${event.target.options[event.target.selectedIndex].text.toLowerCase()} for ${currentHost}.`);
  });
  document.getElementById("grantAccess").addEventListener("click", async () => {
    const granted = await requestPermissions({ permissions: ["cookies", "webRequest"], origins: ["http://*/*", "https://*/*"] });
    await send({ type: "permissions-changed" });
    const refreshed = await send({ type: "status" });
    renderPermissions(refreshed.permissions || {});
    showResult(granted ? "Enhanced metadata access granted." : "Enhanced access was not granted.");
  });
  document.getElementById("removeAccess").addEventListener("click", async () => {
    const removed = await removePermissions({ permissions: ["cookies", "webRequest"], origins: ["http://*/*", "https://*/*"] });
    await send({ type: "permissions-changed" });
    const refreshed = await send({ type: "status" });
    renderPermissions(refreshed.permissions || {});
    showResult(removed ? "Enhanced access removed." : "No optional access was removed.");
  });
});

function send(message) {
  return new Promise(resolve => chrome.runtime.sendMessage(message, response => resolve(response || { ok: false, reason: chrome.runtime.lastError?.message })));
}
function requestPermissions(value) { return new Promise(resolve => chrome.permissions.request(value, resolve)); }
function removePermissions(value) { return new Promise(resolve => chrome.permissions.remove(value, resolve)); }

async function loadCurrentSite() {
  const tabs = await new Promise(resolve => chrome.tabs.query({ active: true, currentWindow: true }, resolve));
  try {
    const url = new URL(tabs?.[0]?.url || "");
    if (!/^https?:$/.test(url.protocol)) return;
    currentHost = url.hostname.toLowerCase();
    document.getElementById("currentSite").textContent = currentHost;
    document.getElementById("currentSiteMode").value = resolveSiteMode(currentHost, currentStatus.rules || {});
  } catch { }
}

function renderHealth(status) {
  const element = document.getElementById("health");
  element.textContent = status?.ready ? `Ready${status.hostVersion ? ` • host ${status.hostVersion}` : ""}` : `Unavailable • ${friendly(status?.reason || "native host not connected")}`;
  element.dataset.ready = status?.ready ? "true" : "false";
  document.getElementById("compatibility").textContent = status?.compatibility && status.compatibility !== "compatible"
    ? `Compatibility: ${friendly(status.compatibility)}${status.minimumExtensionVersion ? ` • requires ${status.minimumExtensionVersion}+` : ""}`
    : "Protocol and extension versions compatible";
}
function renderPermissions(status) {
  const granted = status.enhancedAccessGranted === true;
  document.getElementById("permissionSummary").textContent = granted
    ? `Enhanced access active for ${(status.origins || []).length} origin pattern(s).`
    : "Least-privilege mode: URL takeover only; cookies and request metadata are not read.";
  document.getElementById("grantAccess").hidden = granted;
  document.getElementById("removeAccess").hidden = !granted;
}
function renderPending(items) {
  const section = document.getElementById("pendingSection");
  const list = document.getElementById("pendingList");
  list.textContent = "";
  section.hidden = items.length === 0;
  for (const item of items) {
    const row = document.createElement("div"); row.className = "pending-item";
    const text = document.createElement("div"); text.innerHTML = `<strong></strong><span></span>`;
    text.querySelector("strong").textContent = item.fileName || item.host;
    text.querySelector("span").textContent = item.host;
    const actions = document.createElement("div"); actions.className = "pending-actions";
    const accept = document.createElement("button"); accept.className = "primary"; accept.textContent = "Send to XDM";
    const reject = document.createElement("button"); reject.textContent = "Keep in browser";
    accept.addEventListener("click", async () => { const result = await send({ type: "accept-pending", id: item.id }); showResult(result.ok ? "Download handed to XDM." : friendly(result.reason)); await refreshPending(); });
    reject.addEventListener("click", async () => { await send({ type: "reject-pending", id: item.id }); await refreshPending(); });
    actions.append(accept, reject); row.append(text, actions); list.append(row);
  }
}
async function refreshPending() { const status = await send({ type: "status" }); renderPending(status.pending || []); }
function renderRules(rules) {
  document.getElementById("enabled").checked = rules.enabled !== false;
  document.getElementById("incognito").checked = rules.captureIncognito === true;
  document.getElementById("defaultSiteMode").value = rules.defaultSiteMode || "ask";
  document.getElementById("minimumSize").value = ((Number(rules.minimumSizeBytes) || 0) / 1048576).toString();
  for (const id of ["excludedSites", "includedSites", "blockedMimeTypes", "allowedMimeTypes", "blockedExtensions", "allowedExtensions"]) document.getElementById(id).value = (rules[id] || []).join(", ");
}
function collectRules() {
  const list = id => document.getElementById(id).value.split(/[\n,;]+/).map(value => value.trim()).filter(Boolean);
  return {
    enabled: document.getElementById("enabled").checked,
    captureIncognito: document.getElementById("incognito").checked,
    defaultSiteMode: document.getElementById("defaultSiteMode").value,
    minimumSizeBytes: Math.round((Number(document.getElementById("minimumSize").value) || 0) * 1048576),
    excludedSites: list("excludedSites"), includedSites: list("includedSites"),
    blockedMimeTypes: list("blockedMimeTypes"), allowedMimeTypes: list("allowedMimeTypes"),
    blockedExtensions: list("blockedExtensions"), allowedExtensions: list("allowedExtensions")
  };
}
function resolveSiteMode(host, rules) {
  if ((rules.excludedSites || []).some(pattern => host === pattern || host.endsWith(`.${pattern}`))) return "never";
  const policies = [...(rules.sitePolicies || [])].filter(policy => host === policy.pattern || host.endsWith(`.${policy.pattern}`)).sort((a, b) => b.pattern.length - a.pattern.length);
  return policies[0]?.mode || rules.defaultSiteMode || "ask";
}
function friendly(value) { return String(value || "unknown").replaceAll("_", " "); }
function showResult(text) { document.getElementById("result").textContent = text; }
