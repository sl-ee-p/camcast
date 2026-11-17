// CamCast viewer page: live MJPEG in an <img>, status polling, rotate, fullscreen, snapshot, EN/IT.
(() => {
  "use strict";

  const LANGS = ["en", "it"];
  const POLL_MS = 2000;
  const RETRY_MIN_MS = 2000;
  const RETRY_MAX_MS = 15000;
  const NO_FRAME_RESTART_MS = 8000;
  const IDLE_HIDE_MS = 3000;

  const $ = (id) => document.getElementById(id);
  const ui = {
    app: $("app"), stage: $("stage"), img: $("stream"), overlay: $("overlay"), overlayText: $("overlay-text"),
    state: $("state"), stateLabel: $("state-label"), fps: $("fps"), viewers: $("viewers"),
    rotate: $("rotate"), rotDeg: $("rot-deg"), fullscreen: $("fullscreen"), fsLabel: $("fs-label"),
    snapshot: $("snapshot"), lang: $("lang"), langLabel: $("lang-label"), toast: $("toast"),
  };

  const store = {
    get: (key) => { try { return localStorage.getItem("camcast." + key); } catch { return null; } },
    set: (key, value) => { try { localStorage.setItem("camcast." + key, value); } catch { /* private mode */ } },
  };

  let strings = {};
  let lang = "en";
  let number = new Intl.NumberFormat("en", { minimumFractionDigits: 1, maximumFractionDigits: 1 });
  let status = null; // last /api/status body
  let viewerUp = true; // false while /api/status itself fails
  let streaming = false; // an <img src=/video> request is open
  let hasFrame = false; // the current request has shown at least one frame
  let rejected = false; // the current request failed (503 cap, viewer down, ...)
  let streamStarted = 0;
  let retryDelay = RETRY_MIN_MS;
  let retryTimer = 0;
  let pollTimer = 0;
  let idleTimer = 0;
  let toastTimer = 0;
  let rotation = [0, 90, 180, 270].includes(Number(store.get("rotation"))) ? Number(store.get("rotation")) : 0;

  // --- strings -----------------------------------------------------------------

  function t(key, vars) {
    let text = strings[key] ?? key;
    for (const [name, value] of Object.entries(vars || {})) text = text.replaceAll("{" + name + "}", value);
    return text;
  }

  function pickLang() {
    const query = new URLSearchParams(location.search).get("lang");
    if (LANGS.includes(query)) return query;
    const saved = store.get("lang");
    if (LANGS.includes(saved)) return saved;
    for (const tag of navigator.languages || [navigator.language || ""]) {
      const base = tag.slice(0, 2).toLowerCase();
      if (LANGS.includes(base)) return base;
    }
    return "en";
  }

  async function setLang(next) {
    const response = await fetch("/static/i18n/" + next + ".json", { cache: "no-cache" });
    if (!response.ok) throw new Error("strings " + response.status);
    strings = await response.json();
    lang = next;
    number = new Intl.NumberFormat(lang, { minimumFractionDigits: 1, maximumFractionDigits: 1 });
    document.documentElement.lang = lang;
    document.title = t("title");
    for (const el of document.querySelectorAll("[data-i18n]")) el.textContent = t(el.dataset.i18n);
    for (const el of document.querySelectorAll("[data-i18n-alt]")) el.alt = t(el.dataset.i18nAlt);
    const other = LANGS.find((code) => code !== lang);
    ui.langLabel.textContent = t("switchLang");
    ui.lang.lang = other;
    render();
  }

  // --- stream --------------------------------------------------------------------

  function startStream() {
    clearTimeout(retryTimer);
    streaming = true;
    hasFrame = false;
    rejected = false;
    streamStarted = Date.now();
    ui.img.src = "/video?t=" + streamStarted;
    render();
  }

  function stopStream() {
    clearTimeout(retryTimer);
    streaming = false;
    hasFrame = false;
    ui.img.removeAttribute("src");
  }

  function retryLater() {
    stopStream();
    rejected = true;
    retryTimer = setTimeout(startStream, retryDelay);
    retryDelay = Math.min(RETRY_MAX_MS, retryDelay * 2);
    render();
  }

  ui.img.addEventListener("load", () => {
    hasFrame = true;
    rejected = false;
    retryDelay = RETRY_MIN_MS;
    render();
  });
  ui.img.addEventListener("error", () => { if (streaming) retryLater(); });

  // --- status polling ------------------------------------------------------------

  async function poll() {
    clearTimeout(pollTimer);
    try {
      const response = await fetch("/api/status", { cache: "no-store", signal: AbortSignal.timeout(4000) });
      if (!response.ok) throw new Error("status " + response.status);
      status = await response.json();
      if (!viewerUp) {
        viewerUp = true;
        if (!document.hidden) startStream(); // the viewer came back: the old request is dead
      }
    } catch {
      viewerUp = false;
    }
    // An open request with no picture for a while (e.g. the Pi restarted mid-stream) is reopened.
    if (streaming && !hasFrame && status?.state === "online" && Date.now() - streamStarted > NO_FRAME_RESTART_MS) {
      startStream();
    }
    render();
    if (!document.hidden) pollTimer = setTimeout(poll, POLL_MS);
  }

  // --- rendering -----------------------------------------------------------------

  function overlayMessage() {
    if (!viewerUp) return t("viewerDown");
    if (rejected && status && status.viewers >= status.max_viewers) return t("tooMany", { max: status.max_viewers });
    const state = status?.state;
    if (state === "offline") return t("reason." + (status.reason || "error")) + " " + t("retrying");
    if (rejected) return t("retrying");
    if (state === "online") return hasFrame ? "" : t("noFrames");
    return t("connecting");
  }

  function render() {
    if (!strings.title) return;
    const state = viewerUp ? (status?.state || "unknown") : "noViewer";
    ui.state.className = "pill " + state;
    ui.stateLabel.textContent = t("state." + state);
    const live = viewerUp && state === "online";
    ui.fps.textContent = live ? t("fps", { fps: number.format(status.fps) }) : "";
    ui.fps.hidden = !live;
    ui.viewers.textContent = status && viewerUp ? t("viewers", { n: status.viewers, max: status.max_viewers }) : "";
    const message = overlayMessage();
    ui.overlay.hidden = !message;
    ui.overlayText.textContent = message;
    ui.stage.dataset.rot = String(rotation);
    ui.rotDeg.textContent = t("degrees", { deg: rotation });
    ui.fsLabel.textContent = t(ui.app.classList.contains("fs") ? "exitFullscreen" : "fullscreen");
  }

  new ResizeObserver(([entry]) => {
    const { width, height } = entry.contentRect;
    ui.stage.style.setProperty("--w", width + "px");
    ui.stage.style.setProperty("--h", height + "px");
  }).observe(ui.stage);

  // --- controls ------------------------------------------------------------------

  ui.rotate.addEventListener("click", () => {
    rotation = (rotation + 90) % 360;
    store.set("rotation", String(rotation));
    render();
  });

  const nativeFullscreen = Boolean(document.fullscreenEnabled && ui.app.requestFullscreen);

  function setFullscreenClass(on) {
    ui.app.classList.toggle("fs", on);
    ui.app.classList.remove("idle");
    if (on) wake();
    render();
  }

  ui.fullscreen.addEventListener("click", async () => {
    if (!nativeFullscreen) return setFullscreenClass(!ui.app.classList.contains("fs"));
    try {
      if (document.fullscreenElement) await document.exitFullscreen();
      else await ui.app.requestFullscreen({ navigationUI: "hide" });
    } catch {
      setFullscreenClass(!ui.app.classList.contains("fs"));
    }
  });
  document.addEventListener("fullscreenchange", () => setFullscreenClass(Boolean(document.fullscreenElement)));
  document.addEventListener("keydown", (event) => {
    if (event.key === "Escape" && ui.app.classList.contains("fs") && !document.fullscreenElement) {
      setFullscreenClass(false);
    }
  });

  // In fullscreen the controls fade after a few idle seconds; any tap, move or key brings them back.
  function wake() {
    ui.app.classList.remove("idle");
    clearTimeout(idleTimer);
    if (ui.app.classList.contains("fs")) idleTimer = setTimeout(() => ui.app.classList.add("idle"), IDLE_HIDE_MS);
  }
  for (const type of ["pointerdown", "pointermove", "keydown"]) document.addEventListener(type, wake, { passive: true });

  function toast(text) {
    ui.toast.textContent = text;
    ui.toast.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { ui.toast.hidden = true; }, 3000);
  }

  ui.snapshot.addEventListener("click", async () => {
    ui.snapshot.disabled = true;
    try {
      const response = await fetch("/snapshot.jpg", { cache: "no-store", signal: AbortSignal.timeout(10000) });
      if (!response.ok) throw new Error("snapshot " + response.status);
      const blob = await response.blob();
      const header = response.headers.get("Content-Disposition") || "";
      const name = (header.match(/filename="([^"]+)"/) || [])[1] || "camcast.jpg";
      const url = URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = url;
      link.download = name;
      document.body.append(link);
      link.click();
      link.remove();
      setTimeout(() => URL.revokeObjectURL(url), 10000);
      toast(t("snapshotSaved"));
    } catch {
      toast(t("snapshotFailed"));
    } finally {
      ui.snapshot.disabled = false;
    }
  });

  ui.lang.addEventListener("click", () => {
    const next = LANGS.find((code) => code !== lang);
    store.set("lang", next);
    setLang(next).catch(() => {});
  });

  // A hidden tab gives its viewer slot back (and lets the phone rest once nobody watches).
  document.addEventListener("visibilitychange", () => {
    if (document.hidden) {
      stopStream();
      clearTimeout(pollTimer);
    } else {
      retryDelay = RETRY_MIN_MS;
      startStream();
      poll();
    }
  });

  setLang(pickLang()).catch(() => {}).finally(() => {
    if (!document.hidden) startStream();
    poll();
  });
})();
