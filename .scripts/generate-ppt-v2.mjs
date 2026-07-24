/**
 * 建筑能效与智控核心业务梳理 — 本周工作汇报 PPT（完整版）
 */
import PptxGenJS from 'pptxgenjs'

const pptx = new PptxGenJS()
pptx.layout = 'LAYOUT_WIDE'

// ============ 主题配色 ============
const C = {
  bg:          'F7F8FA',
  cardBg:      'FFFFFF',
  primary:     '2B5F8A',
  accent:      '3D8B5F',
  text:        '2C3E50',
  muted:       '7F8C8D',
  border:      'DCE1E8',
  highlight:   'E67E22',
  purple:      '8E44AD',
  red:         'C0392B',
  greenBg:     'EBF5ED',
  blueBg:      'EBF0F7',
  orangeBg:    'FFF3E5',
  grayBg:      'F8F9FD',
  // 饼图色
  pieBlue:     '2980B9',
  pieGreen:    '27AE60',
  pieOrange:   'E67E22',
  pieGray:     '95A5A6',
}
const FONT = 'Microsoft YaHei'
const SW = 13.33
const SH = 7.5

// ============ 框架常量 ============
const TITLE_TOP = 0.3, TITLE_H = 0.4, DIVIDER_Y = 1.0
const CT = 1.3, CB = 7.1, CH = CB - CT

function addSlide(title, subtitle, cb) {
  const s = pptx.addSlide()
  s.background = { fill: C.bg }
  s.addText(title, {
    x: 0.6, y: TITLE_TOP, w: 11.5, h: TITLE_H,
    fontSize: 20, fontFace: FONT, color: C.primary, bold: true,
  })
  if (subtitle) {
    s.addText(subtitle, {
      x: 0.6, y: TITLE_TOP + TITLE_H, w: 11.5, h: 0.25,
      fontSize: 10, fontFace: FONT, color: C.muted,
    })
  }
  s.addShape(pptx.ShapeType.rect, { x: 0.6, y: DIVIDER_Y, w: 12.13, h: 0.018, fill: { color: C.primary } })
  s.addText(`${s.slideNumber}`, { x: 12.0, y: 7.1, w: 1, h: 0.3, fontSize: 9, fontFace: 'Arial', color: C.muted, align: 'right' })
  cb(s)
}

function card(s, x, y, w, h, fill = C.cardBg) {
  s.addShape(pptx.ShapeType.roundRect, { x, y, w, h, fill: { color: fill }, line: { color: C.border, width: 0.5 }, rectRadius: 0.08 })
}

function sideBar(s, x, y, h, color) {
  s.addShape(pptx.ShapeType.roundRect, { x: x + 0.05, y: y + 0.05, w: 0.05, h: h - 0.1, fill: { color } })
}

function bullets(s, items, x, y, w, h, opts = {}) {
  const texts = items.map((it) => ({ text: it.text, options: { fontSize: opts.fs ?? 11, fontFace: FONT, color: opts.color ?? C.text, bullet: { code: it.bullet ?? '25CF' }, indentLevel: it.indent ?? 0, paraSpaceAfter: opts.space ?? 4 } }))
  s.addText(texts, { x, y, w, h, valign: 'top', lineSpacing: opts.ls ?? 20 })
}

function sectionHeader(s, text, x, y, w, color) {
  s.addText(text, { x, y, w, h: 0.32, fontSize: 12, fontFace: FONT, color, bold: true })
}

function numCircle(s, num, x, y, color) {
  s.addShape(pptx.ShapeType.ellipse, { x, y, w: 0.42, h: 0.42, fill: { color } })
  s.addText(String(num), { x, y, w: 0.42, h: 0.42, fontSize: 16, fontFace: 'Arial', color: C.cardBg, bold: true, align: 'center', valign: 'middle' })
}

// ==================== 封面 ====================
{
  const s = pptx.addSlide()
  s.background = { fill: C.bg }
  s.addShape(pptx.ShapeType.rect, { x: 0, y: 0, w: SW, h: 0.15, fill: { color: C.primary } })
  s.addShape(pptx.ShapeType.rect, { x: 0, y: SH - 0.08, w: SW, h: 0.08, fill: { color: C.primary } })
  s.addShape(pptx.ShapeType.rect, { x: 1.5, y: 1.4, w: 0.06, h: 3.8, fill: { color: C.accent } })
  s.addText('核心数据底座落成', { x: 2.0, y: 1.6, w: 10, h: 1.0, fontSize: 36, fontFace: FONT, color: C.text, bold: true })
  s.addText('建筑能效与空调系统双库设计汇报', { x: 2.0, y: 2.7, w: 10, h: 0.7, fontSize: 22, fontFace: FONT, color: C.primary })
  s.addShape(pptx.ShapeType.rect, { x: 2.0, y: 3.6, w: 3.0, h: 0.025, fill: { color: C.accent } })
  s.addText([
    { text: '懂业务 \u00b7 建底座 \u00b7 定规则\n', options: { fontSize: 14, fontFace: FONT, color: C.muted } },
    { text: '只有懂业务，代码才知道该怎么帮客户省钱', options: { fontSize: 12, fontFace: FONT, color: C.text, italic: true } },
  ], { x: 2.0, y: 4.0, w: 10, h: 1.0, align: 'left', lineSpacing: 26 })
  s.addText('汇报日期：2026/07/03', { x: 2.0, y: 5.6, w: 10, h: 0.4, fontSize: 10, fontFace: FONT, color: C.muted })
}

