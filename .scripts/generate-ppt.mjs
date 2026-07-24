/**
 * 能效碳效智慧管控平台 — 项目汇报 PPT 生成器
 */
import PptxGenJS from 'pptxgenjs'

const pptx = new PptxGenJS()

// ============ 主题：缓和商务风 ============
const C = {
  bg:          'F7F8FA',
  cardBg:      'FFFFFF',
  primary:     '2B5F8A',
  accent:      '3D8B5F',
  danger:      'C0392B',
  text:        '2C3E50',
  muted:       '7F8C8D',
  border:      'DCE1E8',
  highlight:   'E67E22',
  tableStripe: 'F0F4F8',
}

const F = { t1: 24, t2: 20, t3: 16, body: 12, sm: 10, xs: 9 }
const FONT = 'Microsoft YaHei'

// ============ 工具函数 ============
function addSlide(title, cb) {
  const s = pptx.addSlide()
  s.background = { fill: C.bg }
  s.addText(title, {
    x: 0.6, y: 0.3, w: 12, h: 0.55, fontSize: F.t2, fontFace: FONT,
    color: C.primary, bold: true, align: 'left',
  })
  s.addShape(pptx.ShapeType.rect, {
    x: 0.6, y: 0.92, w: 12, h: 0.02, fill: { color: C.primary }, rectRadius: 0.01,
  })
  s.addText(`${s.slideNumber}`, {
    x: 12.3, y: 7.05, w: 0.8, h: 0.35, fontSize: F.sm, fontFace: 'Arial',
    color: C.muted, align: 'right',
  })
  cb(s)
}

function makeCard(s, x, y, w, h, fill = C.cardBg) {
  s.addShape(pptx.ShapeType.roundRect, {
    x, y, w, h, fill: { color: fill }, line: { color: C.border, width: 0.5 }, rectRadius: 0.08,
  })
}

function bullets(s, items, x, y, w, h, opts = {}) {
  const texts = items.map((it) => ({
    text: it.text,
    options: {
      fontSize: opts.fs ?? F.body, fontFace: FONT,
      color: opts.color ?? C.text, bullet: { code: '25CF' },
      indentLevel: it.indent ?? 0, paraSpaceAfter: 5,
    },
  }))
  s.addText(texts, { x, y, w, h, valign: 'top', lineSpacing: 20 })
}

function tbl(s, headers, rows, x, y, w, colW) {
  const hRow = headers.map((h) => ({
    text: h,
    options: {
      fontSize: F.sm, fontFace: FONT, color: C.cardBg, bold: true,
      fill: { color: C.primary }, align: 'center', valign: 'middle',
      border: { color: C.border, pt: 0.5 },
    },
  }))
  const dRows = rows.map((row) =>
    row.map((cell, ci) => ({
      text: String(cell),
      options: {
        fontSize: F.sm, fontFace: FONT, color: C.text,
        fill: { color: ci === 0 ? C.tableStripe : C.bg },
        align: ci === 0 ? 'left' : 'center', valign: 'middle',
        border: { color: C.border, pt: 0.5 },
      },
    })),
  )
  s.addTable([hRow, ...dRows], { x, y, w, border: { color: C.border, pt: 0.5 }, colW })
}

