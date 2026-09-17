/* Real browser -> production HTTP endpoints -> dedicated engines. No request interception. */
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const { chromium } = require(process.env.DAIKIN_PLAYWRIGHT_MODULE || 'playwright');
const root = path.resolve(__dirname, '..');
const settings = JSON.parse(fs.readFileSync(path.join(root, 'target/daikin-target-env.json'), 'utf8').replace(/^\uFEFF/, ''));
const front = process.env.DAIKIN_FRONTEND_URL || 'http://127.0.0.1:5180';
const api = `http://127.0.0.1:${settings.backendPort}/api`;
assert.equal(new URL(front).hostname, '127.0.0.1', 'Use the isolated loopback frontend.');
const output = path.join(root, 'target/daikin-target-browser');
fs.mkdirSync(output, { recursive: true });
const resume = process.env.DAIKIN_TARGET_BROWSER_RESUME === 'true';
const evidence = { manufacturer: 'TEST_PROVIDER', engines: 'MySQL + TDengine', resumed: resume, checks: [], requests: [], errors: [] };

async function control(action, method = 'POST') {
  const response = await fetch(`${api}/__test/daikin/${action}`, { method });
  assert.equal(response.status, 200, `Test control ${action} failed (${response.status}).`);
  const value = await response.json();
  return value.data || value;
}

async function eventually(read, predicate, label) {
  const deadline = Date.now() + 45000;
  while (Date.now() < deadline) {
    const result = await read();
    if (predicate(result)) return result;
    await new Promise(resolve => setTimeout(resolve, 300));
  }
  throw new Error(`Timed out: ${label}`);
}

async function login(page, username, password) {
  await page.goto(`${front}/#/login`);
  await page.locator('input[name="username"]').fill(username);
  await page.locator('input[name="password"]').fill(password);
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await page.waitForURL('**/#/systems');
}

async function business(page, route) {
  return page.evaluate(async ({ api, route }) => {
    const response = await fetch(api + route, { headers: { Authorization: `Bearer ${localStorage.getItem('token')}` } });
    return { status: response.status, body: await response.json() };
  }, { api, route });
}