// ==================== 目录 ====================
addSlide('目  录', '', (s) => {
  card(s, 0.6, CT, 12.13, CH)
  const items = [
    '一、本周工作总览',
    '二、大楼的电都去哪了？— 能耗类型分析',
    '三、帮客户省电的"三大战役"',
    '四、通俗解密中央空调',
    '五、丈量效率的"黄金尺子" — COP计算体系',
    '六、架构选型与总体设计',
    '七、核心业务闭环展示',
    '八、技术与工程研发亮点',
    '九、业务赋能与商业价值',
    '十、下周工作计划',
  ]
  const texts = items.map((it) => ({ text: it, options: { fontSize: 14, fontFace: FONT, color: C.text, bullet: { code: '25CF' }, paraSpaceAfter: 8 } }))
  s.addText(texts, { x: 1.5, y: 1.6, w: 10, h: 5.2, valign: 'middle', lineSpacing: 28 })
})

// ==================== 一、本周工作总览 ====================
addSlide('一、本周工作总览', '', (s) => {
  const lw = 5.8, rw = 5.93, lx = 0.6, rx = lx + lw + 0.4

  // 左侧：双线并进
  card(s, lx, CT, lw, CH)
  sideBar(s, lx, CT, CH, C.accent)
  sectionHeader(s, '本周双线并进', 0.9, 1.5, 5.0, C.accent)

  // 子卡1：数据库设计
  card(s, 0.9, 2.1, 5.1, 1.8, C.greenBg)
  s.addText('数据库设计（2份交付物）', { x: 1.1, y: 2.15, w: 4.5, h: 0.35, fontSize: 11, fontFace: FONT, color: C.accent, bold: true })
  bullets(s, [
    { text: '《冷热源与能效系统数据库详细设计书》（12 张核心表）' },
    { text: '《空调/用能系统专项数据库设计书》（资产/测点/拓扑）' },
  ], 1.1, 2.55, 4.5, 1.2, { fs: 10, ls: 20 })

  // 子卡2：业务研究
  card(s, 0.9, 4.15, 5.1, 2.5, C.blueBg)
  s.addText('业务研究（4大模块深入梳理）', { x: 1.1, y: 4.2, w: 4.5, h: 0.35, fontSize: 11, fontFace: FONT, color: C.primary, bold: true })
  bullets(s, [
    { text: '建筑能耗结构分析（暖通/照明/动力/特殊）' },
    { text: '三级降耗路径对比（被动/主动/智慧节能）' },
    { text: '中央空调三大循环热力学原理' },
    { text: 'COP/IPLV/SCOP 计算标准体系' },
  ], 1.1, 4.6, 4.5, 1.8, { fs: 10, ls: 20 })

  // 右侧：达成目标
  card(s, rx, CT, rw, CH)
  sideBar(s, rx, CT, CH, C.highlight)
  sectionHeader(s, '达成目标', rx + 0.35, 1.5, 5.0, C.highlight)
  bullets(s, [
    { text: '彻底厘清从物理硬件到软件微服务的数据流转闭环', bullet: '27A2' },
    { text: '确立"动静分离、双库联动"底层架构', bullet: '27A2' },
    { text: '深入掌握暖通空调物理规律与行业标准', bullet: '27A2' },
    { text: '定义了 COP/SCOP 等关键能效指标的实时计算模型', bullet: '27A2' },
    { text: '为后续算法引擎与 AI 优化打下业务+数据双基础', bullet: '27A2' },
  ], rx + 0.35, 2.2, 5.1, 4.2, { fs: 11, ls: 24, space: 8 })
})

