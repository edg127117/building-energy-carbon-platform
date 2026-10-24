import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import { readFile, stat } from 'node:fs/promises'
import { networkInterfaces } from 'node:os'
import { dirname, extname, resolve, sep } from 'node:path'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'

// 仅向本机静态站点提供隔离夹具，拒绝外部请求及所有写请求；不连接部署环境。
const require = createRequire(import.meta.url)
const { chromium } = require(process.env.PLAYWRIGHT_MODULE_PATH || 'playwright')
const dist = resolve(dirname(fileURLToPath(import.meta.url)), '../dist')
await stat(resolve(dist, 'index.html'))
const candidates = Object.values(networkInterfaces()).flat().filter(item => item?.family === 'IPv4' && !item.internal)
const address = process.env.HTTP_TEST_HOST || candidates.find(item => item.address.startsWith('192.168.'))?.address
  || candidates.find(item => /^(10\.|172\.(1[6-9]|2\d|3[01])\.)/.test(item.address))?.address
assert(address, 'A local LAN IPv4 address is required; set HTTP_TEST_HOST if necessary')
const pages = [
  { path: '/operations/energy/energyItems', kind: 'reporting' },
  { path: '/operations/energy/energyItems?mode=parameters', kind: 'parameters' },
  { path: '/operations/energy/energyZones', kind: 'classification' },
  { path: '/configuration/space/equipmentSpaces', kind: 'equipment' },
]
const routePaths = [...new Set(pages.map(item => item.path.split('?')[0]))]
const menus = []
let menuId = 0
for (const path of routePaths) {
  const workspace = '/' + path.split('/')[1]
  const groupPath = path.slice(0, path.lastIndexOf('/'))
  const entry = (value, type) => ({ id: ++menuId, menuName: '', path: value, menuType: type, visible: 1, status: 1, sortOrder: menuId, children: [] })
  let root = menus.find(item => item.path === workspace)
  if (!root) { root = entry(workspace, 'M'); menus.push(root) }
  let group = root.children.find(item => item.path === groupPath)
  if (!group) { group = entry(groupPath, 'M'); root.children.push(group) }
  group.children.push(entry(path, 'C'))
}
const contentTypes = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.json': 'application/json' }
const escapedApiRequests = []
const server = createServer(async (request, response) => {
  try {
    const pathname = new URL(request.url, 'http://local.test').pathname
    if (pathname.startsWith('/api')) { escapedApiRequests.push(pathname); response.writeHead(410).end(); return }
    assert.equal(request.method, 'GET')
    const path = resolve(dist, '.' + decodeURIComponent(pathname === '/' ? '/index.html' : pathname))
    assert(path.startsWith(dist + sep))
    response.writeHead(200, { 'Content-Type': contentTypes[extname(path)] || 'application/octet-stream', 'Cache-Control': 'no-store' })
    response.end(await readFile(path))
  } catch { response.writeHead(404).end() }
})
await new Promise((done, reject) => { server.once('error', reject); server.listen(0, address, done) })
const origin = `http://${address}:${server.address().port}`
let browser
const results = []
try {
  browser = await chromium.launch({ ...(process.env.PLAYWRIGHT_BROWSER_CHANNEL ? { channel: process.env.PLAYWRIGHT_BROWSER_CHANNEL } : {}), headless: true, args: ['--no-proxy-server'] })
  for (const pageSpec of pages) {
    const { path, kind } = pageSpec
    const context = await browser.newContext()
    try {
      const page = await context.newPage()
      const errors = [], requests = [], unexpected = []
      page.on('pageerror', error => errors.push(error.message))
      page.on('requestfailed', request => errors.push(request.url() + ': ' + request.failure()?.errorText))
      page.on('response', response => { if (response.status() >= 400) errors.push(response.status() + ' ' + new URL(response.url()).pathname) })
      await page.addInitScript(() => localStorage.setItem('token', 'isolated-http-fixture'))
      await page.route('**/*', async route => {
        const request = route.request(), url = new URL(request.url())
        if (url.origin !== origin) { unexpected.push(request.url()); await route.abort(); return }
        if (!url.pathname.startsWith('/api/')) { await route.continue(); return }
        requests.push({ method: request.method(), path: url.pathname })
        let data
        if (request.method() !== 'GET') { unexpected.push(request.method() + ' ' + url.pathname); await route.abort(); return }
        if (url.pathname === '/api/auth/me') data = { id: 1, username: 'http-fixture', roles: ['PLATFORM_ADMIN', 'ENERGY_MANAGER'] }
        else if (url.pathname === '/api/menu/current') data = menus
        else if (url.pathname.endsWith('/device-grants/access')) data = { testOpenControls: false }
        else if (url.pathname === '/api/building/list') data = { records: [], total: 0 }
        else if (url.pathname.endsWith('/rule-options')) data = { formulas: [], roundingPolicies: [] }
        else if (/^\/api\/v1\/(carbon-management\/(factor-sources|factors)|energy-catalog\/(items|units|compatibilities))$/.test(url.pathname)) data = []
        else { unexpected.push(url.pathname); await route.fulfill({ status: 501, json: { code: 501, data: null } }); return }
        await route.fulfill({ status: 200, json: { code: 200, data } })
      })
      await page.goto(origin + '/#' + path)
      await page.waitForLoadState('networkidle')
      const capabilities = await page.evaluate(() => ({ secure: isSecureContext, randomUUID: typeof crypto.randomUUID, getRandomValues: typeof crypto.getRandomValues }))
      assert.deepEqual(capabilities, { secure: false, randomUUID: 'undefined', getRandomValues: 'function' }, 'The test must use a real insecure HTTP origin without a crypto shim')
      assert(!(await page.locator('body').innerText()).includes('页面加载失败'))
      const tabs = []
      if (kind === 'reporting') {
        assert(await page.getByRole('heading', { name: '能源账单填报', exact: true }).isVisible())
      } else if (kind === 'parameters') {
        assert(await page.getByRole('heading', { name: '专业参数包', exact: true }).isVisible())
        assert.equal(requests.some(request => request.path.includes('/energy-parameter-packages/')), false)
      } else if (kind === 'classification') {
        assert(await page.getByRole('heading', { name: '通用计量分类', exact: true }).isVisible())
        for (const tab of await page.getByRole('tab').all()) {
          tabs.push(await tab.innerText()); await tab.click()
          assert(!(await page.locator('body').innerText()).includes('页面加载失败'))
        }
        await page.getByRole('tab', { name: '人工读数', exact: true }).click()
        const reason = page.locator('input[data-test="workbook-reason"], [data-test="workbook-reason"] input')
        await reason.fill('仅本机 HTTP 回归，不提交配置')
        await page.getByRole('button', { name: '清空工作簿选择', exact: true }).click()
        assert.equal(await reason.inputValue(), '')
      } else {
        assert(await page.getByRole('heading', { name: '设备与空间关联', exact: true }).isVisible())
      }
      assert.deepEqual(errors, [])
      assert.deepEqual(unexpected, [])
      assert(requests.every(request => request.method === 'GET'))
      results.push({ path, capabilities, tabs, apiRequests: requests.length })
    } finally { await context.close() }
  }
  assert.deepEqual(escapedApiRequests, [])
  console.log('LAN_HTTP_IDEMPOTENCY_VERIFIED ' + JSON.stringify({ origin, results, externalRequests: 0, writes: 0 }))
} finally {
  await browser?.close()
  await new Promise((done, reject) => server.close(error => error ? reject(error) : done()))
}