// ============ 封面 ============
{
  const s = pptx.addSlide()
  s.background = { fill: C.bg }
  s.addShape(pptx.ShapeType.rect, { x: 0, y: 0, w: '100%', h: 0.2, fill: { color: C.primary } })
  s.addText('能效碳效智慧管控平台', {
    x: 1, y: 2.0, w: 11, h: 1.2, fontSize: 36, fontFace: FONT,
    color: C.text, bold: true, align: 'center',
  })
  s.addText('项目进展汇报', {
    x: 1, y: 3.2, w: 11, h: 0.8, fontSize: 22, fontFace: FONT,
    color: C.primary, align: 'center',
  })
  s.addShape(pptx.ShapeType.rect, { x: 5.5, y: 4.2, w: 2.3, h: 0.03, fill: { color: C.accent } })
  s.addText([
    { text: 'IoT 平台后端 + Web 大屏前端\n', options: { fontSize: 14, fontFace: FONT, color: C.muted } },
    { text: 'Spring Boot + Vue3 + EMQX + TDengine', options: { fontSize: 12, fontFace: FONT, color: C.muted } },
  ], { x: 1, y: 4.5, w: 11, h: 1.0, align: 'center', lineSpacing: 24 })
  s.addText('数据汇报日期：2026/07/01', {
    x: 1, y: 6.5, w: 11, h: 0.5, fontSize: F.sm, fontFace: FONT,
    color: C.muted, align: 'center',
  })
}

// ============ 目录 ============
addSlide('目录', (s) => {
  makeCard(s, 0.6, 1.5, 12, 5.2)
  bullets(s, [
    { text: '项目概述' },
    { text: '功能清单（七大模块）' },
    { text: '技术架构与选型' },
    { text: '问题解决记录（8 项疑难 Bug）' },
    { text: '测试数据展示' },
    { text: '系统优势（10 项亮点）' },
    { text: '系统局限与优化建议' },
    { text: '文件清单' },
    { text: '下周任务' },
  ], 1.2, 1.8, 11, 4.6, { fs: 16 })
})

// ============ 一、项目概述 ============
addSlide('一、项目概述', (s) => {
  makeCard(s, 0.6, 1.5, 5.8, 5.2)
  bullets(s, [
    { text: '基于 Spring Boot + Vue3 搭建的 IoT 能效碳效智慧管控平台 Demo' },
    { text: '实现硬件 MQTT 接入 → 后端处理 → 前端可视化完整闭环' },
    { text: '设备管理、遥测数据采集、指令下发、大屏实时监控' },
  ], 1.0, 1.8, 5.0, 4.5, { fs: 13 })

  makeCard(s, 6.8, 1.5, 5.8, 5.2)
  bullets(s, [
    { text: '后端：Spring Boot 3.x + JDK 21 虚拟线程' },
    { text: '前端：Vue3 + TS + Ant Design Vue + ECharts + Pinia' },
    { text: '消息中间件：EMQX Broker' },
    { text: '业务数据库：MySQL 8.x（MyBatis-Plus）' },
    { text: '时序数据库：TDengine 3.x（双数据源）' },
    { text: '鉴权：JWT + Spring Security RBAC' },
    { text: '实时推送：WebSocket' },
  ], 7.2, 1.8, 5.0, 4.5, { fs: 13, color: C.accent })
})

// ============ 功能清单 ============
addSlide('二、功能清单 — 用户与权限系统', (s) => {
  makeCard(s, 0.6, 1.5, 5.8, 2.4)
  s.addText('功能点', { x: 0.9, y: 1.6, w: 4, h: 0.35, fontSize: F.body, color: C.primary, bold: true, fontFace: FONT })
  bullets(s, [
    { text: '用户注册：默认 USER 角色，BCrypt 密码加密' },
    { text: '用户登录：JWT 签发（含角色声明），旧密码明文自动升级' },
    { text: 'RBAC 权限骨架：@PreAuthorize 方法级鉴权' },
    { text: 'Token 自动续期：Axios 拦截器自动注入 Bearer Token' },
    { text: '401/403 统一返回 JSON（非 HTML 重定向）' },
  ], 0.9, 2.0, 5.2, 1.6, { fs: 11 })

  makeCard(s, 6.8, 1.5, 5.8, 2.4)
  s.addText('权限分布', { x: 7.1, y: 1.6, w: 4, h: 0.35, fontSize: F.body, color: C.accent, bold: true, fontFace: FONT })
  tbl(s, ['接口', 'ADMIN', 'USER'], [
    ['GET /device/list (设备列表)', '\u2714', '\u2714'],
    ['POST /control/issue (指令下发)', '\u2714', '\u2714'],
    ['POST /device/add (新增设备)', '\u2714', '\u2716'],
    ['DELETE /device/delete/{id} (删除设备)', '\u2714', '\u2716'],
  ], 7.1, 2.0, 5.2, [2.8, 1.2, 1.2])
})