// ==================== 二、大楼的电都去哪了？— 能耗类型分析 ====================
addSlide('二、大楼的电都去哪了？ — 能耗类型分析', '', (s) => {
  // 左侧：饼图模拟（用4个彩色矩形块 + 百分比标签）
  const pieX = 0.8, pieY = 1.6, pieW = 5.2, pieH = 5.2
  card(s, 0.6, CT, 5.6, CH)

  // 模拟饼图：从上到下堆叠色块
  const segments = [
    { label: '暖通空调 (HVAC)', pct: '40%~60%', color: C.pieBlue, share: 0.50 },
    { label: '照明与插座系统',      pct: '20%~30%', color: C.pieGreen, share: 0.25 },
    { label: '动力系统',            pct: '10%~15%', color: C.pieOrange, share: 0.13 },
    { label: '特殊/弱电系统',       pct: '5%~10%',   color: C.pieGray, share: 0.12 },
  ]

  let soFar = 0
  const baseY = 1.6, visH = 5.0
  segments.forEach((seg) => {
    const h = visH * seg.share
    const y = baseY + soFar
    s.addShape(pptx.ShapeType.roundRect, { x: 1.0, y, w: 2.0, h, fill: { color: seg.color }, rectRadius: 0.04 })
    // 标签
    s.addText([
      { text: seg.label + '\n', options: { fontSize: 11, fontFace: FONT, color: C.text, bold: true } },
      { text: seg.pct, options: { fontSize: 16, fontFace: 'Arial', color: seg.color, bold: true } },
    ], { x: 3.3, y: y, w: 2.8, h, valign: 'middle', lineSpacing: 20 })
    soFar += h
  })

  // 右侧：核心痛点 + 目标
  const rx = 6.55, rw = 6.18
  card(s, rx, CT, rw, CH)
  sideBar(s, rx, CT, CH, C.red)
  sectionHeader(s, '核心痛点与目标', rx + 0.35, 1.5, 5.0, C.red)

  // 痛点卡
  card(s, rx + 0.15, 2.1, rw - 0.3, 2.0, 'FDF2F2')
  s.addText([
    { text: '\u26A0 暖通空调 (HVAC) — 绝对的"耗电巨兽"\n', options: { fontSize: 12, fontFace: FONT, color: C.red, bold: true } },
    { text: '核心痛点：长期处于"大马拉小车"的过剩状态\n\n\u26A0 照明与插座 — 隐形的浪费\n', options: { fontSize: 10, fontFace: FONT, color: C.text } },
    { text: '核心痛点：下班长明灯、过度照明、设备待机', options: { fontSize: 10, fontFace: FONT, color: C.text } },
  ], { x: rx + 0.35, y: 2.2, w: rw - 0.7, h: 1.8, lineSpacing: 20 })

  // 目标卡
  card(s, rx + 0.15, 4.35, rw - 0.3, 2.3, C.blueBg)
  s.addText([
    { text: '\uD83C\uDFAF 我们的目标\n', options: { fontSize: 12, fontFace: FONT, color: C.primary, bold: true } },
    { text: '算法优化的第一主战场 = HVAC 暖通系统\n\n', options: { fontSize: 11, fontFace: FONT, color: C.text } },
    { text: '动力系统 (10%~15%)：客梯、货梯、给排水泵\n特殊/弱电 (5%~10%)：数据机房、安防监控', options: { fontSize: 10, fontFace: FONT, color: C.muted } },
  ], { x: rx + 0.35, y: 4.5, w: rw - 0.7, h: 2.0, lineSpacing: 22 })
})

