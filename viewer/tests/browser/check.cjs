// Browser checks for the viewer page. Screenshots go to out/shots/.
//   NODE_PATH=... node tests/browser/check.cjs [--base URL] [--local]
// --local also stops/starts the fake phone and the viewer through servers.sh (dev loopback setup only).
// Without --local (e.g. against the Pi), the destructive steps are skipped.
const { execFileSync } = require("child_process");
const fs = require("fs");
const path = require("path");
const { launch } = require("./pw.cjs");

const arg = (name, fallback) => {
  const at = process.argv.indexOf(name);
  return at > 0 ? process.argv[at + 1] : fallback;
};
const BASE = arg("--base", "http://127.0.0.1:18091");
const LOCAL = process.argv.includes("--local");
const ROOT = path.resolve(__dirname, "../..");
const SHOTS = path.join(ROOT, "out/shots");
fs.mkdirSync(SHOTS, { recursive: true });

const results = [];
const consoleErrors = [];
let outageSteps = false; // network errors are expected only while the fake phone or the viewer is down
function check(name, ok, detail = "") {
  results.push({ name, ok: Boolean(ok), detail });
  console.log(`${ok ? "PASS" : "FAIL"}  ${name}${detail ? "  (" + detail + ")" : ""}`);
}
const servers = (...args) => execFileSync(path.join(__dirname, "servers.sh"), args, { stdio: "inherit" });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function newPage(browser, { locale = "en-US", phone = true, scale = 2, font2x = false, allow503 = false } = {}) {
  const context = await browser.newContext({
    locale,
    acceptDownloads: true,
    viewport: phone ? { width: 412, height: 915 } : { width: 1280, height: 800 },
    deviceScaleFactor: scale,
    isMobile: phone,
    hasTouch: phone,
  });
  const page = await context.newPage();
  page.on("console", (m) => {
    if (m.type() !== "error") return;
    if (allow503 && /503/.test(m.text())) return;
    if (outageSteps && /^Failed to load resource/.test(m.text())) return;
    consoleErrors.push(`${locale}: ${m.text()}`);
  });
  page.on("pageerror", (e) => consoleErrors.push(`${locale} pageerror: ${e.message}`));
  if (font2x) await page.addInitScript(() => {
    document.addEventListener("DOMContentLoaded", () => { document.documentElement.style.fontSize = "200%"; });
  });
  await page.goto(BASE + "/");
  return page;
}

const label = (page) => page.textContent("#state-label");
const waitLabel = (page, text, timeout = 15000) =>
  page.waitForFunction((t) => document.getElementById("state-label").textContent === t, text, { timeout });
const overlay = (page) => page.evaluate(() => {
  const o = document.getElementById("overlay");
  return o.hidden ? "" : o.textContent.trim();
});
const layout = (page) => page.evaluate(() => {
  const vw = document.documentElement.clientWidth;
  const outside = [...document.querySelectorAll("button, .pill, .meta, h1")]
    .filter((el) => !el.hidden && el.offsetParent !== null)
    .filter((el) => { const r = el.getBoundingClientRect(); return r.left < -1 || r.right > vw + 1; })
    .map((el) => el.id || el.className);
  const stage = document.getElementById("stage").getBoundingClientRect();
  const img = document.getElementById("stream").getBoundingClientRect();
  const rotated = ["90", "270"].includes(document.getElementById("stage").dataset.rot);
  const ratio = rotated ? img.height / img.width : img.width / img.height;
  const inside = img.left >= stage.left - 1 && img.right <= stage.right + 1 && img.top >= stage.top - 1
    && img.bottom <= stage.bottom + 1;
  return { overflowX: document.scrollingElement.scrollWidth > vw + 1, outside, stageHeight: Math.round(stage.height),
    stageShare: Math.round((100 * stage.height) / innerHeight), aspectOk: Math.abs(ratio - 720 / 1280) < 0.02 && inside,
    imgSize: [Math.round(img.width), Math.round(img.height)] };
});
// Two screenshots of the image a moment apart differ only if frames keep arriving.
async function isMoving(page) {
  const a = await page.locator("#stream").screenshot();
  await sleep(700);
  const b = await page.locator("#stream").screenshot();
  return !a.equals(b);
}
const status = (page) => page.evaluate(() => fetch("/api/status").then((r) => r.json()));

