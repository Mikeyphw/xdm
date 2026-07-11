"use strict";

document.addEventListener("DOMContentLoaded", async () => {
  const status = await send({ type: "status" });
  document.getElementById("extensionId").textContent = status.extensionId || chrome.runtime.id;
  renderRules(status.rules || {});
  renderHealth(status.connector);

  document.getElementById("save").addEventListener("click", async () => {
    const response = await send({ type: "save-rules", rules: collectRules() });
    showResult(response.ok ? "Rules saved." : response.reason || "Could not save rules.");
  });
  document.getElementById("disable").addEventListener("click", async () => {
    await send({ type: "disable-for", minutes: 10 });
    showResult("Automatic capture disabled for 10 minutes.");
  });
  document.getElementById("enableNow").addEventListener("click", async () => {
    const rules = collectRules();
    rules.enabled = true;
    rules.disabledUntilUtc = null;
    const response = await send({ type: "save-rules", rules });
    renderRules(response.rules || rules);
    showResult("Capture enabled.");
  });
  document.getElementById("enabled").addEventListener("change", async event => {
    await send({ type: "save-rules", rules: { enabled: event.target.checked } });
  });
});

function send(message) {
  return new Promise(resolve => chrome.runtime.sendMessage(message, response => resolve(response || { ok: false, reason: chrome.runtime.lastError?.message })));
}
function renderHealth(status) {
  const element = document.getElementById("health");
  element.textContent = status?.ready ? `Ready${status.hostVersion ? ` • host ${status.hostVersion}` : ""}` : `Unavailable • ${status?.reason || "native host not connected"}`;
  element.dataset.ready = status?.ready ? "true" : "false";
}
function renderRules(rules) {
  document.getElementById("enabled").checked = rules.enabled !== false;
  document.getElementById("incognito").checked = rules.captureIncognito === true;
  document.getElementById("minimumSize").value = ((Number(rules.minimumSizeBytes) || 0) / 1048576).toString();
  for (const id of ["excludedSites", "includedSites", "blockedMimeTypes", "allowedMimeTypes", "blockedExtensions", "allowedExtensions"]) {
    document.getElementById(id).value = (rules[id] || []).join(", ");
  }
}
function collectRules() {
  const list = id => document.getElementById(id).value.split(/[\n,;]+/).map(value => value.trim()).filter(Boolean);
  return {
    enabled: document.getElementById("enabled").checked,
    captureIncognito: document.getElementById("incognito").checked,
    minimumSizeBytes: Math.round((Number(document.getElementById("minimumSize").value) || 0) * 1048576),
    excludedSites: list("excludedSites"), includedSites: list("includedSites"),
    blockedMimeTypes: list("blockedMimeTypes"), allowedMimeTypes: list("allowedMimeTypes"),
    blockedExtensions: list("blockedExtensions"), allowedExtensions: list("allowedExtensions")
  };
}
function showResult(text) { document.getElementById("result").textContent = text; }
