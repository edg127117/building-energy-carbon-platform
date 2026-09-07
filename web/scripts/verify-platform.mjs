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

const server = createServer(async (request, response) => {
  try {
    const pathname = decodeURIComponent(new URL(request.url, 'http://localhost').pathname)
    const requested = pathname === '/' ? '/index.html' : pathname
    const file = resolve(dist, '.' + requested)
    if (!file.startsWith(dist + sep)) throw new Error('outside build')
    response.setHeader('Content-Type', mime[extname(file)] ?? 'application/octet-stream')
    response.setHeader('Cache-Control', 'no-store')
    response.end(await readFile(file))
  } catch {
    response.writeHead(404)
    response.end()
  }
})

const menuPaths = [
  ['/system/users', '用户管理'], ['/system/roles', '角色与菜单授权'],
  ['/system/menus', '菜单管理'], ['/system/building-access', '建筑访问申请审核'],
  ['/system/buildings', '建筑、空间与系统'], ['/system/devices', '设备与测点'],
  ['/system/device-products', '产品与测点模板'], ['/system/device-onboarding', '待接入设备'],
]
const menuTree = [{
  id: 1, parentId: 0, menuName: '后台管理', menuType: 'M', path: null,
  component: null, perms: null, icon: null, visible: 1, status: 1, sortOrder: 1,
  children: menuPaths.map(([path, menuName], index) => ({
    id: index + 2, parentId: 1, menuName, menuType: 'C', path,
    component: null, perms: null, icon: null, visible: 1, status: 1,
    sortOrder: index + 1, children: [],
  })),
}]
const building = {
  buildingId: 'BLD001', buildingName: '办公楼一号', buildingCode: 'BLD001',
  buildingType: '办公', climateZone: '夏热冬冷',
}
const minute = 1_788_739_200_000
const indicators = ['WCR_COP', 'TOWER_EFF', 'PUMP_EFF', 'AHU_POW_EFF'].map((indicatorCode, index) => ({
  indicatorId: `IND-${index + 1}`, indicatorCode, equipId: `EQ-${index + 1}`,
  minuteStart: minute, status: 'SUCCESS', value: [5.21, 78.4, 82.6, 0.31][index],
  unit: ['', '%', '%', 'W/(m³·h)'][index], dataQuality: 0,
  formulaVersion: 'V1', reasonCode: null, missingInputs: [],
}))

function result(data) {
  return { success: true, code: 200, msg: 'ok', data }
}

