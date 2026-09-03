import { createServer } from 'node:http'
import { readFile, mkdir, writeFile } from 'node:fs/promises'
import { resolve, dirname, extname, sep } from 'node:path'
import { fileURLToPath } from 'node:url'
import assert from 'node:assert/strict'
import { chromium } from 'playwright'

const web = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const dist = resolve(web, 'dist/platform')
const artifacts = resolve(web, '../.codex-backups/frontend-foundation')
const mime = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml' }
const server = createServer(async (request, response) => {
  try {
    const file = resolve(dist, '.' + decodeURIComponent(new URL(request.url, 'http://localhost').pathname))
    if (!file.startsWith(dist + sep)) throw new Error('outside build')
    response.setHeader('Content-Type', mime[extname(file)] ?? 'application/octet-stream')
    response.setHeader('Cache-Control', 'no-store')
    response.end(await readFile(file))
  } catch { response.writeHead(404); response.end() }
})
let browser
let page
const results = []
try {
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
  const origin = `http://127.0.0.1:${server.address().port}`
  await mkdir(artifacts, { recursive: true })
  browser = await chromium.launch({ headless: true, channel: process.env.PLAYWRIGHT_CHANNEL || undefined })
  const context = await browser.newContext()
  page = await context.newPage()
  const errors = []
  const dataRequests = []
  page.on('pageerror', error => errors.push(error.message))
  page.on('request', request => {
    if (['fetch', 'xhr', 'websocket'].includes(request.resourceType())) dataRequests.push(request.url())
  })
  for (const [width, height] of [[1920, 1080], [1440, 900], [1366, 768]]) {
    await page.setViewportSize({ width, height })
    for (const mode of ['office', 'monitor']) {
      const route = mode === 'office' ? '/office' : '/monitor/monitoring'
      await page.goto(origin + `/platform.html?case=${mode}-${width}#` + route)
      await page.getByText('页面内容待确认', { exact: true }).waitFor()
      await page.locator(`[data-page-mode="${mode}"]`).waitFor()
      if (mode === 'monitor') await page.getByRole('heading', { name: '监控大屏', exact: true }).first().waitFor()
      const metrics = await page.evaluate(() => {
        const canvas = document.querySelector('.monitor-canvas')
        const rect = canvas?.getBoundingClientRect()
        return {
          scrollWidth: document.documentElement.scrollWidth, scrollHeight: document.documentElement.scrollHeight,
          width: innerWidth, height: innerHeight,
          background: getComputedStyle(document.body).backgroundColor,
          canvas: rect ? { x: rect.x, y: rect.y, width: rect.width, height: rect.height } : null,
          rootTheme: document.documentElement.dataset.theme,
          oldStyle: !!document.querySelector('.ant-app, .trae-badge'),
          typography: {
            pageTitle: getComputedStyle(document.querySelector('.pending-page h1')).fontSize,
            button: getComputedStyle(document.querySelector('.el-button')).fontSize,
            description: getComputedStyle(document.querySelector('.el-empty__description p')).fontSize,
            statusTitle: getComputedStyle(document.querySelector('.el-alert__title')).fontSize,
            statusDescription: getComputedStyle(document.querySelector('.el-alert__description')).fontSize,
            monitorTitle: canvas ? getComputedStyle(document.querySelector('.monitor-header h1')).fontSize : null,
            auxiliary: canvas ? getComputedStyle(document.querySelector('.monitor-clock')).fontSize : null,
            headerInset: canvas ? getComputedStyle(document.querySelector('.monitor-header')).paddingInlineStart : null,
            gap: canvas ? getComputedStyle(canvas).getPropertyValue('--bec-monitor-gap').trim() : null,
            chart: getComputedStyle(canvas ?? document.documentElement).getPropertyValue('--bec-chart-font-size').trim(),
            metric: canvas ? getComputedStyle(canvas).getPropertyValue('--bec-monitor-font-number').trim() : null,
          },
        }
      })
      assert.equal(metrics.rootTheme, 'office-light')
      assert.equal(metrics.background, 'rgb(244, 247, 250)')
      assert.equal(metrics.oldStyle, false)
      assert.ok(metrics.scrollWidth <= width && metrics.scrollHeight <= height, JSON.stringify(metrics))
      assert.equal(metrics.typography.pageTitle, mode === 'monitor' ? '28px' : '16px')
      assert.equal(metrics.typography.button, mode === 'monitor' ? '20px' : '14px')
      assert.equal(metrics.typography.description, mode === 'monitor' ? '20px' : '14px')
      assert.equal(metrics.typography.chart, mode === 'monitor' ? '18px' : '14px')
      assert.equal(metrics.typography.statusTitle, mode === 'monitor' ? '20px' : '16px')
      assert.equal(metrics.typography.statusDescription, mode === 'monitor' ? '18px' : '14px')
      if (mode === 'office') {
        await page.locator('.skip-link').focus()
        await page.keyboard.press('Enter')
        assert.equal(await page.evaluate(() => document.activeElement?.id), 'platform-content')
        assert.ok(page.url().endsWith('#/office'))
      }
      if (mode === 'monitor') {
        assert.equal(metrics.typography.monitorTitle, '40px')
        assert.equal(metrics.typography.auxiliary, '18px')
        assert.equal(metrics.typography.headerInset, '32px')
        assert.equal(metrics.typography.gap, '16px')
        assert.equal(metrics.typography.metric, '44px')
        const scale = Math.min(width / 1920, height / 1080)
        assert.ok(Math.abs(metrics.canvas.width - 1920 * scale) < 1)
        assert.ok(Math.abs(metrics.canvas.height - 1080 * scale) < 1)
        assert.ok(metrics.canvas.x >= -1 && metrics.canvas.y >= -1)
        await page.getByRole('button', { name: '大屏切换', exact: true }).click()
        const dialog = page.getByRole('dialog')
        await dialog.waitFor()
        await dialog.evaluate(async el => { await Promise.all(el.getAnimations({ subtree: true }).map(animation => animation.finished.catch(() => {}))) })
        assert.equal(await dialog.evaluate(el => !!el.closest('.monitor-canvas')), true)
        assert.equal(await page.locator('.el-dialog__title').evaluate(el => getComputedStyle(el).fontSize), '28px')
        const bounds = await page.locator('.el-dialog').boundingBox()
        assert.ok(bounds.x >= 0 && bounds.y >= 0 && bounds.x + bounds.width <= width + 1 && bounds.y + bounds.height <= height + 1, JSON.stringify({ width, height, bounds }))
        await page.getByText('尚未分组', { exact: true }).waitFor()
        await page.getByRole('menuitem', { name: '趋势大屏', exact: true }).click()
        await page.waitForURL('**#/monitor/trend')
        await page.getByText('页面内容待确认', { exact: true }).waitFor()
        await page.reload()
        await page.getByRole('heading', { name: '趋势大屏' }).first().waitFor()
        if (width === 1920) {
          await page.getByRole('button', { name: '进入全屏', exact: true }).click()
          await page.waitForFunction(() => document.fullscreenElement === document.documentElement)
          await page.getByRole('button', { name: '退出全屏', exact: true }).click()
          await page.waitForFunction(() => document.fullscreenElement === null)
        }
      }
      await page.screenshot({ path: resolve(artifacts, `${mode}-${width}x${height}.png`), fullPage: true })
      results.push({ mode, width, height, ...metrics })
    }
  }
  await page.setViewportSize({ width: 1280, height: 1024 })
  await page.goto(origin + '/platform.html#/monitor/not-registered')
  await page.getByText('页面不存在，请返回办公端。', { exact: true }).waitFor()
  await context.setOffline(true)
  await page.getByText('网络连接已断开，请检查网络。', { exact: true }).waitFor()
  assert.equal(await page.evaluate(() => document.documentElement.scrollHeight <= innerHeight), true)
  await context.setOffline(false)
  await page.getByRole('button', { name: '返回办公端', exact: true }).click()
  await page.waitForURL('**#/office')
  assert.equal(await page.locator('.monitor-canvas').count(), 0)
  assert.deepEqual(dataRequests, [])
  assert.deepEqual(errors, [])
  await writeFile(resolve(artifacts, 'browser-results.json'), JSON.stringify({ results, errors, dataRequests }, null, 2))
  process.stdout.write(`PLATFORM_BROWSER_CHECK_OK: ${results.length} layout cases; typography, fullscreen, switching, reload, canvas overlays, offline and no data requests\n`)
} catch (error) {
  if (page) {
    await page.screenshot({ path: resolve(artifacts, 'failure.png'), fullPage: true })
    process.stderr.write(JSON.stringify(await page.evaluate(() => ({ url: location.href, text: document.body.innerText, scrollHeight: document.documentElement.scrollHeight }))) + '\n')
  }
  throw error
} finally {
  await browser?.close()
  await new Promise(resolve => server.close(resolve))
}