addSlide('二、功能清单 — 设备管理 & 遥测数据', (s) => {
  makeCard(s, 0.6, 1.5, 5.8, 5.2)
  s.addText('设备管理', { x: 0.9, y: 1.6, w: 4, h: 0.35, fontSize: F.body, color: C.primary, bold: true, fontFace: FONT })
  bullets(s, [
    { text: '设备台账 CRUD（设备 ID / 名称 / 类型 / 位置）' },
    { text: '在线状态管理：MySQL status 字段（1=在线 / 0=离线）' },
    { text: '在线设备计数：大屏实时展示在线/离线数量' },
    { text: '设备筛选：按 ID/名称模糊搜索、按状态过滤' },
  ], 0.9, 2.0, 5.2, 4.0, { fs: 12 })

  makeCard(s, 6.8, 1.5, 5.8, 5.2)
  s.addText('遥测数据采集与存储', { x: 7.1, y: 1.6, w: 4.5, h: 0.35, fontSize: F.body, color: C.accent, bold: true, fontFace: FONT })
  bullets(s, [
    { text: 'MQTT 上行：监听 device/data/up 主题' },
    { text: '协议解析：自动识别 property / reply 类型' },
    { text: 'TDengine 存储：电压 / 电流 / 有功功率按子表写入' },
    { text: '批量写入：单次 INSERT INTO 多条数据' },
    { text: '历史查询：按设备 + 时间范围查 TDengine（1~24h）' },
  ], 7.1, 2.0, 5.2, 4.0, { fs: 12 })
})

addSlide('二、功能清单 — 指令下发 & Demo 模拟器', (s) => {
  makeCard(s, 0.6, 1.5, 5.8, 5.2)
  s.addText('控制指令下发', { x: 0.9, y: 1.6, w: 4, h: 0.35, fontSize: F.body, color: C.primary, bold: true, fontFace: FONT })
  bullets(s, [
    { text: '指令创建：落库 MySQL control_commands 表' },
    { text: 'MQTT 下发：device/control/down/{deviceId} 透传' },
    { text: 'ACK 回执：自动更新指令状态（成功/失败）' },
    { text: '超时扫描：@Scheduled 每 10s 扫描，30s 超时标失败' },
    { text: '离线设备处理：仅允许执行"开机"指令' },
    { text: '透明透传：Map<String,Object> 泛型，任意字段直达硬件' },
  ], 0.9, 2.0, 5.2, 4.0, { fs: 12 })

  makeCard(s, 6.8, 1.5, 5.8, 5.2)
  s.addText('Demo 设备模拟器', { x: 7.1, y: 1.6, w: 4, h: 0.35, fontSize: F.body, color: C.accent, bold: true, fontFace: FONT })
  bullets(s, [
    { text: '自动 ACK：收到下行指令 1.5s 后回复回执' },
    { text: '关机模拟：3s 后发送 offline 事件 → 状态变 0' },
    { text: '开机模拟：3s 后上报 property 测点数据 → 状态变 1' },
    { text: '双向模拟：订阅 device/control/down/+ 监听所有设备' },
    { text: '使用 CompletableFuture.delayedExecutor 线程管理' },
  ], 7.1, 2.0, 5.2, 4.0, { fs: 12 })
})