(async () => {
  const browser = await chromium.launch({ channel: process.env.DAIKIN_BROWSER_CHANNEL || 'msedge', headless: true });
  const context = await browser.newContext({ viewport: { width: 1440, height: 1050 } });
  const page = await context.newPage();
  page.setDefaultTimeout(30000);
  page.on('pageerror', error => evidence.errors.push(error.message));
  page.on('response', response => {
    const url = new URL(response.url());
    if (url.pathname.startsWith('/api/')) evidence.requests.push({ path: url.pathname, status: response.status() });
  });
  try {
    if (!resume) await control('reset');
    let state = await control('state', 'GET');
    await login(page, 'admin', settings.adminPassword);
    evidence.checks.push('Real admin login and server menu authorization');
    await page.goto(`${front}/#/operations/devices/pendingDevices`);
    await page.getByRole('heading', { name: '待接入设备', exact: true }).waitFor();
    await page.getByRole('tab', { name: '大金空调接入', exact: true }).waitFor();
    assert.equal(await page.getByRole('button', { name: '厂家设备', exact: true }).count(), 0,
      'Device source switch must use clear content tabs instead of header buttons.');
    await page.getByPlaceholder('请输入配置管理员提供的数据源标识').fill(state.sourceId);
    const submitted = page.waitForResponse(response => response.url().endsWith('/sync-jobs') && response.request().method() === 'POST');
    await page.getByRole('button', { name: '开始目录同步', exact: true }).click();
    assert.equal((await submitted).status(), 202);
    assert.equal(await page.getByPlaceholder('请输入配置管理员提供的数据源标识').inputValue(), '',
      'Submitted technical source id must be cleared from the customer-facing form.');
    await control('dispatch');
    state = await eventually(() => control('state', 'GET'), value => Boolean(value.pendingId), 'directory persisted');
    const refreshed = page.waitForResponse(response => response.url().includes('/sync-jobs/') && response.request().method() === 'GET');
    await page.getByRole('button', { name: '更新任务状态', exact: true }).click();
    const job = await (await refreshed).json();
    assert.equal(job.data.status, 'SUCCEEDED');
    await page.getByText('已完成', { exact: true }).waitFor();
    await page.getByText('最近一次同步', { exact: true }).waitFor();
    await page.getByText('未发现异常', { exact: true }).waitFor();
    assert.equal(await page.getByText('SUCCEEDED', { exact: true }).count(), 0, 'Directory status must not expose the backend enum.');
    assert.equal(await page.getByText(state.sourceId, { exact: true }).isVisible(), false,
      'Full source id must remain collapsed in technical details.');
    assert.equal(await page.getByText(job.data.jobId, { exact: true }).isVisible(), false,
      'Full job id must remain collapsed in technical details.');
    await page.screenshot({ path: path.join(output, '01-directory.png'), fullPage: true });
    await page.setViewportSize({ width: 390, height: 844 });
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth), false,
      'Directory onboarding must not overflow the narrow viewport.');
    await page.screenshot({ path: path.join(output, '01-directory-mobile.png'), fullPage: true });
    await page.setViewportSize({ width: 1440, height: 1050 });
    evidence.checks.push('UI submitted manufacturer directory job; actual backend persisted pending device');

    // Approval fixture calls the existing governance service; it does not bypass binding with SQL.
    if (!resume) {
      await control('approve');
      await control('collect');
      for (const value of [36, 0, 24.5]) {
        await control(`temperature/${value}`);
        await control('collect');
      }
    }
    state = await control('state', 'GET');
    assert(state.equipmentId && state.pointId, 'Binding must create a real equipment and point.');
    await page.goto(`${front}/#/operations/realtime/hvac?buildingId=${state.buildingId}`);
    await page.getByRole('cell', { name: new RegExp(state.equipmentName) }).waitFor();
    await page.getByRole('textbox', { name: '设备搜索', exact: true }).fill('不存在的设备');
    await page.getByRole('button', { name: '查询', exact: true }).click();
    await page.getByText('暂无已接入的厂家设备', { exact: true }).waitFor();
    await page.getByRole('textbox', { name: '设备搜索', exact: true }).fill(state.equipmentName);
    await page.getByRole('button', { name: '查询', exact: true }).click();
    await page.getByRole('button', { name: '查看监测', exact: true }).click();
    const visibleHistory = page.waitForResponse(response => new URL(response.url()).pathname.endsWith('/temperatures'));
    await page.getByRole('tab', { name: '温度曲线', exact: true }).click();
    assert.equal((await visibleHistory).status(), 200);
    const temperatures = await business(page, `/v1/hvac-monitoring/devices/${state.equipmentId}/temperatures/current?field=roomTemp`);
    assert.equal(temperatures.status, 200);
    assert(temperatures.body.data?.reading, 'Temperature must be read back from the real storage.');
    assert.equal(Number(temperatures.body.data.reading.value), Number(state.expectedRoomTemp));
    const list = await business(page, `/v1/hvac-monitoring/buildings/${state.buildingId}/devices`);
    const shownDevice = list.body.data.items.find(item => item.equipmentId === state.equipmentId);
    assert(shownDevice, 'Device must remain in its real building scope.');
    assert(Math.abs(temperatures.body.data.reading.observedAt - shownDevice.lastValidAt) < 5000,
      'Temperature and state timestamps must agree; database timezone must not shift readings.');
    assert(temperatures.body.data.reading.observedAt <= Date.now() + 1000, 'Temperature timestamp must not be in the future.');
    const historyTo = Date.now();
    const history = await business(page, `/v1/hvac-monitoring/devices/${state.equipmentId}/temperatures?field=roomTemp&from=${historyTo - 86400000}&to=${historyTo}&limit=1000`);
    assert.equal(history.status, 200);
    const historyValues = history.body.data.items.map(item => Number(item.value));
    assert(historyValues.includes(0) && historyValues.includes(36) && historyValues.includes(24.5), 'Real history must preserve zero and 36-degree readings.');
    evidence.temperatureValues = historyValues;
    await page.getByText(`最近有效值: ${temperatures.body.data.reading.value}`, { exact: false }).waitFor();
    await page.locator('.chart-unit').waitFor();
    const unitBox = await page.locator('.chart-unit').boundingBox();
    const chartBox = await page.locator('.detail .chart').boundingBox();
    assert(unitBox && chartBox && unitBox.y + unitBox.height <= chartBox.y, 'Temperature unit must sit outside the plot.');
    await page.locator('.detail .chart svg').waitFor();
    await page.locator('.detail').scrollIntoViewIfNeeded();
    await page.screenshot({ path: path.join(output, '02-temperature-desktop.png'), fullPage: true });
    evidence.checks.push('Equipment search, current state, real temperature and chart');
    await page.setViewportSize({ width: 390, height: 844 });
    await page.locator('.detail').scrollIntoViewIfNeeded();
    await page.screenshot({ path: path.join(output, '03-temperature-mobile.png'), fullPage: true });
    await page.setViewportSize({ width: 1440, height: 1050 });

    await control('runtime');
    await page.getByRole('tab', { name: '厂家运行统计', exact: true }).click();
    await page.getByText('累计运行时长:', { exact: false }).first().waitFor();
    await page.getByText(/^已完成: \d+$/).waitFor();
    assert.equal(await page.getByText(/^(QUEUED|RUNNING|RETRY_WAIT|SUCCEEDED|FAILED|UNSUPPORTED|EXPIRED):/).count(), 0,
      'Runtime synchronization summary must not expose backend enums.');
    await page.screenshot({ path: path.join(output, '04-runtime.png'), fullPage: true });
    const runtime = await business(page, `/v1/hvac-monitoring/devices/${state.equipmentId}/runtime?granularity=DAY`);
    assert.equal(runtime.status, 200);
    assert(runtime.body.data?.items?.length > 0, 'Runtime query must read the persisted statistics.');
    assert.equal(runtime.body.data.items[0].unit, 'minute');
    assert.equal(Number(runtime.body.data.items[0].metrics.totalRuntime), Number(state.expectedRuntimeMinutes));
    evidence.checks.push('Test runtime provider -> production synchronization -> MySQL -> actual runtime page');

    await control('fault');
    await page.goto(`${front}/#/operations/alarms/liveAlarms?buildingId=${state.buildingId}`);
    await page.getByText('设备故障', { exact: true }).waitFor();
    await page.getByRole('cell', { name: new RegExp(state.equipmentName) }).waitFor();
    await page.screenshot({ path: path.join(output, '05-active-exception.png'), fullPage: true });
    await control('recover');
    await page.goto(`${front}/#/operations/alarms/historyAlarms?buildingId=${state.buildingId}`);
    await page.getByText('设备故障', { exact: true }).waitFor();
    await page.screenshot({ path: path.join(output, '06-recovered-exception.png'), fullPage: true });
    const currentExceptions = await business(page, `/v1/hvac-monitoring/buildings/${state.buildingId}/exceptions/current`);
    assert.equal(currentExceptions.status, 200);
    assert(!currentExceptions.body.data.items.some(item => item.type === 'VENDOR_EQUIPMENT'), 'Recovered fault must leave the current exception list.');
    const events = await business(page, `/v1/hvac-monitoring/devices/${state.equipmentId}/state-events`);
    assert.equal(events.status, 200);
    assert(events.body.data.items.length > 0, 'Fault/recovery must persist state changes.');
    evidence.checks.push('Vendor fault persists and appears in current exceptions; recovery appears in history');

    const now = Date.now();
    const tooOld = await business(page, `/v1/hvac-monitoring/devices/${state.equipmentId}/temperatures?field=roomTemp&from=${now - 91 * 86400000}&to=${now}&limit=1000`);
    assert.equal(tooOld.status, 400, '91-day query must be rejected by backend.');
    const anonymous = await fetch(`${api}/v1/hvac-monitoring/devices/${state.equipmentId}/current`);
    assert.equal(anonymous.status, 401);
    const ownerContext = await browser.newContext();
    const owner = await ownerContext.newPage();
    await login(owner, 'daikin_target_owner', settings.ownerPassword);
    const allowed = await business(owner, `/v1/hvac-monitoring/buildings/${state.buildingId}/devices`);
    assert.equal(allowed.status, 200);
    assert(allowed.body.data.items.some(item => item.equipmentId === state.equipmentId));
    const denied = await business(owner, `/v1/hvac-monitoring/buildings/${state.otherBuildingId}/devices`);
    assert.equal(denied.status, 403, 'Cross-building access must be denied.');
    evidence.checks.push('Real owner authentication, building scope, anonymous denial, 90-day window guard');
    assert.equal(evidence.errors.length, 0, evidence.errors.join('\n'));
    evidence.completedAt = new Date().toISOString();
    evidence.result = 'PASS';
    console.log('DAIKIN_REAL_BROWSER_E2E_OK', evidence.checks.length);
  } catch (error) {
    evidence.result = 'FAIL';
    evidence.failure = error.message;
    await page.screenshot({ path: path.join(output, 'failure.png'), fullPage: true }).catch(() => {});
    throw error;
  } finally {
    fs.writeFileSync(path.join(output, 'evidence.json'), JSON.stringify(evidence, null, 2));
    await browser.close();
  }
})().catch(error => { console.error(error.message); process.exitCode = 1; });