let browser
let page
const requests = []
const errors = []
try {
  await new Promise(done => server.listen(0, '127.0.0.1', done))
  const origin = `http://127.0.0.1:${server.address().port}`
  await mkdir(artifacts, { recursive: true })
  browser = await chromium.launch({ headless: true, channel: process.env.PLAYWRIGHT_CHANNEL || undefined })
  const context = await browser.newContext({ viewport: { width: 1366, height: 768 } })
  page = await context.newPage()
  page.on('pageerror', error => errors.push(error.message))
  await page.route('http://localhost:8081/api/**', async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname.replace('/api', '')
    requests.push(`${request.method()} ${path}`)
    let data = null
    if (path === '/auth/login') data = { token: 'browser-token' }
    else if (path === '/auth/me') data = { uid: 1, username: 'admin', roles: ['PLATFORM_ADMIN'] }
    else if (path === '/menu/current') data = menuTree
    else if (path === '/building/list') data = { records: [building], total: 1, size: 100, current: 1, pages: 1 }
    else if (path.endsWith('/snapshot')) data = {
      buildingId: building.buildingId, generatedAt: minute,
      points: [{
        pointId: 'POINT001', pointCode: 'WCR1_TWin', pointName: '冷冻水进水温度',
        equipId: 'EQ-1', equipCode: 'WCR1', unit: '℃', minute, average: 12.4,
        minimum: 12.1, maximum: 12.8, sampleCount: 60, dataQuality: 0, status: 'NORMAL',
      }],
    }
    else if (path.endsWith('/indicators/latest')) data = { buildingId: building.buildingId, generatedAt: minute, indicators }
    else if (path.endsWith('/indicators/trends')) data = {
      buildingId: building.buildingId, from: minute - 3_600_000, to: minute,
      resolutionMinutes: 5,
      series: indicators.map((item, index) => ({
        indicatorId: item.indicatorId, indicatorCode: item.indicatorCode,
        equipId: item.equipId,
        records: [0, 1, 2, 3].map(step => ({
          time: minute - (3 - step) * 900_000,
          average: Number(item.value) + index + step / 10,
          minimum: Number(item.value) + index, maximum: Number(item.value) + index + 0.4,
          sampleCount: 5, dataQuality: 0,
        })),
      })),
    }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(result(data)) })
  })

  await page.goto(`${origin}/#/login`)
  await page.getByPlaceholder('请输入账号').fill('admin')
  await page.getByPlaceholder('请输入密码').fill('password')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await page.waitForURL('**#/office/dashboard')
  await page.getByRole('heading', { name: '核心看板', exact: true }).waitFor()
  assert.equal(await page.locator('[data-page-mode="office"]').count(), 1)
  assert.equal(await page.getByText('基础框架预览', { exact: true }).count(), 0)
  assert.equal(await page.locator('.ant-layout, .trae-badge').count(), 0)
  assert.ok((await page.getByText('平台管理员', { exact: true }).count()) > 0)
  await page.screenshot({ path: resolve(artifacts, 'office-1366x768.png'), fullPage: true })

  await page.setViewportSize({ width: 1920, height: 1080 })
  await page.reload()
  await page.getByRole('heading', { name: '核心看板', exact: true }).waitFor()
  assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth))
  await page.screenshot({ path: resolve(artifacts, 'office-1920x1080.png'), fullPage: true })

  await page.goto(`${origin}/#/office/trends`)
  await page.getByRole('heading', { name: '趋势分析', exact: true }).waitFor()
  await page.locator('.chart-view').first().waitFor()
  await page.waitForTimeout(250)
  assert.ok(await page.locator('.chart-view svg').count() > 0)
  await page.screenshot({ path: resolve(artifacts, 'trends-1920x1080.png'), fullPage: true })

  await page.goto(`${origin}/#/monitor/monitoring`)
  await page.locator('[data-page-mode="monitor"]').waitFor()
  await page.getByRole('heading', { name: '监控大屏', exact: true }).first().waitFor()
  assert.equal(await page.getByText('基础框架预览', { exact: true }).count(), 0)
  const canvas = await page.locator('.monitor-canvas').boundingBox()
  assert.ok(canvas && Math.abs(canvas.width - 1920) < 1 && Math.abs(canvas.height - 1080) < 1)
  await page.screenshot({ path: resolve(artifacts, 'monitor-1920x1080.png'), fullPage: true })
  await page.getByRole('button', { name: '大屏切换', exact: true }).click()
  const dialog = page.getByRole('dialog')
  await dialog.waitFor()
  assert.equal(await dialog.evaluate(element => Boolean(element.closest('.monitor-canvas'))), true)

  assert.ok(requests.includes('POST /auth/login'))
  assert.ok(requests.includes('GET /auth/me'))
  assert.ok(requests.includes('GET /menu/current'))
  assert.deepEqual(errors, [])
  await writeFile(resolve(artifacts, 'browser-results.json'), JSON.stringify({ requests, errors }, null, 2))
  process.stdout.write('PLATFORM_BROWSER_CHECK_OK: login, office, trend, monitor, 1366 and 1920 layouts\n')
} catch (error) {
  if (page) await page.screenshot({ path: resolve(artifacts, 'failure.png'), fullPage: true })
  throw error
} finally {
  await browser?.close()
  await new Promise(done => server.close(done))
}
