import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const REQUIRED_INDICATOR_CODES = new Set([
  'WCR_COP',
  'TOWER_EFF',
  'PUMP_EFF',
  'AHU_POW_EFF',
]);
const TIMEOUT_MS = 120_000;

function requiredEnvironment(name) {
  const value = process.env[name]?.trim();
  if (!value) throw new Error(`missing required environment ${name}`);
  return value;
}

function stage(name) {
  console.log(`PASS ${name}`);
}

function sanitizedError(error) {
  const text = error instanceof Error ? error.message : String(error);
  return text.replace(/[A-Za-z0-9_-]{24,}/g, '[redacted]');
}

function withTimeout(promise, label, timeoutMs = TIMEOUT_MS) {
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(
      () => reject(new Error(`${label} timed out`)),
      timeoutMs,
    );
    promise.then(
      (value) => {
        clearTimeout(timeout);
        resolve(value);
      },
      (error) => {
        clearTimeout(timeout);
        reject(error);
      },
    );
  });
}

function openSubscription(url, token, buildingId) {
  const messages = [];
  const waiters = [];
  const socket = new WebSocket(url);
  let heartbeat;

  socket.addEventListener('message', (event) => {
    let message;
    try {
      message = JSON.parse(String(event.data));
    } catch {
      return;
    }
    messages.push(message);
    for (const waiter of [...waiters]) waiter(message);
    if (message.type === 'SUBSCRIBED' && heartbeat === undefined) {
      heartbeat = setInterval(() => {
        if (socket.readyState === WebSocket.OPEN) {
          socket.send(JSON.stringify({ type: 'PING' }));
        }
      }, 15_000);
    }
  });
  socket.addEventListener('close', () => clearInterval(heartbeat), {
    once: true,
  });

  const subscribed = withTimeout(new Promise((resolve, reject) => {
    socket.addEventListener('open', () => {
      socket.send(JSON.stringify({ type: 'SUBSCRIBE', token, buildingId }));
    }, { once: true });
    socket.addEventListener('error', () => {
      reject(new Error('websocket transport error'));
    }, { once: true });
    socket.addEventListener('close', (event) => {
      reject(new Error(`websocket closed before subscription (${event.code})`));
    }, { once: true });
    waiters.push((message) => {
      if (message.type === 'SUBSCRIBED' && message.buildingId === buildingId) {
        resolve(message);
      }
    });
  }), `subscribe ${buildingId}`, 10_000);

  function waitFor(predicate, label, timeoutMs = TIMEOUT_MS) {
    const existing = messages.find(predicate);
    if (existing) return Promise.resolve(existing);
    return withTimeout(new Promise((resolve) => {
      waiters.push((message) => {
        if (predicate(message)) resolve(message);
      });
    }), label, timeoutMs);
  }

  return { socket, messages, subscribed, waitFor };
}

function expectRejectedBuilding(url, token, buildingId) {
  return withTimeout(new Promise((resolve, reject) => {
    const socket = new WebSocket(url);
    socket.addEventListener('open', () => {
      socket.send(JSON.stringify({ type: 'SUBSCRIBE', token, buildingId }));
    }, { once: true });
    socket.addEventListener('error', () => {}, { once: true });
    socket.addEventListener('close', (event) => {
      if (event.code === 4403) resolve();
      else reject(new Error(`expected close 4403, received ${event.code}`));
    }, { once: true });
  }), 'forbidden building close', 10_000);
}

function runSimulator() {
  const currentFile = fileURLToPath(import.meta.url);
  const simulator = path.join(
    path.dirname(currentFile),
    'simulate-hvac-19-points.mjs',
  );
  return withTimeout(new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [simulator], {
      cwd: path.dirname(path.dirname(currentFile)),
      env: process.env,
      stdio: 'ignore',
    });
    child.once('error', reject);
    child.once('exit', (code) => {
      if (code === 0) resolve();
      else reject(new Error(`HVAC simulator exited with code ${code}`));
    });
  }), 'HVAC simulator', TIMEOUT_MS);
}

async function readLatest(apiBase, token, buildingId, expected) {
  const deadline = Date.now() + 30_000;
  while (Date.now() < deadline) {
    const response = await fetch(
      `${apiBase.replace(/\/$/, '')}/hvac/buildings/`
        + `${encodeURIComponent(buildingId)}/indicators/latest`,
      {
        headers: { Authorization: `Bearer ${token}` },
        signal: AbortSignal.timeout(10_000),
      },
    );
    if (!response.ok) throw new Error(`latest HTTP returned ${response.status}`);
    const payload = await response.json();
    const indicator = payload?.data?.indicators?.find(
      (candidate) => candidate.indicatorCode === expected.indicatorCode,
    );
    if (payload?.data?.buildingId === buildingId
        && indicator?.minuteStart === expected.minuteStart
        && indicator?.status === expected.status) {
      return indicator;
    }
    await new Promise((resolve) => setTimeout(resolve, 1_000));
  }
  throw new Error('HTTP latest state did not reconcile with realtime event');
}

async function main() {
  if (typeof fetch !== 'function' || typeof WebSocket !== 'function') {
    throw new Error('Node runtime requires global fetch and WebSocket');
  }
  const adminToken = requiredEnvironment('HVAC_RT_ADMIN_TOKEN');
  const restrictedToken = requiredEnvironment('HVAC_RT_RESTRICTED_TOKEN');
  const apiBase = requiredEnvironment('HVAC_RT_API_BASE');
  const wsUrl = requiredEnvironment('HVAC_RT_WS_URL');
  const secondBuilding = requiredEnvironment('HVAC_RT_SECOND_BUILDING_ID');

  const admin = openSubscription(wsUrl, adminToken, 'BLD001');
  const restricted = openSubscription(
    wsUrl, restrictedToken, secondBuilding,
  );
  await Promise.all([admin.subscribed, restricted.subscribed]);
  stage('authorized building subscriptions');

  await expectRejectedBuilding(wsUrl, restrictedToken, 'BLD001');
  stage('forbidden building rejected');

  const indicatorPromise = admin.waitFor(
    (message) => message.type === 'HVAC_INDICATOR'
      && message.data?.buildingId === 'BLD001'
      && REQUIRED_INDICATOR_CODES.has(message.data?.indicatorCode),
    'BLD001 indicator delivery',
  );
  const simulatorPromise = runSimulator();
  const indicatorMessage = await indicatorPromise;
  await new Promise((resolve) => setTimeout(resolve, 2_000));
  if (restricted.messages.some(
    (message) => message.type === 'HVAC_INDICATOR'
      && message.data?.buildingId === 'BLD001',
  )) {
    throw new Error('cross-building indicator was delivered');
  }
  stage('building-scoped indicator delivery');

  await readLatest(apiBase, adminToken, 'BLD001', indicatorMessage.data);
  stage('HTTP authoritative reconciliation');

  admin.socket.close(1000, 'smoke reconnect');
  const reconnected = openSubscription(wsUrl, adminToken, 'BLD001');
  await reconnected.subscribed;
  await readLatest(apiBase, adminToken, 'BLD001', indicatorMessage.data);
  stage('reconnect and HTTP reconciliation');

  reconnected.socket.close(1000, 'smoke complete');
  restricted.socket.close(1000, 'smoke complete');
  await simulatorPromise;
}

main().catch((error) => {
  console.error(`FAIL ${sanitizedError(error)}`);
  process.exitCode = 1;
});
