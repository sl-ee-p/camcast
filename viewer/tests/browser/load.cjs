// Holds 0 -> 1 -> 3 browser viewers on the page for a load measurement, checking each image keeps moving.
//   node tests/browser/load.cjs [--base URL] [--idle 30] [--one 40] [--three 120]
const { launch } = require("./pw.cjs");
const arg = (n, d) => { const i = process.argv.indexOf(n); return i > 0 ? process.argv[i + 1] : d; };
const BASE = arg("--base", "http://192.168.0.12:8091");
const sleep = (s) => new Promise((r) => setTimeout(r, s * 1000));
const stamp = () => new Date().toISOString().slice(11, 19);

(async () => {
  const browser = await launch();
  const pages = [];
  const open = async () => {
    const ctx = await browser.newContext({ viewport: { width: 412, height: 915 } });
    const page = await ctx.newPage();
    await page.goto(BASE + "/");
    await page.waitForFunction(() => document.getElementById("state-label").textContent === "Live", null, { timeout: 20000 });
    pages.push(page);
  };
  const moving = async () => Promise.all(pages.map(async (p) => {
    const a = await p.locator("#stream").screenshot(); await sleep(0.6);
    return !a.equals(await p.locator("#stream").screenshot());
  }));
  console.log(`${stamp()} phase 0 viewers`); await sleep(Number(arg("--idle", 30)));
  await open(); console.log(`${stamp()} phase 1 viewer`); await sleep(Number(arg("--one", 40)));
  await open(); await open(); console.log(`${stamp()} phase 3 viewers`);
  const end = Date.now() + Number(arg("--three", 120)) * 1000;
  while (Date.now() < end) {
    await sleep(20);
    const fps = await pages[0].textContent("#fps");
    console.log(`${stamp()} ${pages.length} pages, moving=${(await moving()).join(",")}, page0 ${fps}, ${await pages[0].textContent("#viewers")}`);
  }
  await browser.close();
  console.log(`${stamp()} done`);
})();
