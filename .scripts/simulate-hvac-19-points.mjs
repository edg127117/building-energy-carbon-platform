/**
 * Deterministic HVAC 19-point MQTT publisher.
 *
 * Publishes one sample for every frozen external alias every 10 seconds for
 * 70 seconds. All samples in a round share the same device timestamp.
 *
 * Set HVAC_OMIT_POINT to one known external alias to exercise missing-input
 * handling without editing this file. For a historical correction smoke test,
 * HVAC_ONLY_POINT publishes one point once and can be combined with
 * HVAC_EVENT_TIME_MS and HVAC_VALUE_OVERRIDE.
 */
import mqtt from 'mqtt';

const BROKER_URL =
  process.env.MQTT_BROKER_URL ?? 'tcp://127.0.0.1:1883';
const MQTT_USERNAME = process.env.MQTT_USER ?? 'admin';
const MQTT_PASSWORD = process.env.MQTT_PASSWORD ?? 'change-me';
const OMIT_POINT = process.env.HVAC_OMIT_POINT?.trim();
const ONLY_POINT = process.env.HVAC_ONLY_POINT?.trim();
const EVENT_TIME_TEXT = process.env.HVAC_EVENT_TIME_MS?.trim();
const VALUE_OVERRIDE_TEXT = process.env.HVAC_VALUE_OVERRIDE?.trim();

const TOPIC = 'device/data/up';
const INTERVAL_MS = 10_000;
const DURATION_MS = 70_000;
const TOTAL_ROUNDS = DURATION_MS / INTERVAL_MS;
const CONNECT_TIMEOUT_MS = 10_000;
const PUBLISH_TIMEOUT_MS = 10_000;

const POINTS = [
  ['WCR1', 'WCR1_TWin', 12.0],
  ['WCR1', 'WCR1_TWout', 7.0],
  ['WCR1', 'WCR1_Flow', 100.0],
  ['WCR1', 'WCR1_PPE', 100.0],
  ['WCR1', 'WCR1_Voltage', 380.0],
  ['WCR1', 'WCR1_Current', 100.0],
  ['WCR1', 'WCR1_PF', 0.9],
  ['TOWER1', 'TOWER1_TCWin', 35.0],
  ['TOWER1', 'TOWER1_TCWout', 30.0],
  ['TOWER1', 'TOWER1_TWB', 25.0],
  ['PUMP1', 'PUMP1_Flow', 100.0],
  ['PUMP1', 'PUMP1_Pout', 300000.0],
  ['PUMP1', 'PUMP1_Pin', 100000.0],
  ['PUMP1', 'PUMP1_Z', 1.0],
  ['PUMP1', 'PUMP1_Power', 10.0],
  ['AHU1', 'AHU1_TotalPress', 1000.0],
  ['AHU1', 'AHU1_EtaT', 60.0],
  ['WEATHER_GATEWAY', 'DBO_TDB', 30.0],
  ['WEATHER_GATEWAY', 'DBO_RH', 50.0],
];

function buildPayload(deviceId, pointCode, val, timestamp) {
  return JSON.stringify({
    buildingId: 'BLD001',
    deviceId,
    pointCode,
    val,
    timestamp,
  });
}

function wait(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function parseOptionalNumber(text, name, predicate, expectation) {
  if (text === undefined || text === '') {
    return undefined;
  }
  const value = Number(text);
  if (!predicate(value)) {
    throw new Error(`${name} must be ${expectation}`);
  }
  return value;
}

function requireKnownPoint(pointCode, variableName) {
  if (pointCode && !POINTS.some(([, candidate]) => candidate === pointCode)) {
    throw new Error(
      `Unknown ${variableName} "${pointCode}". Use one of: `
        + POINTS.map(([, candidate]) => candidate).join(', '),
    );
  }
}

function waitForConnection(client) {
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => {
      cleanup();
      reject(new Error(`MQTT connection timed out after ${CONNECT_TIMEOUT_MS}ms`));
    }, CONNECT_TIMEOUT_MS);

    const onConnect = () => {
      cleanup();
      resolve();
    };
    const onError = (error) => {
      cleanup();
      reject(error);
    };
    const cleanup = () => {
      clearTimeout(timeout);
      client.off('connect', onConnect);
      client.off('error', onError);
    };

    client.once('connect', onConnect);
    client.once('error', onError);
  });
}

function publish(client, deviceId, pointCode, val, timestamp) {
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => {
      reject(new Error(
        `MQTT publish timed out for ${pointCode} after ${PUBLISH_TIMEOUT_MS}ms`,
      ));
    }, PUBLISH_TIMEOUT_MS);

    try {
      client.publish(
        TOPIC,
        buildPayload(deviceId, pointCode, val, timestamp),
        { qos: 1, retain: false },
        (error) => {
          clearTimeout(timeout);
          if (error) {
            reject(new Error(`MQTT publish failed for ${pointCode}: ${error.message}`));
            return;
          }
          resolve();
        },
      );
    } catch (error) {
      clearTimeout(timeout);
      reject(error);
    }
  });
}

