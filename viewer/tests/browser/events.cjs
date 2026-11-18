// Browser checks for movement alerts and clips. Needs a viewer whose phone has at least one clip.
//   node tests/browser/events.cjs --base http://127.0.0.1:18091 --shots out/shots-events
const { launch } = require("./pw.cjs");
const fs = require("fs");

const arg = (name, fallback) => {
  const i = process.argv.indexOf("--" + name);
  return i > 0 ? process.argv[i + 1] : fallback;
};
const BASE = arg("base", "http://127.0.0.1:8091");
const SHOTS = arg("shots", "out/shots-events");
fs.mkdirSync(SHOTS, { recursive: true });

let passed = 0;
let failed = 0;
function check(name, ok, detail = "") {
  console.log(`${ok ? "PASS" : "FAIL"}  ${name}${detail ? "  (" + detail + ")" : ""}`);
  ok ? passed++ : failed++;
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function newPage(browser, { lang = "en", scale = 1, phone = true } = {}) {
  const context = await browser.newContext({
    viewport: phone ? { width: 412, height: 915 } : { width: 1280, height: 800 },
    deviceScaleFactor: 1, locale: lang === "it" ? "it-IT" : "en-US",
  });
  const page = await context.newPage();
  const errors = [];
  page.on("pageerror", (e) => errors.push(e.message));
  page.on("console", (m) => { if (m.type() === "error") errors.push(m.text()); });
  page.errors = errors;
  // A style attribute set from script is allowed by the page's CSP (style-src 'self'); a <style> tag is not.
  if (scale !== 1) await page.addInitScript((pct) => {
    document.addEventListener("DOMContentLoaded", () => { document.documentElement.style.fontSize = pct; });
  }, `${scale * 100}%`);
  await page.goto(BASE + "/?lang=" + lang);
  return page;
}

const fits = (page, selector) => page.evaluate((sel) => {
  const vw = document.documentElement.clientWidth;
  return [...document.querySelectorAll(sel)].filter((el) => el.offsetParent !== null)
    .every((el) => { const r = el.getBoundingClientRect(); return r.left >= -1 && r.right <= vw + 1; });
}, selector);

(async () => {
  const browser = await launch();
  try {
    const page = await newPage(browser);
    await page.waitForFunction(() => document.getElementById("state-label").textContent === "Live", null, { timeout: 20000 });
    const real = await (await page.request.get(BASE + "/api/events")).json();
    check("events API available with clips", real.available && real.clips.length > 0, `${real.clips.length} clips`);
    await page.waitForFunction(() => !document.getElementById("events-badge").hidden, null, { timeout: 10000 });
    check("unseen clips show as a badge, no banner on first load",
      (await page.isHidden("#alert")) && Number(await page.textContent("#events-badge")) >= real.clips.length,
      await page.textContent("#events-badge"));
    check("motion pill follows the phone", (await page.isVisible("#motion")) === Boolean(real.motion || real.recording));

    // A new movement after the page opened: fake the next answer with a newer event.
    const newer = Date.now();
    await page.route("**/api/events", async (route) => {
      const body = await (await route.fetch()).json();
      body.recording = true;
      body.recordingSince = newer;
      route.fulfill({ json: body });
    });
    await page.waitForSelector("#alert:not([hidden])", { timeout: 10000 });
    const alertText = await page.textContent("#alert-text");
    check("new movement raises the banner with its time", alertText.startsWith("Movement detected at "), alertText);
    await page.screenshot({ path: `${SHOTS}/en-alert.png` });
    await page.click("#alert-show");
    await page.waitForSelector("#events-panel:not([hidden])");
    await page.waitForFunction(() => [...document.querySelectorAll(".clip img")].some((i) => i.naturalWidth > 0),
      null, { timeout: 15000 });
    const items = await page.$$eval(".clip", (els) => els.length);
    check("panel lists the clips with thumbnails", items === real.clips.length, `${items}`);
    check("recording item on top", (await page.textContent("#clip-list li:first-child")).startsWith("Recording"));
    check("opening the panel clears the badge and banner", (await page.isHidden("#events-badge")) && (await page.isHidden("#alert")));
    check("panel fits the phone", await fits(page, ".clip, #events-close"));
    await page.screenshot({ path: `${SHOTS}/en-panel.png` });

    await page.click(".clip >> nth=0");
    await page.waitForSelector("#player:not([hidden])");
    await page.waitForFunction(() => document.getElementById("clip-img").naturalWidth > 0, null, { timeout: 15000 });
    const a = await page.locator("#clip-img").screenshot();
    await sleep(800);
    const b = await page.locator("#clip-img").screenshot();
    check("clip plays in the player", !a.equals(b));
    check("player title and download link",
      (await page.textContent("#player-title")).startsWith("Movement at ")
      && (await page.getAttribute("#download", "href")) === `/clips/${real.clips[0].id}.mjpeg`);
    await page.screenshot({ path: `${SHOTS}/en-player.png` });
    const head = await page.request.head(BASE + `/clips/${real.clips[0].id}.mjpeg`);
    check("download answers as an attachment", head.status() === 200
      && head.headers()["content-disposition"].includes(`camcast-${real.clips[0].id}.mjpeg`));
    await page.keyboard.press("Escape");
    check("Escape closes the player, src released", (await page.isHidden("#player"))
      && !(await page.getAttribute("#clip-img", "src")));
    await page.keyboard.press("Escape");
    check("Escape closes the panel", await page.isHidden("#events-panel"));

    // Seen state survives a reload: no badge for clips already looked at.
    await page.unroute("**/api/events");
    await page.reload();
    await page.waitForFunction(() => document.getElementById("state-label").textContent === "Live", null, { timeout: 20000 });
    await sleep(4000);
    check("seen clips stay seen after reload", await page.isHidden("#events-badge"));
    check("no console errors (EN)", page.errors.length === 0, page.errors.join(" | "));

    for (const [lang, scale] of [["it", 1], ["en", 2], ["it", 2]]) {
      const p = await newPage(browser, { lang, scale });
      await p.waitForFunction(() => document.getElementById("events-badge").textContent !== "" ||
        document.getElementById("motion").hidden === false, null, { timeout: 20000 }).catch(() => {});
      await p.route("**/api/events", async (route) => {
        const body = await (await route.fetch()).json();
        body.recording = true;
        body.recordingSince = Date.now();
        route.fulfill({ json: body });
      });
      await p.waitForSelector("#alert:not([hidden])", { timeout: 15000 });
      const tag = `${lang}-${scale}x`;
      check(`${tag} banner fits`, await fits(p, "#alert, #alert button"));
      check(`${tag} controls fit with the Events button`, await fits(p, ".controls button"));
      await p.screenshot({ path: `${SHOTS}/${tag}-alert.png` });
      await p.click("#events");
      await p.waitForSelector("#events-panel:not([hidden])");
      await sleep(1500);
      check(`${tag} panel fits`, await fits(p, ".clip, .sheet-bar button, #clip-list li"));
      await p.screenshot({ path: `${SHOTS}/${tag}-panel.png` });
      if (lang === "it" && scale === 1) {
        check("IT texts", (await p.textContent("#events-title")) === "Clip di movimento"
          && (await p.textContent("#clip-list li:first-child")).startsWith("Registrazione in corso"));
      }
      check(`${tag} no console errors`, p.errors.length === 0, p.errors.join(" | "));
      await p.context().close();
    }

    const desk = await newPage(browser, { phone: false });
    await desk.waitForTimeout(3000);
    await desk.click("#events");
    await desk.waitForTimeout(2000);
    await desk.screenshot({ path: `${SHOTS}/en-desktop-panel.png` });
    check("desktop panel fits", await fits(desk, ".clip"));
  } finally {
    await browser.close();
  }
  console.log(`\n${passed}/${passed + failed} passed; screenshots in ${SHOTS}`);
  process.exit(failed ? 1 : 0);
})();
