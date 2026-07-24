import mqtt from 'mqtt';

const TOTAL = 10000;
const ROUNDS = 10;
const BROKER = 'tcp://127.0.0.1:1883';

function deviceId(i) {
  return 'meter-' + String(i).padStart(4, '0');
}

const clients = [];
console.log(`正在连接 ${TOTAL} 台设备...`);

for (let i = 1; i <= TOTAL; i++) {
  const id = deviceId(i);
  const client = mqtt.connect(BROKER, {
    username: process.env.MQTT_USER ?? 'admin',
    password: process.env.MQTT_PASSWORD ?? 'change-me',
    clientId: id, clean: true
  });
  clients.push({ client, id });
}

await Promise.all(
  clients.map(c => new Promise(r => c.client.on('connect', () => r())))
);
console.log(`${TOTAL} 台设备全部连接完成`);

function waitUntilNextMinute59() {
  const now = new Date();
  const target = new Date(now);
  target.setSeconds(59, 0); // 设为当前分钟的第59秒000毫秒
  if (target <= now) {
    target.setMinutes(target.getMinutes() + 1); // 如果59秒已过，推到下一分钟
  }
  const ms = target - now;
  const waitSec = (ms / 1000).toFixed(0);
  if (ms > 5000) {
    console.log(`  等待 ${waitSec} 秒，目标: ${target.toLocaleTimeString()}`);
  }
  return new Promise(r => setTimeout(r, ms));
}

for (let round = 1; round <= ROUNDS; round++) {
  await waitUntilNextMinute59();
  const start = Date.now();

  const promises = clients.map(({ client, id }) =>
    new Promise(res => {
      client.publish('device/data/up', JSON.stringify({
        deviceId: id,
        timestamp: Date.now(),
        voltage_a: 220 + Math.random() * 5,
        current_a: 10 + Math.random() * 3,
        active_power: 2.2 + Math.random() * 0.5
      }), { qos: 1 }, res);
    })
  );

  await Promise.all(promises);
  console.log(`第 ${round}/${ROUNDS} 轮: ${TOTAL} 条, 耗时 ${Date.now() - start}ms`);
}

console.log('压测结束');
clients.forEach(c => c.client.end());
