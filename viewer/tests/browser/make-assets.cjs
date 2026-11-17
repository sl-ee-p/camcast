// Dev helper. Renders web/static/icon.svg to the PNG icons, and (with --frames DIR) writes 720x1280
// test JPEGs with a moving bar and a frame counter for the fake CamCast. --noise adds sensor-like grain so
// frames weigh about what a real camera sends (~100-150 KB), for load tests.
const { launch } = require("./pw.cjs");
const fs = require("fs");
const path = require("path");

(async () => {
  const root = path.resolve(__dirname, "../..");
  const browser = await launch();
  const page = await browser.newPage();
  const svg = fs.readFileSync(path.join(root, "web/static/icon.svg"), "utf8");
  for (const size of [192, 512]) {
    await page.setViewportSize({ width: size, height: size });
    await page.setContent(`<style>*{margin:0}svg{width:${size}px;height:${size}px;display:block}</style>${svg}`);
    await page.screenshot({ path: path.join(root, `web/static/icon-${size}.png`) });
  }
  const at = process.argv.indexOf("--frames");
  if (at > 0) {
    const dir = process.argv[at + 1];
    fs.mkdirSync(dir, { recursive: true });
    const noise = process.argv.includes("--noise");
    const frames = await page.evaluate((noise) => {
      const canvas = document.createElement("canvas");
      canvas.width = 720; canvas.height = 1280;
      const g = canvas.getContext("2d");
      const out = [];
      for (let i = 0; i < 30; i++) {
        const grad = g.createLinearGradient(0, 0, 720, 1280);
        grad.addColorStop(0, "#1d3b53"); grad.addColorStop(1, "#5a2d4a");
        g.fillStyle = grad; g.fillRect(0, 0, 720, 1280);
        g.fillStyle = "#5cc8ff"; g.fillRect(0, (i / 30) * 1180, 720, 100);
        g.fillStyle = "#ffffff"; g.font = "bold 120px sans-serif"; g.textAlign = "center";
        g.fillText("TEST " + String(i).padStart(2, "0"), 360, 640);
        g.font = "48px sans-serif"; g.fillText("fake CamCast 720×1280", 360, 760);
        g.fillText("▲ top", 360, 80);
        if (noise) {
          const img = g.getImageData(0, 0, 720, 1280);
          for (let p = 0; p < img.data.length; p += 4) {
            const n = (Math.random() - 0.5) * 34;
            img.data[p] += n; img.data[p + 1] += n; img.data[p + 2] += n;
          }
          g.putImageData(img, 0, 0);
        }
        out.push(canvas.toDataURL("image/jpeg", 0.85).split(",")[1]);
      }
      return out;
    }, noise);
    frames.forEach((b64, i) => fs.writeFileSync(path.join(dir, `f${String(i).padStart(2, "0")}.jpg`), Buffer.from(b64, "base64")));
    console.log(`wrote ${frames.length} frames to ${dir}`);
  }
  await browser.close();
})();
