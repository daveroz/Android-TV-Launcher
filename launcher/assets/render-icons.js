// Renders the SVGs in this folder into the app's icon and banner PNGs for every screen density.
// Run from the launcher folder: node assets/render-icons.js && python3 assets/to-webp.py
// (needs Playwright with Chromium, and Pillow)
const { chromium } = require('playwright');
const path = require('path');

const res = path.join(__dirname, '..', 'app', 'src', 'main', 'res');
const densities = { mdpi: 1, hdpi: 1.5, xhdpi: 2, xxhdpi: 3, xxxhdpi: 4 };
const jobs = [];
for (const [name, scale] of Object.entries(densities)) {
  for (const layer of ['background', 'foreground', 'monochrome']) {
    const px = Math.round(108 * scale); // adaptive icon layers are 108dp square
    jobs.push([`icon-${layer}.svg`, px, px, `mipmap-${name}/ic_launcher_${layer}.png`]);
  }
}
jobs.push(['banner.svg', 320, 180, 'drawable-xhdpi/banner.png']);
jobs.push(['banner.svg', 640, 360, 'drawable-xxxhdpi/banner.png']);
jobs.push(['icon.svg', 512, 512, '../../../../docs/icon.png']);

(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage();
  for (const [svg, width, height, out] of jobs) {
    await page.setViewportSize({ width, height });
    await page.goto('file://' + path.join(__dirname, svg));
    await page.evaluate(() => document.fonts.ready);
    const file = path.join(res, out);
    require('fs').mkdirSync(path.dirname(file), { recursive: true });
    await page.screenshot({ path: file, omitBackground: true });
    console.log(out, `${width}x${height}`);
  }
  await browser.close();
})();