addSlide('二、功能清单 — 大屏监控 & 前端工程', (s) => {
  makeCard(s, 0.6, 1.5, 5.8, 5.2)
  s.addText('大屏实时监控', { x: 0.9, y: 1.6, w: 4, h: 0.35, fontSize: F.body, color: C.primary, bold: true, fontFace: FONT })
  bullets(s, [
    { text: 'WebSocket 实时推送，广播到所有连接的前端' },
    { text: '实时曲线图：电压趋势 / 电流趋势 / 有功功率（ECharts）' },
    { text: '时间范围选择：1/3/6/12/24 小时历史回溯' },
    { text: '实时数据预览：当前设备最新测点数值' },
    { text: '设备告警滚筒：离线时自动推送红色告警' },
    { text: '在线/离线统计：动态刷新计数' },
  ], 0.9, 2.0, 5.2, 4.0, { fs: 12 })

  makeCard(s, 6.8, 1.5, 5.8, 5.2)
  s.addText('前端完整工程', { x: 7.1, y: 1.6, w: 4, h: 0.35, fontSize: F.body, color: C.accent, bold: true, fontFace: FONT })
  bullets(s, [
    { text: '登录/注册页：表单校验 + Token 持久化' },
    { text: '大屏页：WS 推送 + ECharts 曲线 + 时间选择器' },
    { text: '设备台账页：增删查 + 指令抽屉 + ACK 通知' },
    { text: '首页 / 403 页：占位导航 + 权限不足提示' },
    { text: '技术栈：Vue3 + TS + Ant Design Vue + ECharts' },
    { text: '状态管理：Pinia（auth + device 双 Store）' },
    { text: '工具链：Vite + Tailwind CSS' },
  ], 7.1, 2.0, 5.2, 4.0, { fs: 12 })
})

// ============ 三、技术架构 ============
addSlide('三、技术架构', (s) => {
  makeCard(s, 0.6, 1.5, 5.8, 2.2)
  s.addText('数据流', { x: 0.9, y: 1.6, w: 4, h: 0.35, fontSize: F.body, color: C.primary, bold: true, fontFace: FONT })
  s.addText(
    '硬件 → MQTT → EMQX → Paho Client → Event Bus\n' +
    '→ TDengine(时序) + MySQL(业务) + WS(推送)\n' +
    '→ 前端 Dashboard(实时) + Device(管理)',
    { x: 0.9, y: 2.1, w: 5.2, h: 1.2, fontSize: 11, fontFace: 'Consolas', color: C.accent, lineSpacing: 20 },
  )

  makeCard(s, 6.8, 1.5, 5.8, 2.2)
  s.addText('数据库双库设计', { x: 7.1, y: 1.6, w: 4, h: 0.35, fontSize: F.body, color: C.accent, bold: true, fontFace: FONT })
  tbl(s, ['数据库', '职责', '核心对象'], [
    ['MySQL (iot_platform)', '业务数据', 'iot_device / sys_user / control_commands'],
    ['TDengine (iot_telemetry)', '时序遥测', 'st_electric_data \u2192 d_{deviceId}'],
  ], 7.1, 2.1, 5.2, [1.8, 1.5, 1.9])

  makeCard(s, 0.6, 4.0, 12, 3.0)
  s.addText('关键技术选型', { x: 0.9, y: 4.1, w: 4, h: 0.35, fontSize: F.body, color: C.primary, bold: true, fontFace: FONT })
  tbl(s, ['层级', '技术', '用途'], [
    ['后端', 'Spring Boot 3.x + JDK 21', '应用框架'],
    ['后端', 'Spring Security + jjwt', '认证鉴权'],
    ['后端', 'Eclipse Paho + EMQX', 'MQTT 消息'],
    ['后端', 'MyBatis-Plus + JdbcTemplate', '双数据源'],
    ['后端', 'WebSocket (Tomcat)', '大屏实时推送'],
    ['前端', 'Vue3 + Vite + TypeScript', 'SPA 框架'],
    ['前端', 'AntDV + ECharts + Tailwind', 'UI + 图表'],
    ['前端', 'Pinia', '状态管理'],
  ], 0.9, 4.5, 11.4, [1.2, 5.0, 5.2])
})