function close(client) {
  return new Promise((resolve) => {
    const timeout = setTimeout(resolve, 2_000);
    client.end(true, {}, () => {
      clearTimeout(timeout);
      resolve();
    });
  });
}

async function main() {
  requireKnownPoint(OMIT_POINT, 'HVAC_OMIT_POINT');
  requireKnownPoint(ONLY_POINT, 'HVAC_ONLY_POINT');
  if (OMIT_POINT && ONLY_POINT) {
    throw new Error('HVAC_OMIT_POINT and HVAC_ONLY_POINT cannot be used together');
  }
  if (!ONLY_POINT && (EVENT_TIME_TEXT || VALUE_OVERRIDE_TEXT)) {
    throw new Error(
      'HVAC_EVENT_TIME_MS and HVAC_VALUE_OVERRIDE require HVAC_ONLY_POINT',
    );
  }

  const eventTime = parseOptionalNumber(
    EVENT_TIME_TEXT,
    'HVAC_EVENT_TIME_MS',
    (value) => Number.isSafeInteger(value) && value > 0,
    'a positive safe integer Unix timestamp in milliseconds',
  );
  const valueOverride = parseOptionalNumber(
    VALUE_OVERRIDE_TEXT,
    'HVAC_VALUE_OVERRIDE',
    Number.isFinite,
    'a finite number',
  );
  const activePoints = ONLY_POINT
    ? POINTS.filter(([, pointCode]) => pointCode === ONLY_POINT)
    : POINTS.filter(([, pointCode]) => pointCode !== OMIT_POINT);
  const totalRounds = ONLY_POINT ? 1 : TOTAL_ROUNDS;
  let runtimeError;
  let closing = false;
  let connected = false;
  let interrupted = false;

  const client = mqtt.connect(BROKER_URL, {
    username: MQTT_USERNAME,
    password: MQTT_PASSWORD,
    clientId: `hvac-19-point-simulator-${Date.now()}`,
    clean: true,
    reconnectPeriod: 0,
    connectTimeout: CONNECT_TIMEOUT_MS,
  });

  client.on('error', (error) => {
    runtimeError ??= error;
  });
  client.on('close', () => {
    if (connected && !closing) {
      runtimeError ??= new Error('MQTT connection closed unexpectedly');
    }
  });
  process.once('SIGINT', () => {
    interrupted = true;
  });
  process.once('SIGTERM', () => {
    interrupted = true;
  });

  try {
    console.log(`[HVAC模拟器] Broker: ${BROKER_URL}`);
    console.log(
      `[HVAC模拟器] 10秒/轮，共${totalRounds}轮；`
        + `每轮${activePoints.length}个测点`,
    );
    if (OMIT_POINT) {
      console.log(`[HVAC模拟器] 故障验证：本次省略 ${OMIT_POINT}`);
    }
    if (ONLY_POINT) {
      console.log(
        `[HVAC模拟器] 历史定点验证：仅发布 ${ONLY_POINT} 一次`
          + `${eventTime === undefined ? '' : `，timestamp=${eventTime}`}`
          + `${valueOverride === undefined ? '' : `，val=${valueOverride}`}`,
      );
    }

    await waitForConnection(client);
    connected = true;
    console.log('[HVAC模拟器] MQTT 已连接');

    let totalSent = 0;
    for (let round = 1; round <= totalRounds; round += 1) {
      if (interrupted) {
        throw new Error('Publisher interrupted by operating-system signal');
      }
      if (runtimeError || !client.connected) {
        throw runtimeError ?? new Error('MQTT client is not connected');
      }

      const timestamp = eventTime ?? Date.now();
      const outcomes = await Promise.allSettled(
        activePoints.map(([deviceId, pointCode, val]) =>
          publish(
            client,
            deviceId,
            pointCode,
            valueOverride ?? val,
            timestamp,
          )),
      );
      const failed = outcomes.find((outcome) => outcome.status === 'rejected');
      if (failed) {
        throw failed.reason;
      }

      totalSent += activePoints.length;
      console.log(
        `[HVAC模拟器] 第 ${round}/${totalRounds} 轮：`
          + `${activePoints.length} 条，timestamp=${timestamp}`,
      );
      if (ONLY_POINT) {
        break;
      }
      await wait(INTERVAL_MS);
      if (interrupted) {
        throw new Error('Publisher interrupted by operating-system signal');
      }
      if (runtimeError || !client.connected) {
        throw runtimeError ?? new Error('MQTT client is not connected');
      }
    }

    console.log(
      `[HVAC模拟器] 完成：${totalRounds} 轮，共发布 ${totalSent} 条`,
    );
  } finally {
    closing = true;
    await close(client);
  }
}

try {
  await main();
} catch (error) {
  console.error('[HVAC模拟器] 失败：', error?.message ?? error);
  process.exitCode = 1;
}