// ==================== 三、帮客户省电的"三大战役" ====================
addSlide('三、帮客户省电的"三大战役" — 降耗路径', '', (s) => {
  const n = 3
  const totalGap = 0.4 * (n - 1)
  const bw = (12.13 - totalGap) / n
  const sx = 0.6
  const gap = 0.4

  const battles = [
    {
      title: '第一战：物理防御',
      sub: '被动式节能',
      items: ['贴隔热膜、外墙保温', '自然通风利用', '遮阳设计'],
      eval: '建筑设计范畴\n后期改造贵，天花板低',
      evalColor: C.muted,
      color: C.pieGray,
    },
    {
      title: '第二战：装备升级',
      sub: '主动式节能',
      items: ['换高效主机', '水泵加装变频器', 'LED节能灯具'],
      eval: '水泵转速下降20%\n耗电量骤降近50%！',
      evalColor: C.accent,
      color: C.pieGreen,
    },
    {
      title: '第三战：大脑赋能',
      sub: '智慧型节能 — 我们的价值',
      items: ['IoT物联网平台', 'AI算法全局联动', '供需精准匹配'],
      eval: '不用换大设备\nROI最高的路径！',
      evalColor: C.highlight,
      color: C.pieBlue,
    },
  ]

  battles.forEach((b, i) => {
    const x = sx + i * (bw + gap)
    card(s, x, CT, bw, CH)
    sideBar(s, x, CT, CH, b.color)

    // 编号
    numCircle(s, i + 1, x + bw / 2 - 0.21, 1.5, b.color)

    // 标题
    s.addText(b.title, { x: x + 0.2, y: 2.15, w: bw - 0.4, h: 0.35, fontSize: 14, fontFace: FONT, color: b.color, bold: true, align: 'center' })
    s.addText(b.sub, { x: x + 0.2, y: 2.5, w: bw - 0.4, h: 0.25, fontSize: 9, fontFace: FONT, color: C.muted, align: 'center' })

    s.addShape(pptx.ShapeType.rect, { x: x + bw / 2 - 0.6, y: 2.9, w: 1.2, h: 0.015, fill: { color: b.color } })

    // 手段
    s.addText('手  段', { x: x + 0.3, y: 3.1, w: bw - 0.6, h: 0.25, fontSize: 9, fontFace: FONT, color: C.muted, align: 'center' })
    b.items.forEach((item, j) => {
      s.addText(item, { x: x + 0.3, y: 3.4 + j * 0.4, w: bw - 0.6, h: 0.35, fontSize: 10, fontFace: FONT, color: C.text, align: 'center' })
    })

    // 评价
    s.addShape(pptx.ShapeType.rect, { x: x + bw / 2 - 0.8, y: 5.0, w: 1.6, h: 0.015, fill: { color: C.border } })
    s.addText('评  价', { x: x + 0.3, y: 5.15, w: bw - 0.6, h: 0.2, fontSize: 8, fontFace: FONT, color: C.muted, align: 'center' })
    s.addText(b.eval, { x: x + 0.3, y: 5.4, w: bw - 0.6, h: 1.2, fontSize: 10, fontFace: FONT, color: b.evalColor, bold: true, align: 'center', lineSpacing: 18 })
  })

  // 递进箭头
  for (let i = 0; i < n - 1; i++) {
    const ax = sx + (i + 1) * bw + i * gap
    s.addText('\u25B6', { x: ax - 0.1, y: CT + CH / 2 - 0.25, w: gap + 0.2, h: 0.5, fontSize: 18, fontFace: FONT, color: C.flowLine, align: 'center', valign: 'middle' })
  }
})

// ==================== 四、通俗解密中央空调 ====================
addSlide('四、通俗解密中央空调 — 大楼里的"热量快递公司"', '', (s) => {
  // 顶行提示
  s.addText('核心认知：空调不生产冷气，它只是热量的搬运工 — 把室内的热量搬到室外，人就凉快了。', {
    x: 0.6, y: CT - 0.05, w: 12.13, h: 0.35,
    fontSize: 12, fontFace: FONT, color: C.highlight, bold: true, align: 'center',
  })

  // 三个流程节点水平排列
  const bw = 3.6, bh = 4.2
  const gap = 0.45
  const totalW = bw * 3 + gap * 2
  const startX = (13.33 - totalW) / 2
  const flowY = 1.75

  const flows = [
    {
      label: '室内收件',
      sub: '冷冻水循环',
      color: C.pieBlue,
      body: '7\u2103 冰水流进房间\n吸收热量变成 12\u2103\n\n\u25B8 末端风机盘管\n\u25B8 把冷量送入室内',
      arrow: '\u25B6',
    },
    {
      label: '中转中心',
      sub: '制冷剂循环 / 冷水机组',
      color: C.red,
      body: '制冷剂吸走水中的热量\n压缩机将其变成\n高温高压气体\n\n\u26A0 大楼里的"电老虎"\n核心能耗所在',
      arrow: '\u25B6',
    },
    {
      label: '室外倒垃圾',
      sub: '冷却水循环',
      color: C.pieOrange,
      body: '32\u2103 冷却水带走热量\n送到楼顶冷却塔\n通过大风扇排入大气\n\n\u25B8 热量最终散到室外\n\u25B8 完成一个完整循环',
      arrow: '',
    },
  ]

  flows.forEach((f, i) => {
    const x = startX + i * (bw + gap)
    card(s, x, flowY, bw, bh, f.color)

    // 标题
    s.addText(f.label, { x: x + 0.15, y: flowY + 0.15, w: bw - 0.3, h: 0.4, fontSize: 15, fontFace: FONT, color: C.cardBg, bold: true, align: 'center' })
    s.addText(f.sub, { x: x + 0.15, y: flowY + 0.55, w: bw - 0.3, h: 0.3, fontSize: 10, fontFace: FONT, color: C.cardBg, align: 'center' })
    s.addShape(pptx.ShapeType.rect, { x: x + 0.4, y: flowY + 0.95, w: bw - 0.8, h: 0.013, fill: { color: C.cardBg } })

    // 内容
    s.addText(f.body, { x: x + 0.25, y: flowY + 1.1, w: bw - 0.5, h: 2.8, fontSize: 11, fontFace: FONT, color: C.cardBg, align: 'center', lineSpacing: 22 })

    // 箭头
    if (f.arrow) {
      s.addText(f.arrow, { x: x + bw, y: flowY + bh / 2 - 0.2, w: gap, h: 0.4, fontSize: 18, fontFace: FONT, color: C.flowLine, align: 'center', valign: 'middle' })
    }
  })

  // 底部：控制目标
  card(s, 0.6, 6.15, 12.13, 0.95, C.blueBg)
  s.addText([
    { text: '我们的控制目标（机电四大件）：  ', options: { fontSize: 11, fontFace: FONT, color: C.primary, bold: true } },
    { text: '冷水机组（控水温）  |  水泵（控转速频率）  |  冷却塔风机（控启停）  |  末端风机（控阀门）', options: { fontSize: 11, fontFace: FONT, color: C.text } },
  ], { x: 1.0, y: 6.2, w: 11.4, h: 0.85, valign: 'middle', lineSpacing: 18 })
})