// ============ 四、问题解决记录 ============
addSlide('四、问题解决记录（8 项）', (s) => {
  makeCard(s, 0.6, 1.5, 12, 5.5)
  tbl(s, ['#', '问题', '根因', '解决方案'], [
    ['1', 'WS 并发 TEXT_FULL_WRITING', '多虚拟线程并发 getAsyncRemote()', 'synchronized + getBasicRemote()'],
    ['2', '前端过多 ACK 弹窗', 'Dashboard 和 Device 都处理 reply', '统一由 DevicePage 处理'],
    ['3', '按钮持续转圈', 'new Thread() 丢失执行', 'CompletableFuture.delayedExecutor'],
    ['4', '关机后状态不更新', 'ACK 只更新指令状态', '额外发送 property/offline 事件'],
    ['5', '离线设备无法点"开启"', '后端无条件拒绝离线指令', 'isPowerOnCommand 白名单'],
    ['6', 'JWT 角色大小写不匹配', 'Token 中 admin vs hasRole(ADMIN)', 'r.toUpperCase() 强转大写'],
    ['7', '数据库中文乱码', 'PS Invoke-RestMethod 编码', 'UTF-8 字节数组 + JDBC charset'],
    ['8', 'TDengine 写入 NULL', 'MQTT 嵌套 data 对象', '改为 flat JSON 结构'],
  ], 0.9, 1.7, 11.4, [0.4, 3.0, 3.6, 4.4])
})

// ============ 五、测试数据 ============
addSlide('五、测试数据展示 — 设备清单（10 台）', (s) => {
  makeCard(s, 0.6, 1.5, 12, 5.5)
  s.addText('10 台设备清单', { x: 0.9, y: 1.6, w: 4, h: 0.35, fontSize: F.body, color: C.primary, bold: true, fontFace: FONT })
  tbl(s, ['设备ID', '名称', '电压', '电流范围', '功率范围'], [
    ['meter-001', '1号注塑机电表', '220V', '12.5\u00b10.6A', '2.75\u00b10.14kW'],
    ['meter-002', '2号冲压机电表', '220V', '18.3\u00b10.9A', '4.06\u00b10.20kW'],
    ['meter-003', '3号CNC电表', '220V', '25.0\u00b11.2A', '5.45\u00b10.27kW'],
    ['comp-001', '空压站1号功率计', '380V', '35.0\u00b11.8A', '23.1\u00b11.2kW'],
    ['comp-002', '空压站2号功率计', '380V', '32.5\u00b11.6A', '21.5\u00b11.1kW'],
    ['chiller-001', '冷冻机组1号', '380V', '28.0\u00b11.4A', '18.5\u00b10.9kW'],
    ['meter-004', '4号冲压机电表', '220V', '15.0A', '3.3kW'],
    ['meter-005', '5号装配线电表', '220V', '8.0A', '1.8kW'],
    ['solar-001', '光伏逆变器1号', '380V', '20.0A', '7.6kW'],
    ['boiler-001', '锅炉房总表', '380V', '45.0A', '17.1kW'],
  ], 0.9, 2.0, 11.4, [1.3, 2.5, 0.8, 1.8, 1.8])
})

