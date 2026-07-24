import { chromium } from 'playwright';

const browser = await chromium.launch({ headless: true, channel: 'msedge' });
const page = await browser.newPage({ viewport: { width: 1600, height: 1200 } });

await page.goto('http://localhost:3457/a3a53b5fa109869bbdfd48ef1a8a7e2c.png', { waitUntil: 'networkidle' });

const naturalSize = await page.evaluate(() => {
  const img = document.querySelector('img');
  if (!img) return { w: 0, h: 0, type: 'no-img' };
  return { w: img.naturalWidth, h: img.naturalHeight, type: 'img' };
});
console.log('Image natural size:', naturalSize);

// Set viewport to match image size for full capture
if (naturalSize.w > 0) {
  await page.setViewportSize({ width: naturalSize.w + 40, height: naturalSize.h + 40 });
  // Reload and wait
  await page.goto('http://localhost:3457/a3a53b5fa109869bbdfd48ef1a8a7e2c.png', { waitUntil: 'networkidle' });
  await page.waitForTimeout(500);
}

await page.screenshot({ path: 'D:/word/iot-platform-demo/.scripts/ch5-screenshots/_zhengxin_menu.png', fullPage: true });
console.log('Saved to _zhengxin_menu.png');

// Also extract any text on the page (unlikely for pure image PNG but just in case)
const bodyText = await page.evaluate(() => document.body.innerText);
if (bodyText.trim()) {
  console.log('Page text content:', bodyText.substring(0, 500));
}

await browser.close();
console.log('Done');
