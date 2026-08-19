/**
 * Publish one deterministic standard onboarding packet for local smoke tests.
 *
 * MQTT QoS 1 completion only proves that the local broker accepted the packet.
 * The PowerShell acceptance script separately checks MySQL and TDengine facts.
 */
import mqtt from 'mqtt';

const brokerUrl = process.env.MQTT_BROKER_URL ?? 'tcp://127.0.0.1:11883';
const username = process.env.MQTT_USER ?? 'admin';
const password = process.env.MQTT_PASSWORD ?? 'change-me';
const identity = process.env.ONBOARDING_IDENTITY ?? 'E3A-SYNTHETIC-MAC-001';
const profileCode = process.env.ONBOARDING_PROFILE_CODE ?? 'HVAC_DEVICE_V1';
const mode = process.env.ONBOARDING_MESSAGE_MODE ?? 'valid';
const eventTime = parsePositiveInteger(
  process.env.ONBOARDING_EVENT_TIME_MS ?? `${Date.now()}`,
  'ONBOARDING_EVENT_TIME_MS',
);
const sequence = parsePositiveInteger(
  process.env.ONBOARDING_SEQUENCE ?? '1',
  'ONBOARDING_SEQUENCE',
);
const value = parseFiniteNumber(
  process.env.ONBOARDING_VALUE ?? '20.5',
  'ONBOARDING_VALUE',
);
const unit = process.env.ONBOARDING_UNIT ?? '℃';
const topic = 'device/telemetry/up';

function parsePositiveInteger(text, name) {
  const value = Number(text);
  if (!Number.isSafeInteger(value) || value <= 0) {
    throw new Error(`${name} must be a positive safe integer`);
  }
  return value;
}

function parseFiniteNumber(text, name) {
  const value = Number(text);
  if (!Number.isFinite(value)) {
    throw new Error(`${name} must be finite`);
  }
  return value;
}

function buildPayload() {
  const payload = {
    standardVersion: '1.0',
    profileCode,
    profileVersion: 1,
    deviceIdentity: {
      type: 'MAC',
      value: identity,
    },
    eventTime,
    receivedTime: eventTime,
    timeSource: 'DEVICE_REPORTED',
    seq: sequence,
    metrics: [{
      code: 'temperature',
      value,
      unit,
      sourceField: '/temperature',
    }],
  };
  if (mode === 'missing-metrics') {
    delete payload.metrics;
  } else if (mode !== 'valid') {
    throw new Error(`Unsupported ONBOARDING_MESSAGE_MODE: ${mode}`);
  }
  return JSON.stringify(payload);
}

function connect(client) {
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(
      () => reject(new Error('MQTT connection timed out after 10000ms')),
      10_000,
    );
    client.once('connect', () => {
      clearTimeout(timeout);
      resolve();
    });
    client.once('error', (error) => {
      clearTimeout(timeout);
      reject(error);
    });
  });
}

function publish(client, payload) {
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(
      () => reject(new Error('MQTT publish timed out after 10000ms')),
      10_000,
    );
    client.publish(topic, payload, { qos: 1, retain: false }, (error) => {
      clearTimeout(timeout);
      if (error) {
        reject(error);
        return;
      }
      resolve();
    });
  });
}

function close(client) {
  return new Promise((resolve) => client.end(true, {}, resolve));
}

async function main() {
  const client = mqtt.connect(brokerUrl, {
    username,
    password,
    clientId: `onboarding-e3a-${process.pid}-${Date.now()}`,
    clean: true,
    reconnectPeriod: 0,
    connectTimeout: 10_000,
  });
  try {
    await connect(client);
    await publish(client, buildPayload());
    console.log(
      `[E3A合成设备] 已发布 mode=${mode} identity=${identity}`
        + ` profile=${profileCode} eventTime=${eventTime} seq=${sequence}`,
    );
  } finally {
    await close(client);
  }
}

await main();