addSlide('五、测试数据展示 — 压力测试（36 条）', (s) => {
  makeCard(s, 0.6, 1.5, 5.8, 5.2)
  s.addText('压测参数', { x: 0.9, y: 1.6, w: 4, h: 0.35, fontSize: F.body, color: C.primary, bold: true, fontFace: FONT })
  bullets(s, [
    { text: '设备数量：6 台在线设备' },
    { text: '上报间隔：10 秒 / 轮' },
    { text: '持续时间：60 秒（共 6 轮）' },
    { text: '单次数据：电压 + 电流 + 功率（\u00b15% 波动）' },
    { text: '发送总量：36 条 MQTT 消息' },
    { text: '存储结果：36 条全部写入 TDengine' },
    { text: '推送结果：WebSocket 实时到达前端' },
    { text: '数据质量：零空值，全部有效' },
  ], 0.9, 2.0, 5.2, 4.0, { fs: 12 })

  makeCard(s, 6.8, 1.5, 5.8, 5.2)
  s.addText('每设备写入结果', { x: 7.1, y: 1.6, w: 4, h: 0.35, fontSize: F.body, color: C.accent, bold: true, fontFace: FONT })
  tbl(s, ['设备', '记录数', '质量'], [
    ['meter-001', '6 条', '\u2714'],
    ['meter-002', '6 条', '\u2714'],
    ['meter-003', '6 条', '\u2714'],
    ['comp-001', '6 条', '\u2714'],
    ['comp-002', '6 条', '\u2714'],
    ['chiller-001', '6 条', '\u2714'],
    ['合计', '36 条', '\u2714 100%'],
  ], 7.1, 2.0, 5.2, [2.4, 1.4, 1.4])
})

// ============ 六、系统优势 ============
addSlide('六、系统优势（10 项亮点）', (s) => {
  makeCard(s, 0.6, 1.5, 5.8, 5.2)
  bullets(s, [
    { text: '全链路打通：MQTT → 后端 → TDengine/MySQL → WS → 大屏' },
    { text: '透明网关设计：指令 params 使用 Map<String,Object> 泛型' },
    { text: '事件驱动架构：Spring Event Bus + @Async + @EventListener' },
    { text: '时序/业务分离：TDengine 存遥测，MySQL 存台账' },
    { text: 'EMQX 心跳自动判离线：Keep-Alive 1.5x 超时即告警' },
  ], 0.9, 1.8, 5.2, 4.5, { fs: 12 })

  makeCard(s, 6.8, 1.5, 5.8, 5.2)
  bullets(s, [
    { text: '策略模式：TimeSeriesRepository 接口，换库只换实现' },
    { text: '无状态鉴权：JWT + @PreAuthorize，前后端分离易扩展' },
    { text: '超时兜底：指令 30s 无 ACK → 标失败 → WS 通知' },
    { text: '并发安全：synchronized + getBasicRemote()' },
    { text: '前端组件化：GlassCard / ChartPanel 等可复用组件' },
  ], 7.1, 1.8, 5.2, 4.5, { fs: 12, color: C.accent })
})

// ============ 七、局限与优化 ============
addSlide('七、局限与优化建议', (s) => {
  makeCard(s, 0.6, 1.5, 5.8, 5.2)
  s.addText('当前局限（8 项）', { x: 0.9, y: 1.6, w: 4, h: 0.35, fontSize: F.body, color: C.danger, bold: true, fontFace: FONT })
  bullets(s, [
    { text: '无真实硬件接入（Demo 全靠模拟器）' },
    { text: '缺少设备认证（MQTT 无白名单/IP 限制）' },
    { text: '无数据脱敏/加密（遥测明文传输）' },
    { text: '缺少告警规则引擎（仅离线一种告警）' },
    { text: '前端无单元/E2E 测试' },
    { text: '时间范围降采样未实现' },
    { text: '无 API 文档（Swagger/OpenAPI）' },
    { text: '无 Docker/K8s 编排' },
  ], 0.9, 2.0, 5.2, 4.0, { fs: 11 })

  makeCard(s, 6.8, 1.5, 5.8, 5.2)
  s.addText('优化建议（按优先级）', { x: 7.1, y: 1.6, w: 4, h: 0.35, fontSize: F.body, color: C.highlight, bold: true, fontFace: FONT })
  bullets(s, [
    { text: '高 \u00b7 Swagger/OpenAPI 自动文档' },
    { text: '高 \u00b7 Docker Compose 一键部署' },
    { text: '高 \u00b7 设备 MQTT 连接认证（TLS/证书）' },
    { text: '中 \u00b7 告警规则引擎（功率异常/电流越限）' },
    { text: '中 \u00b7 服务端数据降采样' },
    { text: '中 \u00b7 指令重试队列' },
    { text: '中 \u00b7 历史数据对比分析' },
    { text: '低 \u00b7 数据导出（Excel/CSV）' },
    { text: '低 \u00b7 CI/CD Pipeline' },
    { text: '留白 \u00b7 MPC 算法对接（预留桩）' },
  ], 7.1, 2.0, 5.2, 4.0, { fs: 11 })
})

