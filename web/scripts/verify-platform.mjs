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
// 仅在浏览器请求拦截中生成授权测试替身；不写入生产菜单、账号或业务数据。
const registry = await readFile(resolve(web, 'src/app/navigation/catalog.ts'), 'utf8')
const paths = []
for (const match of registry.matchAll(/\.\.\.group\('([^']+)', '([^']+)', (\[.*\])\),/g)) {
  const children = JSON.parse(match[3].replaceAll("'", '"'))
  for (const child of children) paths.push('/' + match[1] + '/' + match[2] + '/' + (Array.isArray(child) ? child[0] : child))
}
const screens = await readFile(resolve(web, 'src/modules/large-screen/registry/screens.ts'), 'utf8')
for (const match of screens.matchAll(/path: '([^']+)'/g)) paths.push(match[1])
assert.equal(paths.length, 47)
let grants = paths
let authStatus = 200
const server = createServer(async (request, response) => {
  try {
    const file = resolve(dist, '.' + decodeURIComponent(new URL(request.url, 'http://localhost').pathname))
    if (!file.startsWith(dist + sep)) throw new Error('outside build')
    response.setHeader('Content-Type', mime[extname(file)] ?? 'application/octet-stream')
    response.end(await readFile(file))
  } catch { response.writeHead(404); response.end() }
})
let browser
const results = []
try {
  await new Promise(done => server.listen(0, '127.0.0.1', done))
  await mkdir(artifacts, { recursive: true })
  const origin = 'http://127.0.0.1:' + server.address().port
  browser = await chromium.launch({ headless: true, channel: process.env.PLAYWRIGHT_CHANNEL || undefined })
  const page = await browser.newPage()
  const errors = []
  const unexpectedRequests = []
  page.on('pageerror', error => errors.push(error.message))
  await page.route('**/api/**', async route => {
    const path = new URL(route.request().url()).pathname
    if (!['/api/auth/login', '/api/auth/me', '/api/menu/current', '/api/auth/logout'].includes(path)) {
      unexpectedRequests.push(path); await route.abort(); return
    }
    const data = path.endsWith('/login') ? { token: 'browser-test-only' }
      : path.endsWith('/me') ? { id: 1, username: '测试管理员名称较长的办公账号', roles: [] }
      : path.endsWith('/current') ? grants.map((path, index) => ({ id: index + 1, menuType: 'C', path, visible: 1, status: 1, sortOrder: index, menuName: '' })) : null
    await route.fulfill({ status: authStatus, json: { code: authStatus, data } })
  })
  const visit = route => page.goto(origin + '/platform.html#' + route)
  await visit('/monitor/monitoring')
  await page.getByRole('button', { name: '登录', exact: true }).waitFor()
  await page.getByRole('textbox', { name: '用户名', exact: true }).fill('test-only')
  await page.locator('input[name="password"]').fill('test-only')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await page.getByRole('heading', { name: '选择系统' }).waitFor()
  assert.equal(await page.locator('.system-link').count(), 3)
  for (const [width, height] of [[1920, 1080], [1440, 900], [1366, 768]]) {
    await page.setViewportSize({ width, height })
    for (const path of ['/operations/overview/running', '/configuration/access/users', '/monitor/monitoring']) {
      await visit(path)
      await page.locator('[data-page-path="' + path + '"]').waitFor()
      await page.locator('main .pending-page p').first().waitFor()
      const mode = path.startsWith('/monitor') ? 'monitor' : 'office'
      const metrics = await page.evaluate(() => {
        const header = document.querySelector('header')
        const canvas = document.querySelector('.monitor-canvas')
        const rect = canvas?.getBoundingClientRect()
        const bounds = header.getBoundingClientRect()
        const controls = [...header.querySelectorAll('button,time,strong,h1')].filter(el => el.checkVisibility() && !el.closest('.el-popper'))
        return { width: innerWidth, scrollWidth: document.documentElement.scrollWidth,
          overflow: header.scrollWidth > header.clientWidth,
          clipped: controls.some(el => { const r = el.getBoundingClientRect(); return r.right > bounds.right + 1 || r.bottom > bounds.bottom + 1 }),
          canvas: rect ? { width: rect.width, height: rect.height } : null }
      })
      await page.screenshot({ path: resolve(artifacts, 'latest-inspection.png') })
      assert.ok(metrics.scrollWidth <= width, JSON.stringify(metrics))
      assert.equal(metrics.overflow, false, JSON.stringify(metrics))
      assert.equal(metrics.clipped, false, JSON.stringify(metrics))
      if (mode === 'monitor') {
        assert.ok(Math.abs(metrics.canvas.width / metrics.canvas.height - 16 / 9) < 0.01)
        const sceneBounds = await page.locator('.scene-layer').boundingBox()
        const center = await page.evaluate(() => {
          const rect = document.querySelector('.scene-layer').getBoundingClientRect()
          const title = document.querySelector('.monitor-header h1').getBoundingClientRect()
          const canvas = document.querySelector('.monitor-canvas').getBoundingClientRect()
          return { hit: !!document.elementFromPoint(rect.x + rect.width / 2, rect.y + rect.height / 2)?.closest('.scene-layer'),
            titleOffset: Math.abs(title.x + title.width / 2 - canvas.x - canvas.width / 2) }
        })
        assert.equal(center.hit, true)
        assert.ok(center.titleOffset < 1)
        await page.getByRole('button', { name: '收起左侧面板', exact: true }).focus()
        await page.keyboard.press('Enter')
        await page.getByRole('button', { name: '展开左侧面板', exact: true }).waitFor()
        assert.deepEqual(await page.locator('.scene-layer').boundingBox(), sceneBounds)
        assert.equal(await page.getByRole('button', { name: '收起右侧面板', exact: true }).count(), 1)
        await page.getByRole('button', { name: '收起右侧面板', exact: true }).click()
        assert.deepEqual(await page.locator('.scene-layer').boundingBox(), sceneBounds)
        await page.screenshot({ path: resolve(artifacts, 'monitor-collapsed-' + width + '.png') })
        await page.getByRole('button', { name: '展开左侧面板', exact: true }).click()
        await page.getByRole('button', { name: '展开右侧面板', exact: true }).click()
      }
      if (mode === 'office') {
        await page.getByRole('button', { name: '全局搜索', exact: true }).click()
        await page.getByRole('heading', { name: '全局搜索', exact: true }).waitFor()
        await page.locator('main').click({ position: { x: 400, y: 200 } })
        await page.getByRole('heading', { name: '全局搜索', exact: true }).waitFor({ state: 'hidden' })
      }
      const name = path.split('/')[1] + '-' + width
      await page.screenshot({ path: resolve(artifacts, name + '.png') })
      results.push({ name, metrics })
    }
  }
  // 每个已确认叶子必须能进入；业务页面尚未接入时只应请求认证及授权接口。
  for (const path of paths) {
    await visit(path)
    await page.locator('[data-page-path="' + path + '"]').waitFor()
    await page.locator('main .pending-page p').first().waitFor()
    assert.ok(page.url().endsWith('#' + path), path)
  }
  await visit('/monitor/monitoring')
  await page.locator('[data-page-path="/monitor/monitoring"]').waitFor()
  await page.getByRole('button', { name: '大屏切换', exact: true }).click()
  await page.getByRole('dialog').waitFor()
  assert.equal(await page.locator('.monitor-canvas .el-dialog').count(), 1)
  await page.getByRole('menuitem', { name: '趋势大屏', exact: true }).click()
  await page.locator('[data-page-path="/monitor/trend"]').waitFor()
  assert.equal(await page.locator('.scene-layout').count(), 0)
  await page.getByRole('button', { name: '进入全屏', exact: true }).click()
  await page.getByRole('button', { name: '退出全屏', exact: true }).waitFor()
  await page.getByRole('button', { name: '退出全屏', exact: true }).click()
  await page.getByRole('button', { name: '切换系统', exact: true }).click()
  await page.getByRole('link', { name: '智慧运维平台', exact: true }).click()
  await page.locator('[data-page-path="/operations/overview/running"]').waitFor()
  grants = ['/configuration/access/users']
  await visit('/systems')
  await page.locator('.system-link').waitFor()
  assert.equal(await page.locator('.system-link').count(), 1)
  await visit('/monitor/monitoring')
  await page.waitForURL('**#/403')
  grants = []
  await visit('/systems')
  await page.getByText('暂无可访问的系统，请联系管理员配置权限').waitFor()
  authStatus = 503
  await visit('/systems')
  await page.getByText('访问权限加载失败，请重试').waitFor()
  authStatus = 401
  await visit('/monitor/monitoring')
  await page.waitForURL('**#/login')
  assert.deepEqual(errors, [])
  assert.deepEqual(unexpectedRequests, [])
  await writeFile(resolve(artifacts, 'results.json'), JSON.stringify({ results, verification: 'isolated-browser-only' }, null, 2))
  process.stdout.write('THREE_SYSTEM_BROWSER_OK\n')
} finally {
  await browser?.close()
  await new Promise(done => server.close(done))
}
