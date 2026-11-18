// CamCast viewer page: live MJPEG in an <img>, status polling, rotate, fullscreen, snapshot, EN/IT,
// movement alerts and the motion clips (list, replay, download).
(() => {
  "use strict";

  const LANGS = ["en", "it"];
  const POLL_MS = 2000;
  const RETRY_MIN_MS = 2000;
  const RETRY_MAX_MS = 15000;
  const NO_FRAME_RESTART_MS = 8000;
  const IDLE_HIDE_MS = 3000;
  const EVENTS_POLL_MS = 3000;
  const ALERT_MS = 30000;

  const $ = (id) => document.getElementById(id);
  const ui = {
    app: $("app"), stage: $("stage"), img: $("stream"), overlay: $("overlay"), overlayText: $("overlay-text"),
    state: $("state"), stateLabel: $("state-label"), fps: $("fps"), viewers: $("viewers"),
    rotate: $("rotate"), rotDeg: $("rot-deg"), fullscreen: $("fullscreen"), fsLabel: $("fs-label"),
    snapshot: $("snapshot"), lang: $("lang"), langLabel: $("lang-label"), toast: $("toast"),
    motion: $("motion"), events: $("events"), eventsBadge: $("events-badge"),
    alert: $("alert"), alertText: $("alert-text"), alertShow: $("alert-show"), alertClose: $("alert-close"),
    panel: $("events-panel"), panelClose: $("events-close"), note: $("events-note"), list: $("clip-list"),
    player: $("player"), playerTitle: $("player-title"), playerClose: $("player-close"), playerStage: $("player-stage"),
    clipImg: $("clip-img"), playerOverlay: $("player-overlay"), playerOverlayText: $("player-overlay-text"),
    replay: $("replay"), download: $("download"),
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
  let events = null; // last /api/events body
  let eventsTimer = 0;
  let alertTimer = 0;
  let alertedMotion = null; // newest movement already announced in this page (null until the first poll)
  let seenMotion = Number(store.get("seenMotion")) || 0; // newest movement the user has looked at (badge)
  let playing = null; // the clip open in the player
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
    for (const el of document.querySelectorAll("[data-i18n-label]")) el.setAttribute("aria-label", t(el.dataset.i18nLabel));
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
    renderEvents();
  }

  new ResizeObserver(([entry]) => {
    const { width, height } = entry.contentRect;
    ui.stage.style.setProperty("--w", width + "px");
    ui.stage.style.setProperty("--h", height + "px");
  }).observe(ui.stage);

  // --- movement events and clips ---------------------------------------------------

  function formatTime(millis) {
    const date = new Date(millis);
    const today = new Date().toDateString() === date.toDateString();
    return new Intl.DateTimeFormat(lang, today ? { timeStyle: "medium" } : { dateStyle: "medium", timeStyle: "medium" })
      .format(date);
  }

  function formatDuration(millis) {
    const seconds = Math.max(0, Math.round(millis / 1000));
    return Math.floor(seconds / 60) + ":" + String(seconds % 60).padStart(2, "0");
  }

  /** Every movement the phone knows about, newest first: the clip being recorded, then the saved clips. */
  function movements() {
    if (!events?.available) return [];
    const times = events.clips.map((clip) => clip.motion);
    if (events.recordingSince) times.push(events.recordingSince);
    return [...new Set(times)].sort((a, b) => b - a);
  }

  async function pollEvents() {
    clearTimeout(eventsTimer);
    try {
      const response = await fetch("/api/events", { cache: "no-store", signal: AbortSignal.timeout(6000) });
      if (!response.ok) throw new Error("events " + response.status);
      events = await response.json();
    } catch {
      events = { available: false, reason: "unreachable" };
    }
    const newest = movements()[0] ?? 0;
    // The first answer only sets the baseline: what happened before the page opened shows as the badge, not a banner.
    if (alertedMotion === null) alertedMotion = newest;
    else if (newest > alertedMotion) {
      alertedMotion = newest;
      showAlert(newest);
    }
    renderEvents();
    if (!document.hidden) eventsTimer = setTimeout(pollEvents, EVENTS_POLL_MS);
  }

  function showAlert(millis) {
    ui.alert.dataset.time = String(millis);
    ui.alert.hidden = false;
    renderEvents();
    clearTimeout(alertTimer);
    alertTimer = setTimeout(() => { ui.alert.hidden = true; }, ALERT_MS);
  }

  function markSeen() {
    seenMotion = Math.max(seenMotion, movements()[0] ?? 0);
    store.set("seenMotion", String(seenMotion));
  }

  function renderEvents() {
    if (!strings.title) return;
    ui.motion.hidden = !(events?.available && (events.motion || events.recording));
    const unseen = movements().filter((time) => time > seenMotion).length;
    ui.eventsBadge.hidden = unseen === 0 || !ui.panel.hidden;
    ui.eventsBadge.textContent = unseen > 99 ? "99+" : String(unseen);
    ui.events.setAttribute("aria-label", t("events") + (unseen ? " (" + unseen + ")" : ""));
    if (!ui.alert.hidden) ui.alertText.textContent = t("motionAt", { time: formatTime(Number(ui.alert.dataset.time)) });
    if (!ui.panel.hidden) renderList();
    if (playing) ui.playerTitle.textContent = t("clipTitle", { time: formatTime(playing.motion) });
  }

  function renderList() {
    let note = "";
    if (!events) note = t("connecting");
    else if (!events.available) note = t("eventsUnavailable");
    else if (!events.detection) note = t("detectionOff");
    else if (!events.clips.length && !events.recording) note = t("noClips");
    ui.note.textContent = note;
    ui.note.hidden = !note;
    const items = [];
    if (events?.available && events.recording && events.recordingSince) {
      const li = document.createElement("li");
      li.className = "recording";
      li.textContent = t("recordingNow", { time: formatTime(events.recordingSince) });
      items.push(li);
    }
    const size = new Intl.NumberFormat(lang, { maximumFractionDigits: 1 });
    for (const clip of events?.available ? events.clips : []) {
      const li = document.createElement("li");
      const button = document.createElement("button");
      button.type = "button";
      button.className = "clip" + (clip.motion > seenMotion ? " new" : "");
      const img = document.createElement("img");
      img.loading = "lazy";
      img.src = "/clips/" + clip.id + ".jpg";
      img.alt = t("thumbAlt", { time: formatTime(clip.motion) });
      const when = document.createElement("span");
      when.className = "when";
      when.textContent = formatTime(clip.motion);
      const info = document.createElement("span");
      info.className = "info";
      info.textContent = t("clipMeta", {
        duration: formatDuration(clip.end - clip.start), size: size.format(clip.bytes / 1e6),
      });
      button.append(img, when, info);
      button.addEventListener("click", () => openPlayer(clip));
      li.append(button);
      items.push(li);
    }
    // Rebuild only when the content changed, so thumbnails do not reload every poll.
    const key = items.map((li) => li.textContent + li.className + (li.firstChild?.className || "")).join("|");
    if (ui.list.dataset.key !== key) {
      ui.list.dataset.key = key;
      ui.list.replaceChildren(...items);
    }
  }

  function openPanel() {
    ui.alert.hidden = true;
    ui.panel.hidden = false;
    renderEvents();
    markSeen();
    ui.panelClose.focus();
  }

  function closePanel() {
    ui.panel.hidden = true;
    markSeen();
    renderEvents();
    ui.events.focus();
  }

  function openPlayer(clip) {
    playing = clip;
    ui.player.hidden = false;
    ui.playerStage.dataset.rot = String(rotation); // same turn as the live view
    ui.download.href = "/clips/" + clip.id + ".mjpeg";
    ui.download.setAttribute("download", "camcast-" + clip.id + ".mjpeg");
    startClip();
    renderEvents();
    ui.playerClose.focus();
  }

  function startClip() {
    ui.playerOverlay.hidden = true;
    ui.clipImg.src = "/clips/" + playing.id + "/play?t=" + Date.now();
  }

  function closePlayer() {
    playing = null;
    ui.clipImg.removeAttribute("src"); // ends the replay and frees its slot on the phone
    ui.player.hidden = true;
    if (!ui.panel.hidden) ui.panelClose.focus();
  }

  ui.clipImg.addEventListener("error", async () => {
    if (!playing || !ui.clipImg.getAttribute("src")) return;
    let busy = false;
    try {
      busy = (await fetch("/clips/" + playing.id + "/play", { method: "HEAD", cache: "no-store" })).status === 503;
    } catch { /* viewer down: same message as the camera */ }
    ui.playerOverlayText.textContent = t(busy ? "clipBusy" : "clipFailed");
    ui.playerOverlay.hidden = false;
  });

  ui.events.addEventListener("click", openPanel);
  ui.alertShow.addEventListener("click", openPanel);
  ui.alertClose.addEventListener("click", () => { ui.alert.hidden = true; });
  ui.panelClose.addEventListener("click", closePanel);
  ui.playerClose.addEventListener("click", closePlayer);
  ui.replay.addEventListener("click", () => { if (playing) startClip(); });
  document.addEventListener("keydown", (event) => {
    if (event.key !== "Escape") return;
    if (playing) closePlayer();
    else if (!ui.panel.hidden) closePanel();
  });

  new ResizeObserver(([entry]) => {
    const { width, height } = entry.contentRect;
    ui.playerStage.style.setProperty("--w", width + "px");
    ui.playerStage.style.setProperty("--h", height + "px");
  }).observe(ui.playerStage);

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
      clearTimeout(eventsTimer);
    } else {
      retryDelay = RETRY_MIN_MS;
      startStream();
      poll();
      pollEvents();
    }
  });

  setLang(pickLang()).catch(() => {}).finally(() => {
    if (!document.hidden) startStream();
    poll();
    pollEvents();
  });
})();