// ==================== 五、丈量效率的"黄金尺子" — COP计算体系 ====================
addSlide('五、丈量效率的"黄金尺子" — COP计算体系', '', (s) => {
  // 上半部分：三种COP对比
  const upperH = 3.5
  const upperY = CT

  const copCards = [
    {
      title: '名义 COP',
      sub: '厂家宣传单',
      color: C.pieGray,
      bg: 'F5F5F5',
      body: '实验室极限跑分\n现实中（阴雨天、\n非满载工况）\n根本达不到',
    },
    {
      title: 'IPLV',
      sub: '综合部分负荷能效',
      color: C.pieGreen,
      bg: C.greenBg,
      body: '加权平均了\n25% / 50% / 75% / 100%\n四种负荷率下的得分\n比较客观',
    },
    {
      title: 'SCOP',
      sub: '系统综合能效 — 我们的终极看板',
      color: C.pieBlue,
      bg: C.blueBg,
      body: 'SCOP = 总制冷量 /\n(机组 + 水泵 + 风机耗电)\n追求"整体最优"\n而非"单机最优"',
    },
  ]

  const ccw = 3.85
  const ccGap = 0.3
  const ccTotal = ccw * 3 + ccGap * 2
  const ccStartX = (13.33 - ccTotal) / 2

  copCards.forEach((cc, i) => {
    const x = ccStartX + i * (ccw + ccGap)
    card(s, x, upperY, ccw, upperH, cc.bg)
    sideBar(s, x, upperY, upperH, cc.color)

    s.addText(cc.title, { x: x + 0.35, y: upperY + 0.2, w: ccw - 0.7, h: 0.35, fontSize: 14, fontFace: FONT, color: cc.color, bold: true, align: 'center' })
    s.addText(cc.sub, { x: x + 0.35, y: upperY + 0.55, w: ccw - 0.7, h: 0.25, fontSize: 9, fontFace: FONT, color: C.muted, align: 'center' })
    s.addShape(pptx.ShapeType.rect, { x: x + ccw / 2 - 0.5, y: upperY + 0.95, w: 1.0, h: 0.013, fill: { color: cc.color } })
    s.addText(cc.body, { x: x + 0.35, y: upperY + 1.15, w: ccw - 0.7, h: 2.0, fontSize: 11, fontFace: FONT, color: C.text, align: 'center', lineSpacing: 24 })
  })

  // 下半部分：COP白话 + 行业黑话
  const lowerY = upperY + upperH + 0.2
  const lowerH = CH - upperH - 0.2

  // 左侧：COP白话解释
  const lw2 = 5.8
  card(s, 0.6, lowerY, lw2, lowerH)
  sideBar(s, 0.6, lowerY, lowerH, C.highlight)
  sectionHeader(s, '什么是 COP（性能系数）？', 0.9, lowerY + 0.2, 5, C.highlight)
  s.addText('大白话：我喂给你 1 度电，你能帮我搬走几度电的热量？', {
    x: 0.9, y: lowerY + 0.65, w: 5.1, h: 0.4,
    fontSize: 12, fontFace: FONT, color: C.text, bold: true,
  })
  s.addText('COP 越高 = 越省电。比如 COP = 5，就是 1 度电换 5 份冷量。', {
    x: 0.9, y: lowerY + 1.15, w: 5.1, h: 0.4,
    fontSize: 11, fontFace: FONT, color: C.muted,
  })
  s.addText('痛点：很多大楼买了顶级空调，但水泵和风机把电偷吃了。', {
    x: 0.9, y: lowerY + 1.7, w: 5.1, h: 0.5,
    fontSize: 10, fontFace: FONT, color: C.red,
  })

  // 右侧：行业黑话
  const rw2 = 5.93, rx2 = 0.6 + lw2 + 0.4
  card(s, rx2, lowerY, rw2, lowerH)
  sideBar(s, rx2, lowerY, lowerH, C.purple)
  sectionHeader(s, '行业黑话补充', rx2 + 0.35, lowerY + 0.2, 5, C.purple)

  card(s, rx2 + 0.2, lowerY + 0.8, rw2 - 0.4, 1.8, 'F8F5FC')
  s.addText([
    { text: 'kW/RT（每冷吨耗电量）\n', options: { fontSize: 12, fontFace: FONT, color: C.purple, bold: true } },
    { text: '南方及外资常用，与 COP 互反\n数值越小越好：', options: { fontSize: 10, fontFace: FONT, color: C.text } },
  ], { x: rx2 + 0.5, y: lowerY + 0.9, w: rw2 - 1.0, h: 1.6, lineSpacing: 20 })

  // 指标条
  s.addShape(pptx.ShapeType.roundRect, { x: rx2 + 0.5, y: lowerY + 1.7, w: 3.2, h: 0.35, fill: { color: C.accent }, rectRadius: 0.04 })
  s.addText('0.8 \u2192 优秀', { x: rx2 + 0.5, y: lowerY + 1.7, w: 1.6, h: 0.35, fontSize: 11, fontFace: FONT, color: C.cardBg, bold: true, align: 'center', valign: 'middle' })
  s.addShape(pptx.ShapeType.roundRect, { x: rx2 + 0.5 + 1.6, y: lowerY + 1.7, w: 1.6, h: 0.35, fill: { color: C.red }, rectRadius: 0.04 })
  s.addText('1.2 \u2192 糟糕', { x: rx2 + 0.5 + 1.6, y: lowerY + 1.7, w: 1.6, h: 0.35, fontSize: 11, fontFace: FONT, color: C.cardBg, bold: true, align: 'center', valign: 'middle' })
})