(async () => {
  const browser = await launch();
  try {
    // --- EN phone: live view ---------------------------------------------------------------------
    const en = await newPage(browser);
    await waitLabel(en, "Live");
    await en.waitForFunction(() => document.getElementById("overlay").hidden, null, { timeout: 10000 });
    await sleep(2500); // let fps settle over a few polls
    const fps = await en.textContent("#fps");
    check("EN live label, fps, viewers", /^\d+\.\d fps$/.test(fps) && (await en.textContent("#viewers")) === "1/4 viewers",
      `${await label(en)} | ${fps} | ${await en.textContent("#viewers")}`);
    check("EN image is 720x1280 and moving", (await en.evaluate(() => document.getElementById("stream").naturalWidth)) === 720
      && await isMoving(en));
    const enLayout = await layout(en);
    check("EN phone layout fits", !enLayout.overflowX && !enLayout.outside.length && enLayout.aspectOk,
      JSON.stringify(enLayout));
    await en.screenshot({ path: `${SHOTS}/en-phone.png` });

    // --- rotate ----------------------------------------------------------------------------------
    await en.click("#rotate");
    await sleep(400);
    const fits = await en.evaluate(() => {
      const s = document.getElementById("stage").getBoundingClientRect();
      const i = document.getElementById("stream").getBoundingClientRect();
      return { rot: document.getElementById("stage").dataset.rot, inside: i.left >= s.left - 1 && i.right <= s.right + 1
        && i.top >= s.top - 1 && i.bottom <= s.bottom + 1, landscape: i.width > i.height };
    });
    check("rotate 90: image turned and inside the stage", fits.rot === "90" && fits.inside && fits.landscape, JSON.stringify(fits));
    await en.screenshot({ path: `${SHOTS}/en-phone-rot90.png` });
    await en.reload();
    await waitLabel(en, "Live");
    check("rotation survives reload", (await en.getAttribute("#stage", "data-rot")) === "90");
    for (let i = 0; i < 3; i++) await en.click("#rotate");
    check("rotate wraps back to 0", (await en.getAttribute("#stage", "data-rot")) === "0");

    // --- snapshot --------------------------------------------------------------------------------
    const [download] = await Promise.all([en.waitForEvent("download"), en.click("#snapshot")]);
    const file = `${SHOTS}/${download.suggestedFilename()}`;
    await download.saveAs(file);
    const head = fs.readFileSync(file).subarray(0, 2).toString("hex");
    check("snapshot downloads a JPEG with a dated name", /^camcast-\d{8}-\d{6}\.jpg$/.test(download.suggestedFilename())
      && head === "ffd8", download.suggestedFilename());
    await en.waitForSelector("#toast:not([hidden])");
    check("snapshot toast", (await en.textContent("#toast")) === "Snapshot saved");

    // --- fullscreen ------------------------------------------------------------------------------
    await en.click("#fullscreen");
    await sleep(300);
    const fs1 = await en.evaluate(() => ({ cls: document.getElementById("app").classList.contains("fs"),
      native: Boolean(document.fullscreenElement), bar: getComputedStyle(document.querySelector(".bar")).display,
      label: document.getElementById("fs-label").textContent }));
    check("fullscreen on: header hidden, exit label", fs1.cls && fs1.bar === "none" && fs1.label === "Exit fullscreen",
      JSON.stringify(fs1));
    await en.screenshot({ path: `${SHOTS}/en-phone-fullscreen.png` });
    await sleep(3500);
    check("fullscreen controls fade when idle", await en.evaluate(() => document.getElementById("app").classList.contains("idle")));
    await en.screenshot({ path: `${SHOTS}/en-phone-fullscreen-idle.png` });
    await en.mouse.move(100, 100);
    await en.click("#fullscreen");
    await sleep(300);
    check("fullscreen off", !(await en.evaluate(() => document.getElementById("app").classList.contains("fs"))));

    // --- language toggle -------------------------------------------------------------------------
    await en.click("#lang");
    await waitLabel(en, "In diretta");
    await en.reload();
    await waitLabel(en, "In diretta");
    check("language toggle to IT persists", (await en.getAttribute("html", "lang")) === "it");
    await en.click("#lang");
    await waitLabel(en, "Live");
    await en.context().close();

    // --- IT phone, 2x font EN and IT, desktop ----------------------------------------------------
    const it = await newPage(browser, { locale: "it-IT" });
    await waitLabel(it, "In diretta");
    await sleep(2500);
    const itFps = await it.textContent("#fps");
    check("IT labels and decimal comma", /^\d+,\d fps$/.test(itFps) && (await it.textContent("#viewers")) === "1/4 spettatori",
      `${itFps} | ${await it.textContent("#viewers")}`);
    await it.screenshot({ path: `${SHOTS}/it-phone.png` });
    await it.context().close();

    for (const [locale, name, live] of [["en-US", "en", "Live"], ["it-IT", "it", "In diretta"]]) {
      const big = await newPage(browser, { locale, font2x: true });
      await waitLabel(big, live);
      await sleep(500);
      const l = await layout(big);
      check(`${name.toUpperCase()} 2x font fits, video keeps most of the screen`,
        !l.overflowX && !l.outside.length && l.aspectOk && l.stageShare >= 60, JSON.stringify(l));
      const names = name === "en" ? ["Rotate", "Fullscreen", "Snapshot", "Italiano"] : ["Ruota", "Schermo intero", "Istantanea", "English"];
      const named = await Promise.all(names.map((n) => big.getByRole("button", { name: n, exact: false }).count()));
      check(`${name.toUpperCase()} 2x icon buttons keep accessible names`, named.every((n) => n === 1), named.join(","));
      await big.screenshot({ path: `${SHOTS}/${name}-phone-font2x.png` });
      await big.click("#fullscreen");
      await sleep(300);
      await big.screenshot({ path: `${SHOTS}/${name}-phone-font2x-fullscreen.png` });
      await big.context().close();
    }

    const desk = await newPage(browser, { phone: false, scale: 1 });
    await waitLabel(desk, "Live");
    await sleep(500);
    const dl = await layout(desk);
    check("desktop layout fits", !dl.overflowX && !dl.outside.length && dl.aspectOk, JSON.stringify(dl));
    await desk.screenshot({ path: `${SHOTS}/en-desktop.png` });

    // --- manifest --------------------------------------------------------------------------------
    const manifest = await desk.evaluate(() => fetch("/manifest.webmanifest").then((r) => r.json()));
    const icons = await desk.evaluate((list) => Promise.all(list.map((i) => fetch(i.src).then((r) => r.status))), manifest.icons);
    check("manifest standalone with reachable icons", manifest.display === "standalone" && icons.every((s) => s === 200),
      icons.join(","));

    // --- hidden tab gives its slot back ----------------------------------------------------------
    const hid = await newPage(browser);
    await waitLabel(hid, "Live");
    await desk.waitForFunction(() => document.getElementById("viewers").textContent === "2/4 viewers", null, { timeout: 6000 });
    await hid.evaluate(() => {
      Object.defineProperty(document, "hidden", { configurable: true, get: () => true });
      Object.defineProperty(document, "visibilityState", { configurable: true, get: () => "hidden" });
      document.dispatchEvent(new Event("visibilitychange"));
    });
    await desk.waitForFunction(() => document.getElementById("viewers").textContent === "1/4 viewers", null, { timeout: 6000 })
      .then(() => check("hidden tab releases its viewer slot", true), () => check("hidden tab releases its viewer slot", false));
    await hid.evaluate(() => {
      Object.defineProperty(document, "hidden", { configurable: true, get: () => false });
      Object.defineProperty(document, "visibilityState", { configurable: true, get: () => "visible" });
      document.dispatchEvent(new Event("visibilitychange"));
    });
    await desk.waitForFunction(() => document.getElementById("viewers").textContent === "2/4 viewers", null, { timeout: 6000 })
      .then(() => check("visible again takes a slot", true), () => check("visible again takes a slot", false));

    // --- cap: desk + hid + 2 more = 4, the 5th is refused ------------------------------------------
    const more = [await newPage(browser), await newPage(browser)];
    for (const p of more) await waitLabel(p, "Live");
    const fifth = await newPage(browser, { allow503: true });
    await fifth.waitForFunction(() => /Too many viewers/.test(document.getElementById("overlay-text").textContent),
      null, { timeout: 10000 }).catch(() => {});
    const capText = await overlay(fifth);
    check("5th viewer sees the cap message", /Too many viewers: 4/.test(capText), capText);
    await fifth.screenshot({ path: `${SHOTS}/en-phone-cap.png` });
    check("server counts 4 viewers", (await status(desk)).viewers === 4);
    for (const p of more) await p.context().close();
    await fifth.waitForFunction(() => document.getElementById("overlay").hidden, null, { timeout: 20000 })
      .then(() => check("5th viewer gets in when a slot frees up", true), () => check("5th viewer gets in when a slot frees up", false));
    await fifth.context().close();
    await hid.context().close();

    if (LOCAL) {
      // --- phone goes away and comes back -------------------------------------------------------
      outageSteps = true;
      servers("stop-fake");
      await waitLabel(desk, "Offline", 20000);
      // The first message may be "closed the stream"; after the next retry the steady state is "not reachable".
      await desk.waitForFunction(() => /not reachable/.test(document.getElementById("overlay-text").textContent),
        null, { timeout: 15000 }).catch(() => {});
      const off = await overlay(desk);
      check("phone off: Offline + reason", /not reachable/.test(off), off);
      await desk.screenshot({ path: `${SHOTS}/en-desktop-offline.png` });
      const [, snapToast] = await Promise.all([desk.click("#snapshot"),
        desk.waitForFunction(() => !document.getElementById("toast").hidden, null, { timeout: 15000 })
          .then(() => desk.textContent("#toast"))]);
      check("snapshot while offline explains itself", snapToast === "No snapshot: the camera is unavailable", snapToast);
      servers("restart-fake");
      await waitLabel(desk, "Live", 40000);
      await desk.waitForFunction(() => document.getElementById("overlay").hidden, null, { timeout: 10000 });
      check("phone back: live and moving again", await isMoving(desk));

      // --- the viewer on the Pi restarts ----------------------------------------------------------
      servers("stop-viewer");
      await waitLabel(desk, "No connection", 10000);
      check("viewer down: pill says no connection", /not answering/.test(await overlay(desk)));
      await desk.screenshot({ path: `${SHOTS}/en-desktop-viewer-down.png` });
      servers("start-viewer");
      await waitLabel(desk, "Live", 30000);
      await desk.waitForFunction(() => document.getElementById("overlay").hidden, null, { timeout: 15000 });
      check("viewer back: stream reopens by itself", await isMoving(desk));
      outageSteps = false;
    }
    await desk.context().close();
  } catch (error) {
    check("script ran to the end", false, error.message.split("\n")[0]);
  } finally {
    await browser.close();
  }
  check("no console errors", consoleErrors.length === 0, consoleErrors.slice(0, 5).join(" | "));
  const failed = results.filter((r) => !r.ok).length;
  console.log(`\n${results.length - failed}/${results.length} passed; screenshots in ${SHOTS}`);
  process.exit(failed ? 1 : 0);
})();
