(() => {
  if (globalThis.XdmLauncherUiV1) return;
  const HOST_ID = "__xdm_media_launcher_v1";

  function make(tag, text) {
    const node = document.createElement(tag);
    if (text !== undefined) node.textContent = text;
    return node;
  }

  function addNativeLink(container, label, href, primary = false) {
    if (!href) return null;
    const link = make("a", label);
    link.href = href;
    link.rel = "external noopener";
    link.setAttribute("role", "button");
    link.dataset.xdmNativeLink = "true";
    link.className = primary ? "xdm-action xdm-action-primary" : "xdm-action";
    container.appendChild(link);
    return link;
  }

  function show(input = {}) {
    const old = document.getElementById(HOST_ID);
    if (old) old.remove();

    const target = input.target || "xdm";
    const links = input.links || {};
    const box = make("section");
    box.id = HOST_ID;
    box.setAttribute("aria-label", "XDM media launcher");
    box.innerHTML = `
      <style>
        #${HOST_ID}{position:fixed;right:12px;bottom:max(16px,env(safe-area-inset-bottom));z-index:2147483647;width:calc(100vw - 24px);max-width:390px;box-sizing:border-box;padding:14px;border-radius:16px;background:rgba(15,18,23,.985);color:#edf2f8;font:13px system-ui,sans-serif;box-shadow:0 14px 42px rgba(0,0,0,.5);border:1px solid rgba(255,255,255,.12)}
        #${HOST_ID} .xdm-head{display:flex;align-items:center;gap:8px} #${HOST_ID} strong{flex:1;font-size:14px}
        #${HOST_ID} .xdm-close{border:0;background:transparent;color:#aeb7c4;font-size:22px;padding:2px 5px}
        #${HOST_ID} .xdm-detail{margin:8px 0 11px;color:#aeb7c4;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
        #${HOST_ID} .xdm-actions{display:flex;gap:8px;flex-wrap:wrap}
        #${HOST_ID} .xdm-action{flex:1 1 128px;padding:11px 12px;border-radius:11px;text-align:center;text-decoration:none;font-weight:700;background:rgba(255,255,255,.1);color:#fff;-webkit-tap-highlight-color:transparent}
        #${HOST_ID} .xdm-action-primary{background:#214b68;color:#d7edff}
        #${HOST_ID} .xdm-note{margin-top:10px;color:#8793a2;font-size:11px;line-height:1.4}
      </style>`;
    const header = make("div"); header.className = "xdm-head";
    const heading = make("strong", input.label || "Media ready");
    const close = make("button", "×"); close.className = "xdm-close"; close.setAttribute("aria-label", "Close"); close.addEventListener("click", () => box.remove());
    header.append(heading, close);
    const detail = make("div", input.url || "App-link test"); detail.className = "xdm-detail";
    const actions = make("div"); actions.className = "xdm-actions";

    if (target === "1dm") {
      addNativeLink(actions, "Open in 1DM+", links.oneDm, true);
      addNativeLink(actions, "Open in XDM", links.xdm, false);
    } else if (target === "ask") {
      addNativeLink(actions, "Open in XDM", links.xdm, false);
      addNativeLink(actions, "Open in 1DM+", links.oneDm, false);
    } else {
      addNativeLink(actions, "Open in XDM", links.xdm, true);
      addNativeLink(actions, "Open in 1DM+", links.oneDm, false);
    }

    const note = make("div", "Tap a real webpage link so IronFox can hand the custom scheme to Android."); note.className = "xdm-note";
    box.append(header, detail, actions, note);
    (document.body || document.documentElement).appendChild(box);
    return true;
  }

  globalThis.XdmLauncherUiV1 = Object.freeze({ show, hostId: HOST_ID });
})();
