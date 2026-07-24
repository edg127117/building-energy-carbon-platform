import { chromium } from 'playwright';

const browser = await chromium.launch({ headless: true, channel: 'msedge' });
const page = await browser.newPage({ viewport: { width: 2000, height: 1200 } });

// Use the already running server on 3456
await page.goto('http://localhost:3456/测量参数公式对照表.png', { waitUntil: 'networkidle' });

const naturalSize = await page.evaluate(() => {
  const img = document.querySelector('img');
  if (!img) return { w: 0, h: 0 };
  return { w: img.naturalWidth, h: img.naturalHeight };
});
console.log('Image size:', naturalSize);

if (naturalSize.w > 0) {
  await page.setViewportSize({ width: Math.min(naturalSize.w + 40, 2000), height: Math.min(naturalSize.h + 40, 3000) });
  await page.goto('http://localhost:3456/测量参数公式对照表.png', { waitUntil: 'networkidle' });
  await page.waitForTimeout(500);
}

await page.screenshot({ path: 'D:/word/iot-platform-demo/.scripts/ch5-screenshots/_formula_table.png', fullPage: true });
console.log('Saved to _formula_table.png');

await browser.close();