// ============ 八、文件清单 ============
addSlide('八、文件清单 — 后端核心（30+ 文件）', (s) => {
  makeCard(s, 0.6, 1.5, 12, 5.5)
  s.addText(
    'src/main/java/com/platform/\n\n' +
    '\u251c\u2500\u2500 security/   JWT 鉴权过滤器 + 安全配置（6 文件）\n' +
    '\u251c\u2500\u2500 system/    用户注册/登录 + 角色管理（8 文件）\n' +
    '\u251c\u2500\u2500 iot/\n' +
    '\u2502   \u251c\u2500\u2500 controller/     设备 CRUD + 指令下发 + 历史查询 \u2605\n' +
    '\u2502   \u251c\u2500\u2500 core/handler/   事件消费（TDengine/MySQL/WS）\n' +
    '\u2502   \u251c\u2500\u2500 temporal/       时序存储接口 + TDengine 实现 \u2605\n' +
    '\u2502   \u251c\u2500\u2500 service/impl/   业务逻辑 + 超时扫描\n' +
    '\u2502   \u251c\u2500\u2500 mqtt/           MQTT 发布器\n' +
    '\u2502   \u251c\u2500\u2500 websocket/      WebSocket 服务端\n' +
    '\u2502   \u2514\u2500\u2500 algorithm/      MPC 算法预留桩\n' +
    '\u251c\u2500\u2500 config/    MQTT 客户端 + Demo 模拟器 + TDengine 配置\n' +
    '\u2514\u2500\u2500 framework/ 统一返回 + 全局异常处理\n\n' +
    '\u2605 = 本周新增',
    { x: 0.9, y: 1.7, w: 11.2, h: 5.0, fontSize: 11, fontFace: 'Consolas', color: C.text, lineSpacing: 17 }
  )
})

addSlide('八、文件清单 — 前端核心（20+ 文件）', (s) => {
  makeCard(s, 0.6, 1.5, 12, 5.5)
  s.addText(
    'web/src/\n\n' +
    '\u251c\u2500\u2500 api/          auth.ts / device.ts \u2605 / control.ts\n' +
    '\u251c\u2500\u2500 store/        auth.ts (鉴权) + device.ts (WS数据)\n' +
    '\u251c\u2500\u2500 pages/\n' +
    '\u2502   \u251c\u2500\u2500 DashboardPage.vue   \u2605时间选择器 + 历史合并\n' +
    '\u2502   \u251c\u2500\u2500 DevicePage.vue      设备台账 + 指令抽屉\n' +
    '\u2502   \u251c\u2500\u2500 LoginPage.vue       登录注册\n' +
    '\u2502   \u2514\u2500\u2500 HomePage / ForbiddenPage\n' +
    '\u251c\u2500\u2500 components/   ChartPanel \u2605 / GlassCard / MetricFlipper\n' +
    '\u2502                 StatusBadge / Empty\n' +
    '\u251c\u2500\u2500 utils/        request.ts (Axios) + websocket.ts\n' +
    '\u251c\u2500\u2500 types/        api.ts + ws.ts\n' +
    '\u251c\u2500\u2500 router/       路由 + 权限守卫\n' +
    '\u2514\u2500\u2500 layouts/      AdminLayout + ScreenLayout\n\n' +
    '\u2605 = 本周新增',
    { x: 0.9, y: 1.7, w: 11.2, h: 5.0, fontSize: 11, fontFace: 'Consolas', color: C.accent, lineSpacing: 17 }
  )
})