// ==================== 六、架构选型与总体设计 ====================
addSlide('六、架构选型与总体设计', '', (s) => {
  const colW = 5.8, leftX = 0.6, rightX = leftX + colW + 0.4

  card(s, leftX, CT, colW, 4.6, C.blueBg)
  sideBar(s, leftX, CT, 4.6, C.primary)
  sectionHeader(s, '静态关系底座 (MySQL)', 0.9, 1.45, 4.5, C.primary)
  s.addText('9 张核心表', { x: 0.9, y: 1.82, w: 4, h: 0.25, fontSize: 9, fontFace: FONT, color: C.muted })
  bullets(s, [
    { text: '空间拓扑：建筑 \u2192 楼层 \u2192 区域 \u2192 设备', bullet: '25B8' },
    { text: '统一设备台账：冷机、水泵、空调、传感器', bullet: '25B8' },
    { text: '用能系统建制：按空调、照明等系统分组', bullet: '25B8' },
    { text: '告警规则闭环：规则定义 \u2192 触发 \u2192 记录 \u2192 处理', bullet: '25B8' },
    { text: '作为微服务业务流转与权限控制的"承重墙"', bullet: '25B8' },
  ], 0.9, 2.2, 5.1, 3.8, { fs: 11, ls: 22 })

  card(s, rightX, CT, colW, 4.6, C.greenBg)
  sideBar(s, rightX, CT, 4.6, C.accent)
  sectionHeader(s, '动态时序引擎 (TDengine)', rightX + 0.3, 1.45, 4.5, C.accent)
  s.addText('3 张超级表 (Super Table)', { x: rightX + 0.3, y: 1.82, w: 4, h: 0.25, fontSize: 9, fontFace: FONT, color: C.muted })
  bullets(s, [
    { text: '窄表 + 多维标签：设备/测点/系统', bullet: '25B8' },
    { text: '进出水温度、流量、电量等千万级高频数据', bullet: '25B8' },
    { text: '极速写入：单节点百万点/秒', bullet: '25B8' },
    { text: '窗口聚合：分钟/小时/天自动降采样', bullet: '25B8' },
    { text: '为 COP/SCOP 计算提供毫秒级数据支撑', bullet: '25B8' },
  ], rightX + 0.3, 2.2, 5.1, 3.8, { fs: 11, ls: 22 })

  card(s, 0.6, 6.1, 12.13, 1.0, C.orangeBg)
  s.addText([
    { text: '架构收益：', options: { fontSize: 12, fontFace: FONT, color: C.highlight, bold: true } },
    { text: '物理设备上报与上层算法计算彻底解耦，系统具备极强的横向扩展能力', options: { fontSize: 12, fontFace: FONT, color: C.text } },
  ], { x: 1.0, y: 6.2, w: 11.4, h: 0.8, valign: 'middle', lineSpacing: 20 })
})

