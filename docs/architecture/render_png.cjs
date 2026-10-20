// Renders the architecture SVG to a 2x PNG with the Playwright install from www/.
// Usage (from the repository root): node docs/architecture/render_png.cjs
const path = require('path');
const fs = require('fs');
const { chromium } = require(path.join(__dirname, '../../www/node_modules/playwright'));

(async () => {
  const svg = path.join(__dirname, 'yano-x-architecture.svg');
  const [, w, h] = fs.readFileSync(svg, 'utf8').match(/viewBox="0 0 (\d+) (\d+)"/).map(Number);
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: w, height: h }, deviceScaleFactor: 2 });
  await page.goto('file://' + svg);
  await page.screenshot({ path: path.join(__dirname, 'yano-x-architecture.png'), clip: { x: 0, y: 0, width: w, height: h } });
  await browser.close();
})();
