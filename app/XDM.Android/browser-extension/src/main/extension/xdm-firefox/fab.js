(() => {
  if (globalThis.XdmLauncherUiV2) {
    globalThis.XdmLauncherUiV1 = globalThis.XdmLauncherUiV2;
    return;
  }

  const HOST_ID = "__xdm_media_fab_host";
  const FAB_TTL_MS = 5 * 60 * 1000;
  const CONFIG = globalThis.XdmExtensionConfig || {};
  const FALLBACK_THEME = Object.freeze({
    background: "#090B0F",
    surface: "#101318",
    raisedSurface: "#161A21",
    strongSurface: "#1C212A",
    text: "#E8EDF5",
    mutedText: "#ABB4C2",
    primary: "#7DB8FF",
    onPrimary: "#003259",
    primaryContainer: "#183B59",
    onPrimaryContainer: "#D2E8FF",
    outline: "#333A46",
    separator: "#252B35",
    success: "#79D49A",
    error: "#FFB4AB",
    fabSizePx: 56,
    fabCornerRadiusPx: 18,
    fabEdgeInsetPx: 16,
    fabActionGapPx: 10,
    motionFastMs: 140,
    motionStandardMs: 220
  });

  let host = null;
  let shadow = null;
  let expiryTimer = null;
  let current = null;
  let expanded = false;
  let listenersInstalled = false;

  function clampNumber(value, fallback, minimum, maximum) {
    const number = Number(value);
    return Number.isFinite(number) ? Math.min(maximum, Math.max(minimum, number)) : fallback;
  }

  function safeColor(value, fallback) {
    return /^#[0-9a-f]{6}$/i.test(String(value || "")) ? String(value) : fallback;
  }

  function theme() {
    const value = CONFIG.theme && typeof CONFIG.theme === "object" ? CONFIG.theme : {};
    const colors = {};
    for (const key of [
      "background", "surface", "raisedSurface", "strongSurface", "text", "mutedText",
      "primary", "onPrimary", "primaryContainer", "onPrimaryContainer", "outline",
      "separator", "success", "error"
    ]) colors[key] = safeColor(value[key], FALLBACK_THEME[key]);
    return Object.freeze(Object.assign(colors, {
      fabSizePx: clampNumber(value.fabSizePx, FALLBACK_THEME.fabSizePx, 48, 80),
      fabCornerRadiusPx: clampNumber(value.fabCornerRadiusPx, FALLBACK_THEME.fabCornerRadiusPx, 0, 40),
      fabEdgeInsetPx: clampNumber(value.fabEdgeInsetPx, FALLBACK_THEME.fabEdgeInsetPx, 8, 40),
      fabActionGapPx: clampNumber(value.fabActionGapPx, FALLBACK_THEME.fabActionGapPx, 4, 24),
      motionFastMs: clampNumber(value.motionFastMs, FALLBACK_THEME.motionFastMs, 0, 1000),
      motionStandardMs: clampNumber(value.motionStandardMs, FALLBACK_THEME.motionStandardMs, 0, 1500)
    }));
  }

  function make(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined) node.textContent = text;
    return node;
  }

  function iconSvg() {
    return '<svg aria-hidden="true" viewBox="0 0 24 24" width="27" height="27" fill="none" xmlns="http://www.w3.org/2000/svg"><path d="M12 3v10.25m0 0 4-4m-4 4-4-4M5 15.5v3A1.5 1.5 0 0 0 6.5 20h11a1.5 1.5 0 0 0 1.5-1.5v-3" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/><path d="m17.1 4.4 2.5 2.5m0-2.5-2.5 2.5" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/></svg>';
  }

  function css(tokens) {
    return `
      :host{all:initial;position:fixed;right:max(${tokens.fabEdgeInsetPx}px,calc(env(safe-area-inset-right) + 12px));bottom:max(${tokens.fabEdgeInsetPx}px,calc(env(safe-area-inset-bottom) + 12px));z-index:2147483647;pointer-events:none;contain:layout style;font-family:system-ui,-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;color-scheme:dark}
      *,*::before,*::after{box-sizing:border-box}
      .xdm-shell{display:flex;flex-direction:column;align-items:flex-end;gap:${tokens.fabActionGapPx}px;pointer-events:none;isolation:isolate}
      .xdm-menu{display:flex;flex-direction:column;align-items:stretch;gap:8px;min-width:168px;padding:8px;border-radius:${Math.min(tokens.fabCornerRadiusPx,16)}px;background:${tokens.surface};color:${tokens.text};box-shadow:0 12px 34px rgba(0,0,0,.42);opacity:0;transform:translateY(8px) scale(.96);transform-origin:bottom right;visibility:hidden;pointer-events:none;transition:opacity ${tokens.motionFastMs}ms ease,transform ${tokens.motionStandardMs}ms cubic-bezier(.2,.8,.2,1),visibility 0s linear ${tokens.motionStandardMs}ms}
      .xdm-shell[data-expanded="true"] .xdm-menu{opacity:1;transform:translateY(0) scale(1);visibility:visible;pointer-events:auto;transition-delay:0s}
      .xdm-choice{display:flex;align-items:center;justify-content:center;min-height:48px;padding:10px 14px;border:0;border-radius:12px;background:${tokens.raisedSurface};color:${tokens.text};font:700 13px/1.2 system-ui,-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;text-decoration:none;text-align:center;-webkit-tap-highlight-color:transparent;touch-action:manipulation}
      .xdm-choice-primary{background:${tokens.primaryContainer};color:${tokens.onPrimaryContainer}}
      .xdm-fab{position:relative;display:grid;place-items:center;width:${tokens.fabSizePx}px;height:${tokens.fabSizePx}px;min-width:48px;min-height:48px;margin:0;padding:0;border:0;border-radius:${tokens.fabCornerRadiusPx}px;background:${tokens.primaryContainer};color:${tokens.onPrimaryContainer};box-shadow:0 8px 24px rgba(0,0,0,.38);text-decoration:none;cursor:pointer;pointer-events:auto;-webkit-tap-highlight-color:transparent;touch-action:manipulation;transition:transform ${tokens.motionFastMs}ms ease,background ${tokens.motionFastMs}ms ease,box-shadow ${tokens.motionFastMs}ms ease}
      .xdm-fab:hover{background:${tokens.strongSurface};box-shadow:0 10px 28px rgba(0,0,0,.46)}
      .xdm-fab:active{transform:scale(.94)}
      .xdm-fab:focus-visible,.xdm-choice:focus-visible{outline:3px solid ${tokens.primary};outline-offset:3px}
      .xdm-badge{position:absolute;top:-6px;right:-6px;display:grid;place-items:center;min-width:21px;height:21px;padding:0 6px;border-radius:999px;background:${tokens.primary};color:${tokens.onPrimary};font:800 11px/1 system-ui,-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;box-shadow:0 2px 8px rgba(0,0,0,.4)}
      .xdm-kind{position:absolute;left:-7px;top:-8px;min-width:28px;padding:4px 6px;border-radius:999px;background:${tokens.strongSurface};color:${tokens.text};font:800 9px/1 system-ui,-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;letter-spacing:.35px;text-transform:uppercase;box-shadow:0 2px 8px rgba(0,0,0,.35)}
      .xdm-sr{position:absolute!important;width:1px!important;height:1px!important;padding:0!important;margin:-1px!important;overflow:hidden!important;clip:rect(0,0,0,0)!important;white-space:nowrap!important;border:0!important}
      @media (prefers-reduced-motion:reduce){.xdm-menu,.xdm-fab{transition:none!important}}
    `;
  }

  function installListeners() {
    if (listenersInstalled) return;
    listenersInstalled = true;
    document.addEventListener("fullscreenchange", mount, true);
    document.addEventListener("keydown", event => {
      if (event.key === "Escape") hide();
    }, true);
  }

  function ensureHost() {
    if (host && host.isConnected !== false) return host;
    const existing = document.getElementById(HOST_ID);
    if (existing) existing.remove();
    host = document.createElement("div");
    host.id = HOST_ID;
    host.dataset.xdmMediaFab = "v2";
    shadow = host.attachShadow({ mode: "open" });
    installListeners();
    mount();
    return host;
  }

  function mount() {
    if (!host) return;
    const parent = document.fullscreenElement || document.body || document.documentElement;
    if (parent && host.parentNode !== parent) parent.appendChild(host);
  }

  function safeTarget(value) {
    return value === "1dm" || value === "ask" ? value : "xdm";
  }

  function safeKind(value) {
    const kind = String(value || "").toLowerCase();
    if (kind === "hls" || kind === "dash") return kind.toUpperCase();
    if (kind === "video" || kind === "audio") return kind.toUpperCase();
    return "";
  }

  function safeCount(value) {
    const count = Math.floor(Number(value || 1));
    return Number.isFinite(count) ? Math.max(1, Math.min(99, count)) : 1;
  }

  function nativeLink(label, href, primary = false) {
    if (!href) return null;
    const link = make("a", primary ? "xdm-choice xdm-choice-primary" : "xdm-choice", label);
    link.href = href;
    link.rel = "external noopener";
    link.dataset.xdmNativeLink = "true";
    link.addEventListener("click", () => setTimeout(hide, 900));
    return link;
  }

  function describe(state) {
    const destination = state.target === "1dm" ? "1DM+" : state.target === "ask" ? "app choices" : "XDM";
    const kind = state.kind ? `${state.kind} ` : "";
    const count = state.candidateCount > 1 ? `, ${state.candidateCount} candidates` : "";
    return `Open detected ${kind}media in ${destination}${count}`;
  }

  function render() {
    if (!current) return false;
    ensureHost();
    mount();
    const tokens = theme();
    shadow.replaceChildren();
    const style = make("style");
    style.textContent = css(tokens);
    const shell = make("div", "xdm-shell");
    shell.dataset.expanded = String(expanded);
    const menu = make("div", "xdm-menu");
    menu.setAttribute("role", "menu");
    menu.setAttribute("aria-hidden", String(!expanded));

    const xdmLink = nativeLink("Open in XDM", current.links.xdm, true);
    const oneDmLink = nativeLink("Open in 1DM+", current.links.oneDm, false);
    if (xdmLink) { xdmLink.setAttribute("role", "menuitem"); menu.appendChild(xdmLink); }
    if (oneDmLink) { oneDmLink.setAttribute("role", "menuitem"); menu.appendChild(oneDmLink); }

    const target = safeTarget(current.target);
    let fab;
    if (target === "ask") {
      fab = make("button", "xdm-fab");
      fab.type = "button";
      fab.setAttribute("aria-haspopup", "menu");
      fab.setAttribute("aria-expanded", String(expanded));
      fab.addEventListener("click", () => {
        expanded = !expanded;
        render();
      });
    } else {
      const href = target === "1dm" ? current.links.oneDm : current.links.xdm;
      if (!href) return false;
      fab = make("a", "xdm-fab");
      fab.href = href;
      fab.rel = "external noopener";
      fab.dataset.xdmNativeLink = "true";
      fab.addEventListener("click", () => setTimeout(hide, 900));
    }
    const label = describe(Object.assign({}, current, { target }));
    fab.setAttribute("aria-label", label);
    fab.title = label;
    const icon = make("span");
    icon.innerHTML = iconSvg();
    fab.appendChild(icon);
    const sr = make("span", "xdm-sr", label);
    fab.appendChild(sr);

    if (current.candidateCount > 1) {
      const badge = make("span", "xdm-badge", current.candidateCount >= 99 ? "99+" : String(current.candidateCount));
      badge.setAttribute("aria-hidden", "true");
      fab.appendChild(badge);
    }
    if (current.kind) {
      const kind = make("span", "xdm-kind", current.kind);
      kind.setAttribute("aria-hidden", "true");
      fab.appendChild(kind);
    }

    if (target === "ask") shell.appendChild(menu);
    shell.appendChild(fab);
    shadow.append(style, shell);
    host.dataset.target = target;
    host.dataset.candidateCount = String(current.candidateCount);
    host.dataset.streamKind = current.kind.toLowerCase();
    return true;
  }

  function scheduleExpiry() {
    if (expiryTimer) clearTimeout(expiryTimer);
    expiryTimer = setTimeout(hide, FAB_TTL_MS);
  }

  function normalize(input = {}) {
    const links = input.links && typeof input.links === "object" ? input.links : {};
    return Object.freeze({
      target: safeTarget(input.target),
      links: Object.freeze({ xdm: String(links.xdm || ""), oneDm: String(links.oneDm || "") }),
      candidateCount: safeCount(input.candidateCount),
      kind: safeKind(input.streamKind || input.kind),
      url: String(input.url || ""),
      label: String(input.label || "Media ready")
    });
  }

  function show(input = {}) {
    current = normalize(input);
    expanded = current.target === "ask" && Boolean(input.expanded);
    const rendered = render();
    if (rendered) scheduleExpiry();
    return rendered;
  }

  function update(input = {}) {
    if (!current) return show(input);
    current = normalize(Object.assign({}, current, input, {
      links: Object.assign({}, current.links, input.links || {})
    }));
    const rendered = render();
    if (rendered) scheduleExpiry();
    return rendered;
  }

  function hide() {
    if (expiryTimer) clearTimeout(expiryTimer);
    expiryTimer = null;
    expanded = false;
    current = null;
    if (host) host.remove();
    host = null;
    shadow = null;
    return true;
  }

  function state() {
    return current ? Object.freeze({
      target: current.target,
      candidateCount: current.candidateCount,
      kind: current.kind,
      expanded,
      hostId: HOST_ID
    }) : null;
  }

  const API = Object.freeze({ show, update, hide, state, hostId: HOST_ID });
  globalThis.XdmLauncherUiV2 = API;
  globalThis.XdmLauncherUiV1 = API;
})();