// ==================== 七、核心业务闭环展示 ====================
addSlide('七、核心业务闭环展示', '', (s) => {
  s.addText('从数据采集到能效诊断的完整闭环', { x: 0.6, y: DIVIDER_Y + 0.1, w: 12.13, h: 0.35, fontSize: 12, fontFace: FONT, color: C.muted, align: 'center' })

  const n = 4, gap = 0.4
  const bw = (12.13 - gap * (n - 1)) / n, bh = 4.2
  const sx = 0.6, topY = 1.75

  const nodes = [
    { label: '物联接入与翻译', items: ['biz_data_point 测点字典表', '散乱报文\u2192标准业务数据', '默认值兜底防零值宕机'], color: C.primary },
    { label: '分钟级对齐与清洗', items: ['时间窗口 (Time Window)', '离散数据对齐到整点秒', '确保计算基准统一'], color: C.accent },
    { label: '算法引擎推盘', items: ['定时计算单机/系统 COP', '结果存入 st_indicator_minute', '支撑大屏极速渲染'], color: C.purple },
    { label: '规则引擎与告警', items: ['内存 + Redis 规则比对', 'duration_minutes 防抖机制', '过滤硬件毛刺误报'], color: C.highlight },
  ]

  nodes.forEach((node, i) => {
    const x = sx + i * (bw + gap)
    card(s, x, topY, bw, bh, node.color)
    s.addText(node.label, { x: x + 0.15, y: topY + 0.15, w: bw - 0.3, h: 0.5, fontSize: 13, fontFace: FONT, color: C.cardBg, bold: true, align: 'center' })
    s.addShape(pptx.ShapeType.rect, { x: x + 0.4, y: topY + 0.78, w: bw - 0.8, h: 0.015, fill: { color: C.cardBg } })
    node.items.forEach((item, j) => {
      s.addText([{ text: '\u25B8 ', options: { fontSize: 9, fontFace: FONT, color: C.cardBg } }, { text: item, options: { fontSize: 10, fontFace: FONT, color: C.cardBg } }], { x: x + 0.2, y: topY + 1.0 + j * 0.85, w: bw - 0.4, h: 0.7, lineSpacing: 16 })
    })
  })

  for (let i = 0; i < n - 1; i++) {
    const ax = sx + (i + 1) * bw + i * gap
    s.addText('\u25B6', { x: ax - 0.1, y: topY + bh / 2 - 0.2, w: gap + 0.2, h: 0.4, fontSize: 16, fontFace: FONT, color: C.flowLine, align: 'center', valign: 'middle' })
  }
})

// ==================== 八、技术与工程研发亮点 ====================
addSlide('八、技术与工程研发亮点', '', (s) => {
  const colW = 5.8, lx = 0.6, rx = lx + colW + 0.4
  const uh = 2.6, lt = CT + uh + 0.25, lh = CH - uh - 0.25

  card(s, lx, CT, colW, uh)
  sideBar(s, lx, CT, uh, C.primary)
  sectionHeader(s, '极致查询优化 — 谓词下推', 0.9, 1.45, 4.8, C.primary)
  bullets(s, [
    { text: '引入 is_for_calc 字段标识参与计算的测点', bullet: '25B8' },
    { text: '无效测点在数据库层直接过滤，不进入 JVM', bullet: '25B8' },
    { text: '大幅降低内存压力与网络 I/O 消耗', bullet: '25B8' },
  ], 0.9, 2.05, 5.1, 1.8, { fs: 11, ls: 22 })

  card(s, rx, CT, colW, uh)
  sideBar(s, rx, CT, uh, C.accent)
  sectionHeader(s, '规则快照设计', rx + 0.3, 1.45, 4.8, C.accent)
  bullets(s, [
    { text: '报警流水表冗余存储 threshold_value 阈值', bullet: '25B8' },
    { text: '保证业务规则历史快照的绝对一致性', bullet: '25B8' },
    { text: '方便后期运维溯源与审计追溯', bullet: '25B8' },
  ], rx + 0.3, 2.05, 5.1, 1.8, { fs: 11, ls: 22 })

  card(s, 0.6, lt, 12.13, lh, C.grayBg)
  sideBar(s, 0.6, lt, lh, C.primary)
  sectionHeader(s, '研发效能提升战略', 0.9, lt + 0.2, 5, C.primary)
  bullets(s, [
    { text: 'CRUD 接口 + MyBatis-Plus 实体类 + 关联映射 \u2192 交由 Trae / Cursor 等 AI 编程工具辅助生成', bullet: '2714' },
    { text: '团队研发精力 100% 聚焦：微服务 RPC 调度 + 分布式定时计算 + 复杂能效算法落地', bullet: '2714' },
    { text: '大幅缩短项目周期，加速从"设计"到"交付"', bullet: '2714' },
  ], 0.9, lt + 0.7, 11.2, lh - 1.0, { fs: 12, ls: 24, space: 8 })
})

