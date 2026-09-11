import { createServer } from 'node:http'
import { readFile, mkdir, writeFile } from 'node:fs/promises'
import { resolve, dirname, extname, sep } from 'node:path'
import { fileURLToPath } from 'node:url'
import assert from 'node:assert/strict'
import { chromium } from 'playwright'

const web = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const dist = resolve(web, 'dist')
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
const migratedPaths = new Set(['/operations/realtime/hvac', '/operations/energy/trend',
  '/configuration/access/users', '/configuration/access/roles', '/configuration/settings/menus', '/configuration/access/buildingAccess',
  '/configuration/space/buildings', '/configuration/ingestion/points', '/configuration/ingestion/products', '/configuration/ingestion/pendingDevices'])
const businessRequests = []
const building = { buildingId: 'TEST-BUILDING', buildingName: '隔离测试建筑', buildingCode: 'TEST-BUILDING' }
const minute = 1788739200000
const indicators = ['WCR_COP', 'TOWER_EFF', 'PUMP_EFF', 'AHU_POW_EFF'].map((indicatorCode, index) => ({
  indicatorId: `TEST-${index}`, indicatorCode, equipId: `TEST-EQUIP-${index}`, minuteStart: minute,
  status: 'SUCCESS', value: index + 1, unit: '', dataQuality: 0, formulaVersion: 'TEST', missingInputs: [],
}))
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
      const paginated = ['/api/system/users', '/api/building/list', '/api/v1/assets/buildings', '/api/v1/assets/equipment', '/api/v1/assets/system-groups', '/api/v1/device-products', '/api/v1/device-onboarding/pending']
      const arrays = ['/api/system/roles', '/api/menu/admin/tree', '/api/system/building-access/requests', '/api/v1/assets/spaces']
      let data
      if (paginated.includes(path)) data = path.startsWith('/api/v1/')
        ? { items: [], total: 0, page: 1, size: 100 }
        : { records: path === '/api/building/list' ? [building] : [], total: path === '/api/building/list' ? 1 : 0, current: 1, size: 100, pages: 1 }
      else if (arrays.includes(path)) data = []
      else if (path.endsWith('/snapshot')) data = { buildingId: building.buildingId, generatedAt: minute, points: [] }
      else if (path.endsWith('/indicators/latest')) data = { buildingId: building.buildingId, generatedAt: minute, indicators }
      else if (path.endsWith('/indicators/trends')) data = { buildingId: building.buildingId, from: minute - 3600000, to: minute, resolutionMinutes: 5,
        series: indicators.map(item => ({ ...item, records: [{ time: minute, average: item.value, minimum: item.value, maximum: item.value, sampleCount: 1, dataQuality: 0 }] })) }
      else { unexpectedRequests.push(path); await route.abort(); return }
      assert.equal(route.request().method(), 'GET')
      assert.equal(route.request().headers().authorization, 'Bearer browser-test-only')
      businessRequests.push(path)
      await route.fulfill({ status: 200, json: { code: 200, data } }); return
    }
    const data = path.endsWith('/login') ? { token: 'browser-test-only' }
      : path.endsWith('/me') ? { id: 1, username: '测试管理员名称较长的办公账号', roles: ['PLATFORM_ADMIN'] }
      : path.endsWith('/current') ? grants.map((path, index) => ({ id: index + 1, menuType: 'C', path, visible: 1, status: 1, sortOrder: index, menuName: '' })) : null
    await route.fulfill({ status: authStatus, json: { code: authStatus, data } })
  })
  const visit = route => page.goto(origin + '/index.html#' + route)
  await visit('/monitor/monitoring')
  await page.getByRole('button', { name: '登录', exact: true }).waitFor()
  await page.getByRole('textbox', { name: '用户名', exact: true }).fill('test-only')
  await page.locator('input[name="password"]').fill('test-only')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await page.getByRole('heading', { name: '选择工作区' }).waitFor()
  assert.equal(await page.locator('.system-link').count(), 3)
  for (const [width, height] of [[1920, 1080], [1440, 900], [1366, 768]]) {
    await page.setViewportSize({ width, height })
    for (const selected of [['monitor'], ['monitor', 'operations'], ['monitor', 'operations', 'configuration']]) {
      grants = paths.filter(path => selected.includes(path.split('/')[1]))
      await visit('/systems')
      await page.locator('.system-link').first().waitFor()
      assert.equal(await page.locator('.system-link').count(), selected.length)
      const selection = await page.evaluate(() => ({
        overflow: document.documentElement.scrollWidth > innerWidth,
        cards: [...document.querySelectorAll('.system-link')].map(el => { const r = el.getBoundingClientRect(); return { x: r.x, width: r.width, y: r.y, right: r.right } }),
      }))
      assert.equal(selection.overflow, false)
      assert.ok(selection.cards.every(card => card.width <= 368 && card.y === selection.cards[0].y))
      assert.ok(Math.abs(selection.cards[0].x - (width - selection.cards.at(-1).right)) < 2)
      await page.locator('.system-link').first().focus()
      await page.screenshot({ path: resolve(artifacts, `selection-${selected.length}-${width}.png`) })
      await page.keyboard.press('Enter')
      await page.locator('[data-page-path]').waitFor()
    }
    grants = paths
    for (const path of ['/operations/overview/running', '/configuration/access/users', '/monitor/monitoring']) {
      await visit(path)
      await page.locator('[data-page-path="' + path + '"]').waitFor()
      await page.locator('main h1').first().waitFor()
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
        assert.equal(await page.locator('.company-logo').count(), 1)
        if (!migratedPaths.has(path)) {
        assert.equal(await page.locator('.pending-panel').count(), 1)
        const panel = await page.locator('.pending-panel').evaluate(el => ({
          text: el.textContent.replace(/\s+/g, ''),
          background: getComputedStyle(el).backgroundColor,
        }))
        assert.ok(panel.text.endsWith('待建设'))
        assert.equal(panel.background, 'rgb(255, 255, 255)')
        } else {
          assert.equal(await page.locator('.pending-panel').count(), 0)
          await page.locator('main .el-empty').first().waitFor()
        }
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
  // 已迁移页面访问隔离业务替身，其余已确认入口继续保持待建设占位。
  for (const path of paths) {
    await visit(path)
    await page.locator('[data-page-path="' + path + '"]').waitFor()
    await page.locator('main h1').first().waitFor().catch(async error => {
      throw new Error(`${path}: ${error.message}; page errors: ${errors.join('; ')}; content: ${await page.locator('main').innerText()}`)
    })
    if (!migratedPaths.has(path)) await page.locator('main .pending-page p').first().waitFor()
    assert.ok(page.url().endsWith('#' + path), path)
    if (path === '/operations/realtime/hvac') {
      await page.locator('.indicator-grid').waitFor()
      assert.equal(await page.locator('.indicator-grid > *').count(), 4)
      await page.screenshot({ path: resolve(artifacts, 'hvac-business.png') })
    }
    if (path === '/operations/energy/trend') {
      await page.locator('.trend-chart canvas, .trend-chart svg').first().waitFor()
      await page.screenshot({ path: resolve(artifacts, 'trend-business.png') })
    }
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
  for (const endpoint of ['/api/system/users', '/api/v1/assets/buildings', '/api/v1/device-products', '/api/v1/device-onboarding/pending']) assert.ok(businessRequests.includes(endpoint), endpoint)
  assert.ok(businessRequests.some(path => path.endsWith('/indicators/latest')))
  assert.ok(businessRequests.some(path => path.endsWith('/indicators/trends')))
  await writeFile(resolve(artifacts, 'results.json'), JSON.stringify({ results, businessRequests, verification: 'isolated-browser-only' }, null, 2))
  process.stdout.write('THREE_SYSTEM_BROWSER_OK\n')
} finally {
  await browser?.close()
  await new Promise(done => server.close(done))
}
