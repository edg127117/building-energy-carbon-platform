/**
 * IoT 设备数据模拟器
 * 模拟 6 台在线设备每 10 秒上报一次测点数据，持续 1 分钟（共 6 轮）
 * 数据通过 MQTT 发送到 device/data/up 主题
 */
import mqtt from 'mqtt';

// ========== 配置 ==========
const BROKER_URL = 'tcp://127.0.0.1:1883';
const MQTT_USERNAME = process.env.MQTT_USER ?? 'admin';
const MQTT_PASSWORD = process.env.MQTT_PASSWORD ?? 'change-me';
const TOPIC = 'device/data/up';
const INTERVAL_MS = 10000;    // 每 10 秒一轮
const DURATION_MS = 60000;     // 持续 1 分钟
const TOTAL_ROUNDS = DURATION_MS / INTERVAL_MS; // 6 轮

// 6 台在线设备（来自 MySQL 查询结果）
const DEVICES = [
  { deviceId: 'meter-001', deviceName: '1号注塑机电表', baseVoltage: 220, baseCurrent: 12.5, basePower: 2.75 },
  { deviceId: 'meter-002', deviceName: '2号冲压机电表', baseVoltage: 222, baseCurrent: 18.3, basePower: 4.06 },
  { deviceId: 'meter-003', deviceName: '3号CNC电表',     baseVoltage: 218, baseCurrent: 25.0, basePower: 5.45 },
  { deviceId: 'comp-001',  deviceName: '空压站1号功率计', baseVoltage: 380, baseCurrent: 35.0, basePower: 23.1 },
  { deviceId: 'comp-002',  deviceName: '空压站2号功率计', baseVoltage: 380, baseCurrent: 32.5, basePower: 21.5 },
  { deviceId: 'chiller-001',deviceName: '冷冻机组1号',    baseVoltage: 380, baseCurrent: 28.0, basePower: 18.5 },
];

// ========== 辅助函数 ==========
/** 生成带 ±5% 随机波动的模拟值 */
function simulateValue(base, variance = 0.05) {
  const factor = 1 + (Math.random() * 2 - 1) * variance;
  return Math.round(base * factor * 100) / 100;
}

/** 构建上报报文（flat 结构，字段与后端 TDengine 写入对齐） */
function buildPayload(device) {
  return JSON.stringify({
    deviceId: device.deviceId,
    timestamp: Date.now(),
    voltage_a: simulateValue(device.baseVoltage),
    current_a: simulateValue(device.baseCurrent),
    active_power: simulateValue(device.basePower),
  });
}

// ========== 主流程 ==========
console.log(`[模拟器] 启动 MQTT 设备数据模拟器`);
console.log(`[模拟器] 目标设备: ${DEVICES.map(d => d.deviceId).join(', ')}`);
console.log(`[模拟器] 上报间隔: ${INTERVAL_MS / 1000}s, 持续: ${DURATION_MS / 1000}s, 共 ${TOTAL_ROUNDS} 轮`);
console.log('');

const client = mqtt.connect(BROKER_URL, {
  username: MQTT_USERNAME,
  password: MQTT_PASSWORD,
  clientId: `simulator-${Date.now()}`,
  clean: true,
});

client.on('connect', () => {
  console.log('[模拟器] ✓ MQTT 已连接\n');

  let round = 0;
  let totalSent = 0;

  // 立即发送第一轮
  sendRound();

  const timer = setInterval(() => {
    round++;
    if (round >= TOTAL_ROUNDS) {
      clearInterval(timer);
      console.log(`\n[模拟器] ==== 完成! 共发送 ${totalSent} 条数据 (${TOTAL_ROUNDS} 轮 × ${DEVICES.length} 台) ====`);
      client.end();
      process.exit(0);
      return;
    }
    sendRound();
  }, INTERVAL_MS);

  function sendRound() {
    const roundLabel = round + 1;
    const timestamp = new Date().toLocaleTimeString('zh-CN', { hour12: false });
    process.stdout.write(`[${timestamp}] 第 ${roundLabel}/${TOTAL_ROUNDS} 轮: `);

    const promises = DEVICES.map((device) => {
      return new Promise((resolve) => {
        const payload = buildPayload(device);
        client.publish(TOPIC, payload, { qos: 1 }, (err) => {
          if (err) {
            console.error(`  ✗ ${device.deviceId}: ${err.message}`);
          }
          resolve();
        });
        totalSent++;
      });
    });

    Promise.all(promises).then(() => {
      console.log(`已发送 ${DEVICES.length} 条`);
    });
  }
});

client.on('error', (err) => {
  console.error('[模拟器] MQTT 错误:', err.message);
  process.exit(1);
});
