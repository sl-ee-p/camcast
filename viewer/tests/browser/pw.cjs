// Loads Playwright the way this dev box has it: `playwright` if installed, else `playwright-core` via NODE_PATH,
// with the browser from CHROME_PATH (default: the cached Chromium build homelab-twin uses).
const os = require("os");
const fs = require("fs");

const { chromium } = (() => {
  try { return require("playwright"); } catch { return require("playwright-core"); }
})();

const fallback = `${os.homedir()}/.cache/ms-playwright/chromium-1248/chrome-linux64/chrome`;
const executablePath = process.env.CHROME_PATH || (fs.existsSync(fallback) ? fallback : undefined);

module.exports = { chromium, launch: (options = {}) => chromium.launch({ executablePath, ...options }) };