// ============ 九、下周任务 ============
addSlide('九、下周任务', (s) => {
  // 任务一
  makeCard(s, 0.6, 1.5, 12, 2.8, 'EBF5ED')
  s.addShape(pptx.ShapeType.roundRect, { x: 0.65, y: 1.55, w: 0.06, h: 2.7, fill: { color: C.accent } })
  s.addText('任务一', {
    x: 1.0, y: 1.7, w: 2, h: 0.4, fontSize: 16, fontFace: FONT, color: C.accent, bold: true,
  })
  s.addText('将服务器内的数据库及其组件安装成功，实现云端传输到本地服务器中', {
    x: 1.0, y: 2.15, w: 11, h: 0.5, fontSize: 14, fontFace: FONT, color: C.text,
  })
  bullets(s, [
    { text: '安装并配置 MySQL 8.x / TDengine 3.x / EMQX 至目标服务器' },
    { text: '打通云端开发环境 \u2192 本地服务器的网络与数据传输通道' },
    { text: '迁移现有 Demo 数据至本地服务器，验证全链路连通性' },
  ], 1.2, 2.7, 10.5, 1.4, { fs: 12, color: C.muted })

  // 任务二
  makeCard(s, 0.6, 4.5, 12, 2.6, 'FEF9F0')
  s.addShape(pptx.ShapeType.roundRect, { x: 0.65, y: 4.55, w: 0.06, h: 2.5, fill: { color: C.highlight } })
  s.addText('任务二', {
    x: 1.0, y: 4.65, w: 3, h: 0.4, fontSize: 16, fontFace: FONT, color: C.highlight, bold: true,
  })
  s.addText('根据数据库业务建模方案，完成第一版数据库设计方案', {
    x: 1.0, y: 5.1, w: 11, h: 0.5, fontSize: 14, fontFace: FONT, color: C.text,
  })
  bullets(s, [
    { text: '梳理业务实体（设备 / 用户 / 角色 / 指令 / 遥测 / 告警）与关系' },
    { text: '设计 MySQL 表结构：字段类型、索引、外键约束、分库分表策略' },
    { text: '设计 TDengine 超级表 Schema：标签字段、测点字段、降采样策略' },
    { text: '输出 ER 图 + 数据库设计文档 V1.0' },
  ], 1.2, 5.6, 10.5, 1.2, { fs: 12, color: C.muted })
})

// ============ 尾页 ============
{
  const s = pptx.addSlide()
  s.background = { fill: C.bg }
  s.addShape(pptx.ShapeType.rect, { x: 0, y: 0, w: '100%', h: 0.2, fill: { color: C.primary } })
  s.addText('谢谢！', {
    x: 1, y: 2.5, w: 11, h: 1.5, fontSize: 48, fontFace: FONT,
    color: C.text, bold: true, align: 'center',
  })
  s.addText('能效碳效智慧管控平台 — 项目汇报', {
    x: 1, y: 4.2, w: 11, h: 0.8, fontSize: 18, fontFace: FONT,
    color: C.primary, align: 'center',
  })
  s.addShape(pptx.ShapeType.rect, { x: 5.5, y: 5.2, w: 2.3, h: 0.03, fill: { color: C.accent } })
  s.addText('2026/07/01', {
    x: 1, y: 5.5, w: 11, h: 0.5, fontSize: F.body, fontFace: FONT,
    color: C.muted, align: 'center',
  })
}

// ============ 保存 ============
const outputPath = 'd:/word/iot-platform-demo/.trae/documents/能效碳效智慧管控平台-项目汇报-v2.pptx'
await pptx.writeFile({ fileName: outputPath })
console.log(`PPT 已生成: ${outputPath}`)
console.log(`共 ${pptx.slides.length} 页`)
