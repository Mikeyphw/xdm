(() => {
  const API_NAME = "__xdmBridgeSelfTestV1";
  if (globalThis[API_NAME]) return;

  const HOST_ID = "__xdm_bridge_selftest_host";
  let lastResult = Object.freeze({ ok: false, hostMounted: false, shadowMounted: false, lastError: "not-run" });

  function removeHost() {
    const existing = document.getElementById(HOST_ID);
    if (existing) existing.remove();
  }

  function probe() {
    removeHost();
    try {
      const parent = document.body || document.documentElement;
      if (!parent) throw new Error("No page root is available for launcher injection.");

      const host = document.createElement("div");
      host.id = HOST_ID;
      host.dataset.xdmBridgeSelfTest = "v1";
      const shadow = host.attachShadow({ mode: "open" });
      const style = document.createElement("style");
      style.textContent = ":host{all:initial;position:fixed;right:12px;bottom:12px;z-index:2147483647;pointer-events:none}.probe{width:48px;height:48px;border-radius:16px;background:#183b59;color:#d2e8ff;display:grid;place-items:center;font:800 11px system-ui,sans-serif}";
      const marker = document.createElement("div");
      marker.className = "probe";
      marker.textContent = "XDM";
      shadow.append(style, marker);
      parent.appendChild(host);

      const hostMounted = host.parentNode === parent && host.isConnected !== false;
      const shadowMounted = Boolean(host.shadowRoot && host.shadowRoot.children && host.shadowRoot.children.length >= 2);
      lastResult = Object.freeze({
        ok: hostMounted && shadowMounted,
        hostMounted,
        shadowMounted,
        pageRoot: parent === document.body ? "body" : "documentElement",
        lastError: ""
      });
      removeHost();
      return lastResult;
    } catch (error) {
      removeHost();
      lastResult = Object.freeze({
        ok: false,
        hostMounted: false,
        shadowMounted: false,
        pageRoot: "none",
        lastError: error && error.message ? error.message : String(error)
      });
      return lastResult;
    }
  }

  function health() {
    return lastResult;
  }

  globalThis[API_NAME] = Object.freeze({ probe, health, hostId: HOST_ID });
})();
