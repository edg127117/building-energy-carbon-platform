import { chromium } from 'playwright';

const BASE = 'http://localhost:3456/第5章_建筑调适技术.html';
const OUT = 'D:/word/iot-platform-demo/.scripts/ch5-screenshots';

const KEY_PAGES = [
  { id: 'p77', label: '变风量空调系统调适-单机调适开始' },
  { id: 'p78', label: '系统图(动画原型)' },
  { id: 'p79', label: '系统图续' },
  { id: 'p80', label: 'COP计算相关' },
  { id: 'p81', label: 'COP计算相关' },
  { id: 'p82', label: '冷水机组COP公式(5-1附近)' },
  { id: 'p83', label: '冷水机组COP公式(5-3附近)' },
  { id: 'p84', label: '冷水机组COP公式续' },
  { id: 'p85', label: '冷却塔效率' },
  { id: 'p86', label: '冷却塔效率续' },
  { id: 'p87', label: '水泵效率(5-5附近)' },
  { id: 'p88', label: '水泵效率(5-6附近)' },
  { id: 'p89', label: '水泵效率续' },
  { id: 'p90', label: '风系统功耗效率(5.7附近)' },
  { id: 'p91', label: '风系统功耗效率续' },
  { id: 'p92', label: '风量计算' },
  { id: 'p93', label: '风量测量方法' },
  { id: 'p94', label: '测量点位' },
  { id: 'p95', label: '测量仪器' },
  { id: 'p96', label: '换热器/散热片数据段' },
  { id: 'p97', label: '测量工具章节开始' },
];

async function main() {
  const fs = await import('fs');
  if (!fs.existsSync(OUT)) fs.mkdirSync(OUT, { recursive: true });

  const browser = await chromium.launch({ headless: true, channel: 'msedge' });
  const page = await browser.newPage({ viewport: { width: 1400, height: 1000 } });

  for (const p of KEY_PAGES) {
    console.log(`[capture] ${p.id} - ${p.label}`);
    await page.goto(`${BASE}#${p.id}`, { waitUntil: 'networkidle', timeout: 15000 });
    await page.waitForTimeout(800);

    // Wait for image to load
    try {
      await page.waitForSelector(`#${p.id} .page-image.loaded`, { timeout: 10000 });
    } catch {
      // Fallback: just wait a bit more
      await page.waitForTimeout(2000);
    }

    const img = await page.$(`#${p.id} .page-image`);
    if (img) {
      const src = await img.getAttribute('src');
      console.log(`  图片: ${src}`);
      const naturalDim = await img.evaluate(el => ({ w: el.naturalWidth, h: el.naturalHeight, c: el.complete }));
      console.log(`  尺寸: ${naturalDim.w}x${naturalDim.h}, 完整: ${naturalDim.c}`);
    }

    // Save screenshot of just this page section
    const section = await page.$(`#${p.id}`);
    if (section) {
      await section.screenshot({ path: `${OUT}/${p.id}.png` });
      console.log(`  截图已保存: ${OUT}/${p.id}.png`);
    }
  }

  // Also capture the first page visible to confirm images load correctly
  await page.goto(`${BASE}#p77`, { waitUntil: 'networkidle', timeout: 15000 });
  await page.waitForTimeout(2000);
  await page.screenshot({ path: `${OUT}/_p77_fullpage.png`, fullPage: false });

  await browser.close();
  console.log('全部完成');
}

main().catch(e => { console.error(e); process.exit(1); });