// ==================== 九、业务赋能与商业价值 ====================
addSlide('九、业务赋能与商业价值', '', (s) => {
  const n = 3, gap = 0.4
  const bw = (12.13 - gap * (n - 1)) / n
  const sx = 0.6

  const items = [
    { title: '精细化算账', desc: '打破传统"一表一记"粗放模式，实现"大楼 \u2192 用能系统 \u2192 核心设备"三级能效向下钻取分析。', color: C.primary },
    { title: '动态碳排追踪', desc: '预留动态因子时序表，可无缝对接外部电网 API，实现碳排放指标的实时、精准核算。', color: C.accent },
    { title: '主动防御式运维', desc: '通过 COP 偏差分析与防抖告警机制，提前发现设备带病运行，降低整体能耗浪费。', color: C.highlight },
  ]

  items.forEach((item, i) => {
    const x = sx + i * (bw + gap)
    card(s, x, CT, bw, CH)
    sideBar(s, x, CT, CH, item.color)
    numCircle(s, i + 1, x + bw / 2 - 0.21, 1.6, item.color)
    s.addText(item.title, { x: x + 0.3, y: 2.25, w: bw - 0.6, h: 0.45, fontSize: 15, fontFace: FONT, color: item.color, bold: true, align: 'center' })
    s.addShape(pptx.ShapeType.rect, { x: x + bw / 2 - 0.6, y: 2.85, w: 1.2, h: 0.018, fill: { color: item.color } })
    s.addText(item.desc, { x: x + 0.4, y: 3.1, w: bw - 0.8, h: 3.2, fontSize: 11, fontFace: FONT, color: C.text, align: 'center', lineSpacing: 26, valign: 'top' })
  })
})

// ==================== 十、下周工作计划 ====================
addSlide('十、下周工作计划', '', (s) => {
  s.addText('从数据层向微服务业务层的冲锋', { x: 0.6, y: DIVIDER_Y + 0.08, w: 12.13, h: 0.35, fontSize: 12, fontFace: FONT, color: C.muted, align: 'center' })

  const tasks = [
    { title: '微服务模块拆分', desc: '基于已敲定的表结构，完成 Spring Boot / Spring Cloud 工程的模块划分。包括：物联接入服务、能效计算服务、告警中心服务等核心微服务。', bg: C.greenBg, bar: C.accent },
    { title: '核心计算逻辑落地', desc: '启动 COP、热效率等核心物理计算公式在 Java 层的编写与单元测试，确保计算结果与设计书中的公式完全对齐。', bg: C.blueBg, bar: C.primary },
    { title: '接口契约定义', desc: '梳理并定义前后端交互的 RESTful API 接口文档，拉齐前后端开发进度，确保并行开发互不阻塞。', bg: 'FEF9F0', bar: C.highlight },
  ]

  const cH = 1.6, cGap = 0.25, sY = 1.75
  tasks.forEach((task, i) => {
    const y = sY + i * (cH + cGap)
    card(s, 0.6, y, 12.13, cH, task.bg)
    sideBar(s, 0.6, y, cH, task.bar)
    s.addText(`0${i + 1}`, { x: 0.9, y: y + 0.1, w: 0.6, h: cH - 0.2, fontSize: 20, fontFace: 'Arial', color: task.bar, bold: true, align: 'center', valign: 'middle' })
    s.addText(task.title, { x: 1.65, y: y + 0.15, w: 9, h: 0.4, fontSize: 14, fontFace: FONT, color: C.text, bold: true })
    s.addText(task.desc, { x: 1.65, y: y + 0.6, w: 10.5, h: 0.85, fontSize: 11, fontFace: FONT, color: C.muted, lineSpacing: 18 })
  })
})

// ==================== 尾页 ====================
{
  const s = pptx.addSlide()
  s.background = { fill: C.bg }
  s.addShape(pptx.ShapeType.rect, { x: 0, y: 0, w: SW, h: 0.15, fill: { color: C.primary } })
  s.addShape(pptx.ShapeType.rect, { x: 0, y: SH - 0.08, w: SW, h: 0.08, fill: { color: C.primary } })
  s.addText('谢谢！', { x: 1, y: 2.3, w: 11.33, h: 1.5, fontSize: 48, fontFace: FONT, color: C.text, bold: true, align: 'center' })
  s.addShape(pptx.ShapeType.rect, { x: 5.5, y: 4.0, w: 2.33, h: 0.025, fill: { color: C.accent } })
  s.addText('懂业务 \u00b7 建底座 \u00b7 定规则', { x: 1, y: 4.3, w: 11.33, h: 0.7, fontSize: 18, fontFace: FONT, color: C.primary, align: 'center' })
  s.addText('建筑能效与空调系统 — 双库设计汇报    2026/07/03', { x: 1, y: 5.3, w: 11.33, h: 0.4, fontSize: 11, fontFace: FONT, color: C.muted, align: 'center' })
}

// ==================== 保存 ====================
const outputPath = 'd:/word/iot-platform-demo/.trae/documents/建筑能效与智控-业务梳理汇报-v4.pptx'
await pptx.writeFile({ fileName: outputPath })
console.log('Done: ' + outputPath)
console.log('Pages: ' + pptx.slides.length)
