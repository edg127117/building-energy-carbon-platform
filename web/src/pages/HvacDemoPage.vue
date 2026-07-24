<template>
  <div class="hvac-demo">
    <div class="ambient ambient-a" />
    <div class="ambient ambient-b" />

    <header class="topbar">
      <div class="brand">
        <div class="brand-mark"><Snowflake :size="20" /></div>
        <div>
          <div class="brand-title">中央空调经常性调适平台</div>
          <div class="brand-subtitle">CONTINUOUS COMMISSIONING CENTER</div>
        </div>
      </div>

      <div class="header-center">
        <div class="building-pill"><Building2 :size="15" /> 所在大楼 · 屋顶中央空调系统</div>
        <div class="demo-pill">演示数据</div>
      </div>

      <div class="header-status">
        <div class="status-item"><span class="status-dot online" /> 数据链路正常</div>
        <div class="clock">{{ clock }}</div>
      </div>
    </header>

    <main class="dashboard-shell">
      <section class="summary-strip">
        <div>
          <div class="eyebrow">SYSTEM OVERVIEW</div>
          <h1>中央空调系统实时能效总览</h1>
          <p>单体设备能效计算 · 19 个核心测点 · 分钟级更新</p>
        </div>
        <div class="summary-stats">
          <div class="summary-stat">
            <span>在线设备</span><strong>4/4</strong><small>全部运行</small>
          </div>
          <div class="summary-stat">
            <span>有效测点</span><strong>19/19</strong><small>质量良好</small>
          </div>
          <div class="summary-stat">
            <span>系统负荷率</span><strong>{{ loadRate.toFixed(0) }}%</strong><small>平稳运行</small>
          </div>
        </div>
      </section>

      <section class="main-grid">
        <article class="panel topology-panel">
          <div class="panel-heading">
            <div>
              <span class="section-index">01</span>
              <span class="panel-title">系统运行拓扑</span>
              <span class="panel-subtitle">点击设备查看能效计算过程</span>
            </div>
            <div class="legend">
              <span><i class="legend-line cooling" />冷却水</span>
              <span><i class="legend-line chilled" />冷冻水</span>
              <span><i class="legend-line air" />送回风</span>
            </div>
          </div>

          <div class="topology-canvas">
            <div class="zone-label zone-cooling">冷却水循环</div>
            <div class="zone-label zone-chilled">冷冻水循环</div>
            <div class="zone-label zone-air">VAV 风系统</div>

            <div class="pipe pipe-h cooling-pipe pipe-cooling-supply"><span /></div>
            <div class="pipe pipe-h cooling-pipe pipe-cooling-return reverse"><span /></div>

            <div class="pipe pipe-h chilled-pipe pipe-chilled-supply"><span /></div>
            <div class="pipe pipe-h chilled-pipe pipe-chilled-return reverse"><span /></div>
            <div class="pipe pipe-v chilled-pipe pipe-chilled-drop"><span /></div>
            <div class="pipe pipe-v chilled-pipe pipe-chilled-return-drop reverse"><span /></div>
            <div class="pipe pipe-v chilled-pipe pipe-chilled-rise reverse"><span /></div>
            <div class="pipe pipe-v chilled-pipe pipe-chilled-return-rise"><span /></div>

            <div class="pipe pipe-v air-pipe pipe-air-loop"><span /></div>
            <div class="pipe pipe-v air-pipe pipe-air-return-loop reverse"><span /></div>

            <button class="device-card tower" @click="openFormula('towerEfficiency')">
              <span class="device-glow" />
              <span class="device-icon"><Waves :size="26" /></span>
              <span class="device-meta"><b>CT-01</b><em>冷却塔</em></span>
              <span class="device-value"><b>进水</b> {{ points.TOWER1_TCWin.toFixed(1) }}℃ · <b>出水</b> {{ points.TOWER1_TCWout.toFixed(1) }}℃</span>
              <span class="device-state"><i />运行中</span>
            </button>

            <button class="device-card chiller" @click="openFormula('chillerCop')">
              <span class="device-glow" />
              <span class="device-icon"><Snowflake :size="28" /></span>
              <span class="device-meta"><b>WCR-01</b><em>水冷冷水机组</em></span>
              <span class="device-value"><b>出水</b> {{ points.WCR1_TWout.toFixed(1) }}℃ · <b>功率</b> {{ points.WCR1_PPE.toFixed(1) }} kW</span>
              <span class="device-state"><i />制冷运行</span>
            </button>

            <button class="device-card pump" @click="openFormula('pumpEfficiency')">
              <span class="device-glow" />
              <span class="device-icon"><Gauge :size="27" /></span>
              <span class="device-meta"><b>P-01</b><em>冷冻水泵</em></span>
              <span class="device-value">{{ points.PUMP1_Flow.toFixed(1) }} m³/h</span>
              <span class="device-state"><i />变频运行</span>
            </button>

            <button class="device-card ahu" @click="openFormula('ahuPower')">
              <span class="device-glow" />
              <span class="device-icon fan-icon"><Fan :size="29" /></span>
              <span class="device-meta"><b>AHU-01</b><em>空气处理机组</em></span>
              <span class="device-value">全压 {{ points.AHU1_TotalPress.toFixed(0) }} Pa</span>
              <span class="device-state"><i />送风运行</span>
            </button>

            <div class="vav-terminal">
              <div class="vav-icon"><Wind :size="22" /></div>
              <div><b>VAV 末端</b><span>风量自适应</span></div>
              <div class="air-dots"><i /><i /><i /></div>
            </div>

            <div class="flow-label label-supply">冷冻水供水<br /><b>{{ points.WCR1_TWout.toFixed(1) }}℃</b></div>
            <div class="flow-label label-return">冷冻水回水<br /><b>{{ points.WCR1_TWin.toFixed(1) }}℃</b></div>

            <div class="outdoor-chip"><CloudSun :size="17" /><span>室外 {{ points.DBO_TDB.toFixed(1) }}℃ · RH {{ points.DBO_RH.toFixed(0) }}%</span></div>
          </div>
        </article>

        <aside class="indicators">
          <div class="aside-heading">
            <div><span class="section-index">02</span><span class="panel-title">实时能效指标</span></div>
            <span class="minute-tag">每分钟更新</span>
          </div>

          <button
            v-for="card in indicatorCards"
            :key="card.key"
            class="indicator-card"
            :class="card.tone"
            @click="openFormula(card.key)"
          >
            <span class="indicator-icon"><component :is="card.icon" :size="19" /></span>
            <span class="indicator-main">
              <span class="indicator-label">{{ card.label }}</span>
              <span class="indicator-number">{{ card.value }}<small>{{ card.unit }}</small></span>
            </span>
            <span class="indicator-side">
              <span class="grade">{{ card.grade }}</span>
              <span class="delta"><TrendingUp :size="12" /> {{ card.delta }}</span>
            </span>
            <span class="indicator-progress"><i :style="{ width: card.progress + '%' }" /></span>
          </button>

          <div class="quality-card">
            <div class="quality-head"><span>数据可信度</span><strong>98.6%</strong></div>
            <div class="quality-bar"><i /></div>
            <div class="quality-items">
              <span><i class="q-real" />真实采集 17</span>
              <span><i class="q-fill" />插值补全 2</span>
              <span><i class="q-default" />典型值 0</span>
            </div>
          </div>
        </aside>
      </section>

      <section class="panel formula-catalog">
        <div class="panel-heading formula-catalog-heading">
          <div>
            <span class="section-index">03</span>
            <span class="panel-title">本期公式清单</span>
            <span class="panel-subtitle">依据冻结书菜单公式表逐项展示</span>
          </div>
          <div class="formula-count"><strong>7</strong><span>条计算公式已覆盖</span><em>6 个前端入口 · 水泵包含两段串联计算</em></div>
        </div>
        <div class="formula-grid">
          <button
            v-for="item in formulaCards"
            :key="item.key"
            class="formula-card"
            :class="item.tone"
            @click="openFormula(item.key)"
          >
            <span class="formula-card-icon"><component :is="item.icon" :size="19" /></span>
            <span class="formula-card-main">
              <small>{{ item.group }}</small>
              <b>{{ item.title }}</b>
              <code>{{ item.formula }}</code>
            </span>
            <span class="formula-card-result"><strong>{{ item.result }}</strong><small>{{ item.unit }}</small></span>
            <ChevronRight :size="15" class="formula-card-arrow" />
          </button>
        </div>
      </section>

      <section class="bottom-grid">
        <article class="panel trend-panel">
          <div class="panel-heading compact">
            <div><span class="section-index">04</span><span class="panel-title">能效运行趋势</span><span class="panel-subtitle">最近 24 分钟</span></div>
            <div class="trend-tabs"><button class="active">实时</button><button>1小时</button><button>24小时</button></div>
          </div>
          <div ref="chartRef" class="trend-chart" />
        </article>

        <article class="panel point-panel">
          <div class="panel-heading compact">
            <div><span class="section-index">05</span><span class="panel-title">关键测点</span></div>
            <button class="text-button">查看全部 19 点 <ChevronRight :size="14" /></button>
          </div>
          <div class="point-list">
            <div v-for="point in keyPoints" :key="point.code" class="point-row">
              <span class="point-status" />
              <span class="point-name"><b>{{ point.name }}</b><small>{{ point.code }}</small></span>
              <span class="point-value">{{ point.value }} <small>{{ point.unit }}</small></span>
              <span class="point-quality">实采</span>
            </div>
          </div>
        </article>
      </section>
    </main>

    <a-drawer
      v-model:open="formulaOpen"
      :width="560"
      placement="right"
      :closable="false"
      root-class-name="formula-drawer"
    >
      <template #title>
        <div class="drawer-title">
          <div><span>能效计算详情</span><small>{{ selectedFormula.equip }}</small></div>
          <button @click="formulaOpen = false"><X :size="18" /></button>
        </div>
      </template>

      <div class="formula-content">
        <div class="result-hero" :class="selectedFormula.tone">
          <div><span>{{ selectedFormula.title }}</span><small>{{ selectedFormula.code }}</small></div>
          <strong>{{ selectedFormula.result }}<em>{{ selectedFormula.unit }}</em></strong>
          <div class="result-grade"><span class="status-dot online" />{{ selectedFormula.grade }} · 数据可信</div>
        </div>

        <div class="formula-section">
          <div class="formula-section-title"><Calculator :size="17" />计算过程</div>
          <div v-for="(step, index) in selectedFormula.steps" :key="step.name" class="formula-step">
            <span class="step-no">{{ String(index + 1).padStart(2, '0') }}</span>
            <div>
              <b>{{ step.name }}</b>
              <code>{{ step.formula }}</code>
              <p>{{ step.detail }}</p>
            </div>
            <strong>{{ step.value }}</strong>
          </div>
        </div>

        <div class="formula-section">
          <div class="formula-section-title"><SlidersHorizontal :size="17" />参与测点</div>
          <div class="input-grid">
            <div v-for="input in selectedFormula.inputs" :key="input.code" class="input-card">
              <span>{{ input.name }}</span><b>{{ input.value }} <small>{{ input.unit }}</small></b>
              <em>{{ input.code }} · 实采</em>
            </div>
          </div>
        </div>

        <div class="calc-note">
          <Info :size="16" />
          <span>当前为前端效果演示数据。正式版本将按分钟读取 TDengine 测点并记录完整计算追溯链。</span>
        </div>
      </div>
    </a-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, markRaw, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import * as echarts from 'echarts'
import {
  Activity,
  Building2,
  Calculator,
  ChevronRight,
  CloudSun,
  Fan,
  Gauge,
  Info,
  SlidersHorizontal,
  Snowflake,
  TrendingUp,
  Waves,
  Wind,
  X,
} from 'lucide-vue-next'

type FormulaKey = 'chillerCooling' | 'chillerPower' | 'chillerCop' | 'towerEfficiency' | 'pumpEfficiency' | 'ahuPower'

const clock = ref('')
const loadRate = ref(72)
const formulaOpen = ref(false)
const selectedKey = ref<FormulaKey>('chillerCop')
const chartRef = ref<HTMLDivElement | null>(null)
let chart: echarts.ECharts | null = null
let timer: number | null = null

const indicators = reactive({
  chiller: 6.52,
  tower: 72.6,
  pump: 81.3,
  ahu: 0.42,
})

const points = reactive({
  WCR1_TWin: 12.3,
  WCR1_TWout: 7.1,
  WCR1_Flow: 35.2,
  WCR1_PPE: 32.6,
  WCR1_Voltage: 380,
  WCR1_Current: 56.5,
  WCR1_PF: 0.875,
  TOWER1_TCWin: 32.8,
  TOWER1_TCWout: 27.4,
  TOWER1_TWB: 24.7,
  PUMP1_Flow: 38.6,
  PUMP1_Pout: 312000,
  PUMP1_Pin: 126000,
  PUMP1_Z: 0.6,
  PUMP1_Power: 6.8,
  AHU1_TotalPress: 864,
  AHU1_EtaT: 57.2,
  DBO_TDB: 30.6,
  DBO_RH: 64,
})

const history = reactive({
  labels: Array.from({ length: 24 }, (_, i) => `${String(14 + Math.floor((i + 36) / 60)).padStart(2, '0')}:${String((i + 36) % 60).padStart(2, '0')}`),
  cop: Array.from({ length: 24 }, (_, i) => +(6.25 + Math.sin(i / 4) * 0.22 + Math.random() * 0.08).toFixed(2)),
  tower: Array.from({ length: 24 }, (_, i) => +(71.5 + Math.sin(i / 5) * 2.2 + Math.random() * 0.8).toFixed(1)),
  pump: Array.from({ length: 24 }, (_, i) => +(80.5 + Math.cos(i / 4) * 1.5 + Math.random() * 0.6).toFixed(1)),
})

const indicatorCards = computed(() => [
  { key: 'chillerCop' as FormulaKey, label: '冷水机组 COP', value: indicators.chiller.toFixed(2), unit: '', grade: '高效', delta: '+2.4%', progress: 86, tone: 'blue', icon: markRaw(Snowflake) },
  { key: 'towerEfficiency' as FormulaKey, label: '冷却塔效率', value: indicators.tower.toFixed(1), unit: '%', grade: '正常', delta: '+1.2%', progress: 73, tone: 'green', icon: markRaw(Waves) },
  { key: 'pumpEfficiency' as FormulaKey, label: '水泵效率', value: indicators.pump.toFixed(1), unit: '%', grade: '高效', delta: '+0.8%', progress: 81, tone: 'orange', icon: markRaw(Gauge) },
  { key: 'ahuPower' as FormulaKey, label: '风系统耗功值', value: indicators.ahu.toFixed(2), unit: ' W/(m³·h)', grade: '良好', delta: '-1.1%', progress: 68, tone: 'yellow', icon: markRaw(Fan) },
])

const keyPoints = computed(() => [
  { name: '冷冻水出水温度', code: 'WCR1_TWout', value: points.WCR1_TWout.toFixed(1), unit: '℃' },
  { name: '冷冻水流量', code: 'WCR1_Flow', value: points.WCR1_Flow.toFixed(1), unit: 'm³/h' },
  { name: '机组瞬时功率', code: 'WCR1_PPE', value: points.WCR1_PPE.toFixed(1), unit: 'kW' },
  { name: '冷却塔湿球温度', code: 'TOWER1_TWB', value: points.TOWER1_TWB.toFixed(1), unit: '℃' },
])

const coolingCapacity = computed(() => points.WCR1_Flow * 1000 * 4.18 * (points.WCR1_TWin - points.WCR1_TWout) / 3600)
const fallbackPower = computed(() => points.WCR1_Voltage * points.WCR1_Current * points.WCR1_PF * Math.sqrt(3) / 1000)
const pumpHead = computed(() => (points.PUMP1_Pout - points.PUMP1_Pin) / (1000 * 9.8))

const formulaMap = computed(() => ({
  chillerCooling: {
    equip: 'WCR-01 · 水冷冷水机组', title: '制冷量计算', code: 'WCR_COOLING_CAPACITY', tone: 'blue', grade: '实时计算', result: coolingCapacity.value.toFixed(1), unit: ' kW',
    steps: [
      { name: '计算冷冻水温差', formula: 'ΔT = Tᵢₙ − Tₒᵤₜ', detail: `${points.WCR1_TWin.toFixed(1)} − ${points.WCR1_TWout.toFixed(1)}`, value: `${(points.WCR1_TWin - points.WCR1_TWout).toFixed(1)} K` },
      { name: '计算机组制冷量', formula: 'Q₀ = V × ρ × c × ΔT / 3600', detail: `${points.WCR1_Flow.toFixed(1)} × 1000 × 4.18 × ${(points.WCR1_TWin - points.WCR1_TWout).toFixed(1)} / 3600`, value: `${coolingCapacity.value.toFixed(1)} kW` },
    ],
    inputs: [
      { name: '冷冻水进水温度', code: 'WCR1_TWin', value: points.WCR1_TWin.toFixed(1), unit: '℃' },
      { name: '冷冻水出水温度', code: 'WCR1_TWout', value: points.WCR1_TWout.toFixed(1), unit: '℃' },
      { name: '冷冻水流量', code: 'WCR1_Flow', value: points.WCR1_Flow.toFixed(1), unit: 'm³/h' },
      { name: '水比热容', code: 'CONST_C', value: '4.18', unit: 'kJ/(kg·℃)' },
    ],
  },
  chillerPower: {
    equip: 'WCR-01 · 水冷冷水机组', title: '电功率计算', code: 'WCR_INPUT_POWER', tone: 'blue', grade: '直接读数', result: points.WCR1_PPE.toFixed(1), unit: ' kW',
    steps: [
      { name: '优先读取瞬时功率', formula: 'Nᵢ = PPE', detail: '智能电表有效时采用瞬时有功功率', value: `${points.WCR1_PPE.toFixed(1)} kW` },
      { name: '电参数反算兜底', formula: 'Nᵢ = U × I × cosφ × √3 / 1000', detail: `${points.WCR1_Voltage.toFixed(0)} × ${points.WCR1_Current.toFixed(1)} × ${points.WCR1_PF.toFixed(3)} × √3 / 1000`, value: `${fallbackPower.value.toFixed(1)} kW` },
    ],
    inputs: [
      { name: '瞬时功率', code: 'WCR1_PPE', value: points.WCR1_PPE.toFixed(1), unit: 'kW' },
      { name: '压缩机电压', code: 'WCR1_Voltage', value: points.WCR1_Voltage.toFixed(0), unit: 'V' },
      { name: '压缩机电流', code: 'WCR1_Current', value: points.WCR1_Current.toFixed(1), unit: 'A' },
      { name: '功率因数', code: 'WCR1_PF', value: points.WCR1_PF.toFixed(3), unit: '' },
    ],
  },
  chillerCop: {
    equip: 'WCR-01 · 水冷冷水机组', title: 'COP 计算', code: 'WCR_COP', tone: 'blue', grade: '高效', result: indicators.chiller.toFixed(2), unit: '',
    steps: [
      { name: '计算机组制冷量', formula: 'Q₀ = V × ρ × c × (Tᵢₙ − Tₒᵤₜ) / 3600', detail: `${points.WCR1_Flow.toFixed(1)} × 1000 × 4.18 × (${points.WCR1_TWin.toFixed(1)} − ${points.WCR1_TWout.toFixed(1)}) / 3600`, value: `${coolingCapacity.value.toFixed(1)} kW` },
      { name: '确定机组输入功率', formula: 'Nᵢ = PPE（优先）｜ U × I × cosφ × √3 / 1000（兜底）', detail: `当前智能电表有效，采用 PPE 直接读数 ${points.WCR1_PPE.toFixed(1)} kW`, value: `${points.WCR1_PPE.toFixed(1)} kW` },
      { name: '计算能效比 COP', formula: 'COP = Q₀ / Nᵢ', detail: `${coolingCapacity.value.toFixed(1)} / ${points.WCR1_PPE.toFixed(1)}`, value: indicators.chiller.toFixed(2) },
    ],
    inputs: [
      { name: '机组制冷量', code: 'FORMULA_5_1', value: coolingCapacity.value.toFixed(1), unit: 'kW' },
      { name: '机组输入功率', code: 'FORMULA_5_2', value: points.WCR1_PPE.toFixed(1), unit: 'kW' },
    ],
  },
  towerEfficiency: {
    equip: 'CT-01 · 冷却塔', title: '冷却塔效率', code: 'TOWER_EFF', tone: 'green', grade: '正常', result: indicators.tower.toFixed(1), unit: '%',
    steps: [
      { name: '计算冷却温差', formula: 'ΔT = Tᵢₙ − Tₒᵤₜ', detail: `${points.TOWER1_TCWin.toFixed(1)} − ${points.TOWER1_TCWout.toFixed(1)}`, value: `${(points.TOWER1_TCWin - points.TOWER1_TCWout).toFixed(1)} ℃` },
      { name: '计算冷却塔效率', formula: 'η = (Tᵢₙ − Tₒᵤₜ) / (Tᵢₙ − Tᵢw) × 100%', detail: `(${points.TOWER1_TCWin.toFixed(1)} − ${points.TOWER1_TCWout.toFixed(1)}) / (${points.TOWER1_TCWin.toFixed(1)} − ${points.TOWER1_TWB.toFixed(1)})`, value: `${indicators.tower.toFixed(1)}%` },
    ],
    inputs: [
      { name: '冷却水进水温度', code: 'TOWER1_TCWin', value: points.TOWER1_TCWin.toFixed(1), unit: '℃' },
      { name: '冷却水出水温度', code: 'TOWER1_TCWout', value: points.TOWER1_TCWout.toFixed(1), unit: '℃' },
      { name: '室外湿球温度', code: 'TOWER1_TWB', value: points.TOWER1_TWB.toFixed(1), unit: '℃' },
    ],
  },
  pumpEfficiency: {
    equip: 'P-01 · 冷冻水泵', title: '水泵效率', code: 'PUMP_EFF', tone: 'orange', grade: '高效', result: indicators.pump.toFixed(1), unit: '%',
    steps: [
      { name: '计算水泵平均扬程', formula: 'ΔH = (Pₒᵤₜ − Pᵢₙ) / (ρ × g)', detail: `(${points.PUMP1_Pout.toFixed(0)} − ${points.PUMP1_Pin.toFixed(0)}) / (1000 × 9.8)，结果作为下一步水泵效率计算输入`, value: `${pumpHead.value.toFixed(2)} mH₂O` },
      { name: '计算水泵效率', formula: 'η = V̇ × ρ × g × (ΔH + Z) × 10⁻⁶ / (3.6 × W) × 100%', detail: `${points.PUMP1_Flow.toFixed(1)} × 1000 × 9.8 × (${pumpHead.value.toFixed(2)} + ${points.PUMP1_Z}) × 10⁻⁶ / (3.6 × ${points.PUMP1_Power}) × 100%`, value: `${indicators.pump.toFixed(1)}%` },
    ],
    inputs: [
      { name: '水泵流量', code: 'PUMP1_Flow', value: points.PUMP1_Flow.toFixed(1), unit: 'm³/h' },
      { name: '出口压力', code: 'PUMP1_Pout', value: (points.PUMP1_Pout / 1000).toFixed(0), unit: 'kPa' },
      { name: '入口压力', code: 'PUMP1_Pin', value: (points.PUMP1_Pin / 1000).toFixed(0), unit: 'kPa' },
      { name: '输入功率', code: 'PUMP1_Power', value: points.PUMP1_Power.toFixed(1), unit: 'kW' },
    ],
  },
  ahuPower: {
    equip: 'AHU-01 · 变风量空调系统', title: '单位风量耗功值', code: 'AHU_POW_EFF', tone: 'yellow', grade: '良好', result: indicators.ahu.toFixed(2), unit: ' W/(m³·h)',
    steps: [
      { name: '读取风机全压', formula: 'P = AHU1_TotalPress', detail: '送风机实时全压测点', value: `${points.AHU1_TotalPress.toFixed(0)} Pa` },
      { name: '计算单位风量耗功', formula: 'Wₛ = P / (3600 × ηₜ)', detail: `${points.AHU1_TotalPress.toFixed(0)} / (3600 × ${(points.AHU1_EtaT / 100).toFixed(3)})`, value: `${indicators.ahu.toFixed(2)} W/(m³·h)` },
    ],
    inputs: [
      { name: '风机全压', code: 'AHU1_TotalPress', value: points.AHU1_TotalPress.toFixed(0), unit: 'Pa' },
      { name: '风机总效率', code: 'AHU1_EtaT', value: points.AHU1_EtaT.toFixed(1), unit: '%' },
    ],
  },
}))

const formulaCards = computed(() => [
  { key: 'chillerCooling' as FormulaKey, group: '冷水机组', title: '制冷量计算', formula: 'Q₀ = V × ρ × c × ΔT / 3600', result: coolingCapacity.value.toFixed(1), unit: 'kW', tone: 'blue', icon: markRaw(Snowflake) },
  { key: 'chillerPower' as FormulaKey, group: '冷水机组', title: '电功率计算', formula: 'Nᵢ = PPE ｜ U × I × cosφ × √3', result: points.WCR1_PPE.toFixed(1), unit: 'kW', tone: 'blue', icon: markRaw(Activity) },
  { key: 'chillerCop' as FormulaKey, group: '冷水机组', title: 'COP 计算', formula: 'COP = Q₀ / Nᵢ', result: indicators.chiller.toFixed(2), unit: '', tone: 'blue', icon: markRaw(Calculator) },
  { key: 'towerEfficiency' as FormulaKey, group: '冷却塔', title: '冷却塔效率', formula: 'η = ΔT / (Tᵢₙ − Tᵢw)', result: indicators.tower.toFixed(1), unit: '%', tone: 'green', icon: markRaw(Waves) },
  { key: 'pumpEfficiency' as FormulaKey, group: '水泵', title: '水泵效率', formula: '先算 ΔH，再代入 η = V̇ × ρ × g × (ΔH + Z) / (3.6 × W)', result: indicators.pump.toFixed(1), unit: '%', tone: 'orange', icon: markRaw(Gauge) },
  { key: 'ahuPower' as FormulaKey, group: '风系统', title: '单位风量耗功值', formula: 'Wₛ = P / (3600 × ηₜ)', result: indicators.ahu.toFixed(2), unit: 'W/(m³·h)', tone: 'yellow', icon: markRaw(Fan) },
])

const selectedFormula = computed(() => formulaMap.value[selectedKey.value])

function openFormula(key: FormulaKey) {
  selectedKey.value = key
  formulaOpen.value = true
}

function jitter(value: number, range: number) {
  return value + (Math.random() - 0.5) * range
}

function updateMockData() {
  points.WCR1_TWin = jitter(points.WCR1_TWin, 0.12)
  points.WCR1_TWout = jitter(points.WCR1_TWout, 0.08)
  points.WCR1_Flow = jitter(points.WCR1_Flow, 0.35)
  points.WCR1_PPE = jitter(points.WCR1_PPE, 0.24)
  points.TOWER1_TCWin = jitter(points.TOWER1_TCWin, 0.1)
  points.TOWER1_TCWout = jitter(points.TOWER1_TCWout, 0.08)
  points.PUMP1_Flow = jitter(points.PUMP1_Flow, 0.28)
  points.AHU1_TotalPress = jitter(points.AHU1_TotalPress, 4)
  points.DBO_TDB = jitter(points.DBO_TDB, 0.04)
  loadRate.value = Math.max(66, Math.min(79, jitter(loadRate.value, 1.2)))

  indicators.chiller = Math.max(6.28, Math.min(6.72, jitter(indicators.chiller, 0.05)))
  indicators.tower = Math.max(70.5, Math.min(75.4, jitter(indicators.tower, 0.32)))
  indicators.pump = Math.max(79.8, Math.min(83.1, jitter(indicators.pump, 0.22)))
  indicators.ahu = Math.max(0.38, Math.min(0.46, jitter(indicators.ahu, 0.008)))

  const now = new Date()
  history.labels.push(`${String(now.getHours()).padStart(2, '0')}:${String(now.getMinutes()).padStart(2, '0')}`)
  history.labels.shift()
  history.cop.push(+indicators.chiller.toFixed(2)); history.cop.shift()
  history.tower.push(+indicators.tower.toFixed(1)); history.tower.shift()
  history.pump.push(+indicators.pump.toFixed(1)); history.pump.shift()
  updateChart()
}

function updateClock() {
  const now = new Date()
  clock.value = now.toLocaleString('zh-CN', { hour12: false }).replace(/\//g, '-')
}

function chartOption(): echarts.EChartsOption {
  return {
    animationDuration: 700,
    grid: { left: 42, right: 22, top: 38, bottom: 26 },
    tooltip: { trigger: 'axis', backgroundColor: '#101b2d', borderColor: 'rgba(255,255,255,.12)', textStyle: { color: '#dce8f8' } },
    legend: { top: 0, right: 8, textStyle: { color: '#8191a8', fontSize: 10 }, itemWidth: 14, itemHeight: 5 },
    xAxis: { type: 'category', boundaryGap: false, data: history.labels, axisLine: { lineStyle: { color: 'rgba(126,151,183,.16)' } }, axisTick: { show: false }, axisLabel: { color: '#53657d', fontSize: 10, interval: 4 } },
    yAxis: [
      { type: 'value', min: 5.8, max: 7.2, axisLabel: { color: '#53657d', fontSize: 10 }, splitLine: { lineStyle: { color: 'rgba(126,151,183,.09)' } } },
      { type: 'value', min: 60, max: 90, axisLabel: { color: '#53657d', fontSize: 10, formatter: '{value}%' }, splitLine: { show: false } },
    ],
    series: [
      { name: '冷机 COP', type: 'line', smooth: true, showSymbol: false, data: history.cop, lineStyle: { width: 2.4, color: '#42a5ff' }, areaStyle: { color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [{ offset: 0, color: 'rgba(66,165,255,.22)' }, { offset: 1, color: 'rgba(66,165,255,0)' }]) } },
      { name: '冷却塔效率', type: 'line', yAxisIndex: 1, smooth: true, showSymbol: false, data: history.tower, lineStyle: { width: 1.8, color: '#32d6a1' } },
      { name: '水泵效率', type: 'line', yAxisIndex: 1, smooth: true, showSymbol: false, data: history.pump, lineStyle: { width: 1.8, color: '#ffad52' } },
    ],
  }
}

function updateChart() {
  chart?.setOption(chartOption())
}

function resizeChart() {
  chart?.resize()
}

onMounted(async () => {
  updateClock()
  await nextTick()
  if (chartRef.value) {
    chart = echarts.init(chartRef.value)
    updateChart()
  }
  timer = window.setInterval(() => {
    updateClock()
    updateMockData()
  }, 2200)
  window.addEventListener('resize', resizeChart)
})

onBeforeUnmount(() => {
  if (timer !== null) window.clearInterval(timer)
  window.removeEventListener('resize', resizeChart)
  chart?.dispose()
})
</script>

<style scoped>
.hvac-demo {
  min-height: 100vh;
  color: #dce8f8;
  background: #06101d;
  background-image:
    linear-gradient(rgba(67, 113, 158, 0.055) 1px, transparent 1px),
    linear-gradient(90deg, rgba(67, 113, 158, 0.055) 1px, transparent 1px),
    radial-gradient(circle at 42% 30%, rgba(17, 85, 140, 0.18), transparent 32%);
  background-size: 42px 42px, 42px 42px, auto;
  overflow-x: hidden;
  position: relative;
}

.ambient { position: fixed; border-radius: 50%; filter: blur(100px); pointer-events: none; opacity: .24; }
.ambient-a { width: 420px; height: 420px; left: -180px; top: 28%; background: #0b68c7; }
.ambient-b { width: 360px; height: 360px; right: -160px; bottom: -80px; background: #087c6d; }

.topbar { height: 68px; padding: 0 28px; display: grid; grid-template-columns: 1fr auto 1fr; align-items: center; border-bottom: 1px solid rgba(150, 190, 225, .13); background: rgba(4, 13, 24, .83); backdrop-filter: blur(18px); position: sticky; top: 0; z-index: 20; }
.brand { display: flex; align-items: center; gap: 12px; }
.brand-mark { width: 38px; height: 38px; display: grid; place-items: center; color: #dff2ff; border: 1px solid rgba(86, 180, 255, .45); background: linear-gradient(145deg, rgba(43, 143, 229, .25), rgba(17, 60, 104, .2)); box-shadow: inset 0 0 20px rgba(42, 151, 241, .13), 0 0 22px rgba(35, 139, 226, .12); }
.brand-title { font-weight: 650; font-size: 15px; letter-spacing: .06em; }
.brand-subtitle { margin-top: 2px; color: #5d718a; font-size: 8px; letter-spacing: .18em; }
.header-center { display: flex; gap: 8px; align-items: center; }
.building-pill, .demo-pill { height: 30px; display: flex; align-items: center; gap: 7px; border: 1px solid rgba(143, 175, 204, .15); padding: 0 12px; font-size: 11px; color: #9db0c7; background: rgba(11, 27, 45, .7); }
.demo-pill { color: #5ce2b4; border-color: rgba(63, 223, 171, .2); background: rgba(21, 111, 86, .12); }
.header-status { justify-self: end; display: flex; align-items: center; gap: 18px; color: #778ba3; font-size: 10px; }
.status-item { display: flex; gap: 7px; align-items: center; }
.status-dot { display: inline-block; width: 7px; height: 7px; border-radius: 50%; }
.status-dot.online { background: #3fe0a8; box-shadow: 0 0 9px #3fe0a8; }
.clock { font-variant-numeric: tabular-nums; letter-spacing: .06em; }

.dashboard-shell { width: min(1640px, calc(100% - 44px)); margin: 0 auto; padding: 24px 0 30px; position: relative; z-index: 2; }
.summary-strip { display: flex; justify-content: space-between; align-items: flex-end; gap: 24px; margin-bottom: 18px; }
.eyebrow { color: #3d97e6; font-size: 9px; letter-spacing: .28em; font-weight: 700; }
h1 { margin: 5px 0 2px; font-size: clamp(22px, 2vw, 31px); line-height: 1.2; color: #eff7ff; font-weight: 560; letter-spacing: .03em; }
.summary-strip p { margin: 0; color: #62758d; font-size: 11px; letter-spacing: .06em; }
.summary-stats { display: flex; min-width: 450px; border: 1px solid rgba(140, 180, 215, .13); background: rgba(9, 24, 41, .62); }
.summary-stat { flex: 1; min-width: 140px; padding: 9px 15px; display: grid; grid-template-columns: 1fr auto; align-items: baseline; border-right: 1px solid rgba(140, 180, 215, .1); }
.summary-stat:last-child { border-right: 0; }
.summary-stat span, .summary-stat small { color: #62758c; font-size: 9px; }
.summary-stat strong { color: #dceeff; font-size: 21px; font-weight: 560; font-variant-numeric: tabular-nums; }
.summary-stat small { grid-column: 1 / -1; color: #3eae89; }

.main-grid { display: grid; grid-template-columns: minmax(0, 1fr) 316px; gap: 14px; }
.panel, .indicators { border: 1px solid rgba(137, 179, 215, .14); background: linear-gradient(145deg, rgba(10, 28, 48, .9), rgba(6, 19, 34, .88)); box-shadow: inset 0 1px 0 rgba(255,255,255,.025), 0 14px 40px rgba(0, 4, 11, .16); }
.panel-heading, .aside-heading { height: 48px; padding: 0 15px; display: flex; align-items: center; justify-content: space-between; border-bottom: 1px solid rgba(137, 179, 215, .1); }
.panel-heading > div, .aside-heading > div { display: flex; align-items: center; gap: 9px; }
.section-index { color: #318cd8; font-size: 9px; letter-spacing: .1em; }
.panel-title { color: #d6e5f5; font-size: 12px; font-weight: 600; }
.panel-subtitle { color: #4f647d; font-size: 9px; }
.legend { display: flex; gap: 14px; color: #657990; font-size: 9px; }
.legend span { display: flex; align-items: center; gap: 5px; }
.legend-line { display: inline-block; width: 16px; height: 2px; }
.legend-line.cooling { background: #36d7a7; }
.legend-line.chilled { background: #37a6ff; }
.legend-line.air { background: #e2b84e; }

.topology-panel { overflow-x: auto; }
.topology-canvas { min-width: 900px; height: 500px; position: relative; overflow: hidden; background: radial-gradient(circle at 50% 48%, rgba(34, 86, 131, .12), transparent 42%); }
.topology-canvas::after { content: ''; position: absolute; inset: 18px; border: 1px solid rgba(119, 164, 201, .045); pointer-events: none; }
.zone-label { position: absolute; top: 15px; color: #3d5269; font-size: 8px; letter-spacing: .14em; text-transform: uppercase; }
.zone-cooling { left: 7%; }
.zone-chilled { left: 44%; }
.zone-air { right: 9%; }

.device-card { position: absolute; z-index: 5; width: 155px; min-height: 118px; border: 1px solid rgba(100, 169, 219, .28); background: linear-gradient(145deg, rgba(14, 39, 64, .96), rgba(7, 24, 42, .96)); color: #dceafa; padding: 14px; text-align: left; cursor: pointer; transition: transform .22s ease, border-color .22s ease, box-shadow .22s ease; overflow: hidden; }
.device-card:hover { transform: translateY(-3px); border-color: rgba(93, 186, 255, .65); box-shadow: 0 13px 34px rgba(0, 8, 18, .35), inset 0 0 28px rgba(38, 135, 212, .09); }
.device-glow { position: absolute; width: 65px; height: 65px; border-radius: 50%; right: -20px; top: -20px; background: rgba(37, 151, 237, .16); filter: blur(14px); }
.device-icon { width: 38px; height: 38px; display: grid; place-items: center; color: #61bdff; border: 1px solid rgba(86, 183, 255, .24); background: rgba(29, 115, 183, .12); float: left; margin-right: 10px; }
.device-meta { display: flex; flex-direction: column; min-width: 0; padding-top: 1px; }
.device-meta b { font-size: 10px; letter-spacing: .13em; color: #7b95ae; }
.device-meta em { font-size: 12px; font-style: normal; font-weight: 550; color: #e1effc; white-space: nowrap; }
.device-value { display: block; clear: both; padding-top: 11px; font-size: 10px; color: #85a0b8; font-variant-numeric: tabular-nums; white-space: nowrap; }
.device-value b { color: #5f7b94; font-size: 8px; font-weight: 500; }
.device-state { display: flex; align-items: center; gap: 6px; margin-top: 7px; color: #41d9a5; font-size: 9px; }
.device-state i { width: 5px; height: 5px; background: #3ce0a9; border-radius: 50%; box-shadow: 0 0 8px #3ce0a9; }
.tower { left: 5%; top: 90px; width: 180px; border-color: rgba(54, 216, 168, .3); }
.tower .device-icon { color: #45ddb0; border-color: rgba(69, 221, 176, .25); background: rgba(30, 128, 100, .12); }
.tower .device-glow { background: rgba(43, 203, 155, .17); }
.chiller { left: 34%; top: 90px; width: 190px; }
.pump { left: 55%; top: 261px; width: 160px; border-color: rgba(255, 168, 82, .3); }
.pump .device-icon { color: #ffae5f; border-color: rgba(255, 174, 95, .25); background: rgba(151, 89, 27, .12); }
.pump .device-glow { background: rgba(239, 135, 42, .16); }
.ahu { right: 5%; top: 90px; width: 190px; border-color: rgba(233, 194, 82, .32); }
.ahu .device-icon { color: #f0ca64; border-color: rgba(240, 202, 100, .25); background: rgba(153, 122, 30, .12); }
.ahu .device-glow { background: rgba(231, 190, 66, .16); }
.fan-icon :deep(svg) { animation: rotateFan 3s linear infinite; }

.vav-terminal { position: absolute; right: 5%; bottom: 30px; z-index: 5; width: 190px; height: 58px; padding: 10px 12px; display: flex; align-items: center; gap: 9px; border: 1px solid rgba(224, 185, 73, .2); background: rgba(18, 30, 41, .96); }
.vav-icon { color: #dbb951; }
.vav-terminal div:nth-child(2) { display: flex; flex-direction: column; }
.vav-terminal b { color: #cedae7; font-size: 10px; }
.vav-terminal span { color: #5c7087; font-size: 8px; }
.air-dots { display: flex; gap: 3px; margin-left: auto; }
.air-dots i { width: 3px; height: 3px; border-radius: 50%; background: #e1bd55; animation: airPulse 1.2s ease-in-out infinite; }
.air-dots i:nth-child(2) { animation-delay: .2s; }
.air-dots i:nth-child(3) { animation-delay: .4s; }

.pipe { position: absolute; z-index: 1; overflow: hidden; background: rgba(62, 129, 183, .12); }
.pipe-h { height: 5px; }
.pipe-v { width: 5px; }
.pipe::before { content: ''; position: absolute; inset: 1px; background: currentColor; opacity: .32; }
.pipe span { position: absolute; width: 28px; height: 100%; background: linear-gradient(90deg, transparent, currentColor, transparent); filter: drop-shadow(0 0 5px currentColor); animation: flowH 2.1s linear infinite; }
.pipe-v span { width: 100%; height: 28px; background: linear-gradient(180deg, transparent, currentColor, transparent); animation-name: flowV; }
.pipe.reverse span { animation-direction: reverse; }
.cooling-pipe { color: #34d6a4; }
.chilled-pipe { color: #3da8ff; }
.air-pipe { color: #e0b947; }
.pipe-cooling-supply { left: calc(5% + 180px); top: 121px; width: calc(29% - 180px); }
.pipe-cooling-return { left: calc(5% + 180px); top: 169px; width: calc(29% - 180px); }
.pipe-chilled-supply { left: calc(34% + 72px); top: 319px; width: calc(61% - 234px); }
.pipe-chilled-return { left: calc(34% + 118px); top: 392px; width: calc(61% - 242px); }
.pipe-chilled-drop { left: calc(34% + 70px); top: 208px; height: 114px; }
.pipe-chilled-return-drop { left: calc(34% + 116px); top: 208px; height: 187px; }
.pipe-chilled-rise { left: calc(95% - 164px); top: 208px; height: 114px; }
.pipe-chilled-return-rise { left: calc(95% - 126px); top: 208px; height: 187px; }
.pipe-air-loop { left: calc(95% - 68px); top: 208px; height: 205px; }
.pipe-air-return-loop { left: calc(95% - 38px); top: 208px; height: 205px; }

.flow-label { position: absolute; z-index: 7; color: #7197b5; font-size: 9px; line-height: 1; white-space: nowrap; background: rgba(8, 23, 40, .96); border: 1px solid rgba(94, 151, 194, .15); padding: 5px 8px; box-shadow: 0 4px 12px rgba(0, 7, 15, .22); }
.flow-label b { color: #7cbced; font-weight: 500; }
.label-supply, .label-return { left: calc(34% + 124px); width: 58px; box-sizing: border-box; padding: 5px 4px; line-height: 1.35; text-align: center; white-space: normal; }
.label-supply { top: 282px; }
.label-return { top: 402px; }
.outdoor-chip { position: absolute; left: 5.5%; bottom: 32px; display: flex; gap: 7px; align-items: center; color: #7790a8; font-size: 9px; border: 1px solid rgba(133, 168, 197, .12); background: rgba(7, 20, 34, .8); padding: 8px 10px; }

.indicators { padding-bottom: 11px; }
.aside-heading { margin-bottom: 9px; }
.minute-tag { color: #597089; font-size: 8px; }
.indicator-card { position: relative; width: calc(100% - 20px); height: 72px; margin: 0 10px 8px; padding: 10px 12px; display: flex; align-items: center; gap: 10px; border: 1px solid rgba(127, 167, 201, .12); background: rgba(7, 21, 37, .72); color: #dce8f5; text-align: left; cursor: pointer; overflow: hidden; transition: border-color .2s ease, transform .2s ease; }
.indicator-card:hover { transform: translateX(-3px); border-color: rgba(80, 168, 235, .35); }
.indicator-icon { width: 34px; height: 34px; display: grid; place-items: center; color: var(--tone); background: color-mix(in srgb, var(--tone) 10%, transparent); border: 1px solid color-mix(in srgb, var(--tone) 25%, transparent); }
.indicator-main { display: flex; flex-direction: column; flex: 1; }
.indicator-label { color: #70859b; font-size: 9px; }
.indicator-number { line-height: 1.1; color: #edf6ff; font-size: 23px; font-weight: 560; font-variant-numeric: tabular-nums; }
.indicator-number small { font-size: 8px; color: #6d8298; font-weight: 400; margin-left: 4px; }
.indicator-side { display: flex; flex-direction: column; align-items: flex-end; gap: 5px; }
.grade { color: var(--tone); font-size: 9px; }
.delta { display: flex; gap: 2px; align-items: center; color: #4bbf98; font-size: 8px; }
.indicator-progress { position: absolute; bottom: 0; left: 0; right: 0; height: 2px; background: rgba(255,255,255,.025); }
.indicator-progress i { display: block; height: 100%; background: var(--tone); box-shadow: 0 0 10px var(--tone); opacity: .72; }
.indicator-card.blue { --tone: #42a5ff; }
.indicator-card.green { --tone: #37d4a2; }
.indicator-card.orange { --tone: #ffab55; }
.indicator-card.yellow { --tone: #e6c45d; }
.quality-card { margin: 12px 10px 0; padding: 12px; border: 1px solid rgba(127, 167, 201, .1); background: rgba(5, 17, 30, .6); }
.quality-head { display: flex; justify-content: space-between; color: #778ca4; font-size: 9px; }
.quality-head strong { color: #4fdaa9; font-size: 12px; }
.quality-bar { height: 3px; margin: 8px 0 10px; background: rgba(91, 127, 154, .16); }
.quality-bar i { display: block; width: 98.6%; height: 100%; background: linear-gradient(90deg, #2582c7, #42d5a3); }
.quality-items { display: flex; flex-wrap: wrap; gap: 9px; color: #566b82; font-size: 7px; }
.quality-items span { display: flex; gap: 4px; align-items: center; }
.quality-items i { width: 4px; height: 4px; border-radius: 50%; }
.q-real { background: #42d6a5; }.q-fill { background: #e6bf51; }.q-default { background: #8796a8; }

.formula-catalog { margin-top: 14px; }
.formula-catalog-heading { height: 52px; }
.formula-count { display: flex; align-items: baseline; gap: 5px; color: #61768d; font-size: 9px; }
.formula-count strong { color: #65bcf7; font-size: 19px; font-weight: 560; }
.formula-count em { margin-left: 9px; padding-left: 12px; border-left: 1px solid rgba(125, 166, 200, .14); color: #586a7d; font-size: 8px; font-style: normal; }
.formula-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 9px; padding: 12px; }
.formula-card { --tone: #42a5ff; position: relative; min-width: 0; min-height: 94px; padding: 13px 42px 12px 13px; display: grid; grid-template-columns: 32px minmax(0, 1fr) auto; grid-template-rows: 1fr auto; align-items: center; column-gap: 10px; border: 1px solid rgba(127, 167, 201, .12); background: linear-gradient(135deg, color-mix(in srgb, var(--tone) 7%, transparent), rgba(5, 17, 30, .72)); color: #dce8f5; text-align: left; cursor: pointer; overflow: hidden; transition: transform .2s ease, border-color .2s ease, background .2s ease; }
.formula-card:hover { transform: translateY(-2px); border-color: color-mix(in srgb, var(--tone) 45%, transparent); background: linear-gradient(135deg, color-mix(in srgb, var(--tone) 12%, transparent), rgba(5, 17, 30, .82)); }
.formula-card.green { --tone: #37d4a2; }.formula-card.orange { --tone: #ffab55; }.formula-card.yellow { --tone: #e6c45d; }
.formula-card-icon { grid-row: 1 / 3; width: 32px; height: 32px; display: grid; place-items: center; color: var(--tone); border: 1px solid color-mix(in srgb, var(--tone) 25%, transparent); background: color-mix(in srgb, var(--tone) 9%, transparent); }
.formula-card-main { min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.formula-card-main small { color: #536b82; font-size: 8px; }
.formula-card-main b { color: #cfdfed; font-size: 11px; font-weight: 560; }
.formula-card-main code { overflow: hidden; color: #6591b2; font: 8px/1.4 'Cascadia Code', Consolas, monospace; text-overflow: ellipsis; white-space: nowrap; }
.formula-card-result { grid-column: 2 / 4; align-self: end; display: flex; align-items: baseline; gap: 5px; margin-top: 5px; }
.formula-card-result strong { color: #ecf6ff; font-size: 17px; line-height: 1; font-weight: 540; font-variant-numeric: tabular-nums; }
.formula-card-result small { color: #61768c; font-size: 7px; }
.formula-card-arrow { position: absolute; right: 11px; bottom: 13px; color: color-mix(in srgb, var(--tone) 65%, transparent); }

.bottom-grid { display: grid; grid-template-columns: minmax(0, 1fr) 370px; gap: 14px; margin-top: 14px; }
.panel-heading.compact { height: 42px; }
.trend-chart { height: 190px; }
.trend-tabs { display: flex; gap: 2px; }
.trend-tabs button, .text-button { border: 0; background: transparent; color: #526a82; font-size: 8px; cursor: pointer; padding: 4px 7px; }
.trend-tabs button.active { color: #61b9fa; background: rgba(45, 142, 214, .1); }
.text-button { display: flex; align-items: center; gap: 2px; color: #5e7890; }
.point-list { padding: 7px 13px 11px; }
.point-row { min-height: 38px; display: grid; grid-template-columns: 14px 1fr auto 42px; align-items: center; gap: 6px; border-bottom: 1px solid rgba(129, 169, 202, .08); }
.point-row:last-child { border-bottom: 0; }
.point-status { width: 5px; height: 5px; border-radius: 50%; background: #3cd7a4; box-shadow: 0 0 7px rgba(60, 215, 164, .65); }
.point-name { display: flex; flex-direction: column; }
.point-name b { font-size: 9px; color: #9badbf; font-weight: 500; }
.point-name small { font-size: 7px; color: #40566d; }
.point-value { color: #d2e1ed; font-size: 12px; font-variant-numeric: tabular-nums; }
.point-value small { color: #566b80; font-size: 7px; }
.point-quality { color: #3db78f; font-size: 7px; text-align: right; }

:global(.formula-drawer .ant-drawer-content) { background: #081523 !important; border-left: 1px solid rgba(107, 166, 212, .18); }
:global(.formula-drawer .ant-drawer-header) { background: #081523 !important; border-bottom-color: rgba(107, 166, 212, .12) !important; padding: 17px 20px !important; }
:global(.formula-drawer .ant-drawer-body) { padding: 18px !important; }
.drawer-title { display: flex; justify-content: space-between; align-items: center; width: 100%; }
.drawer-title > div { display: flex; flex-direction: column; }
.drawer-title span { color: #e2eef9; font-size: 14px; }
.drawer-title small { color: #586f86; font-size: 9px; font-weight: 400; }
.drawer-title button { width: 30px; height: 30px; display: grid; place-items: center; border: 1px solid rgba(136, 174, 204, .12); background: rgba(255,255,255,.02); color: #758aa0; cursor: pointer; }
.result-hero { --tone: #42a5ff; min-height: 112px; padding: 17px; display: grid; grid-template-columns: 1fr auto; border: 1px solid color-mix(in srgb, var(--tone) 28%, transparent); background: linear-gradient(135deg, color-mix(in srgb, var(--tone) 12%, transparent), rgba(6, 20, 34, .92)); }
.result-hero.green { --tone: #36d4a2; }.result-hero.orange { --tone: #ffab55; }.result-hero.yellow { --tone: #e4c258; }
.result-hero > div:first-child { display: flex; flex-direction: column; }
.result-hero span { color: #9db2c6; font-size: 11px; }
.result-hero small { color: #4e687f; font-size: 8px; letter-spacing: .12em; }
.result-hero > strong { color: #eef8ff; font-size: 40px; line-height: 1; font-weight: 540; font-variant-numeric: tabular-nums; }
.result-hero strong em { color: #71889f; font-size: 9px; font-style: normal; font-weight: 400; margin-left: 5px; }
.result-grade { grid-column: 1 / -1; align-self: end; display: flex; gap: 7px; align-items: center; color: var(--tone); font-size: 9px; }
.formula-section { margin-top: 20px; }
.formula-section-title { display: flex; gap: 7px; align-items: center; color: #9fb2c4; font-size: 11px; margin-bottom: 10px; }
.formula-step { display: grid; grid-template-columns: 28px 1fr auto; gap: 9px; padding: 12px 0; border-bottom: 1px solid rgba(130, 169, 202, .09); }
.step-no { color: #367db2; font-size: 9px; padding-top: 2px; }
.formula-step > div { display: flex; flex-direction: column; gap: 4px; }
.formula-step b { color: #c5d4e1; font-size: 10px; font-weight: 550; }
.formula-step code { color: #62b8f3; font-size: 10px; font-family: 'Cascadia Code', Consolas, monospace; }
.formula-step p { margin: 0; color: #536b81; font-size: 8px; }
.formula-step > strong { color: #dbeaf7; font-size: 11px; font-weight: 520; white-space: nowrap; }
.input-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
.input-card { padding: 11px; display: flex; flex-direction: column; border: 1px solid rgba(128, 168, 200, .1); background: rgba(5, 17, 29, .65); }
.input-card span { color: #647b91; font-size: 8px; }
.input-card b { color: #d7e5f1; font-size: 15px; font-weight: 520; }
.input-card b small { color: #64798f; font-size: 8px; }
.input-card em { color: #3c9679; font-size: 7px; font-style: normal; }
.calc-note { margin-top: 18px; padding: 11px; display: flex; gap: 8px; color: #60778d; font-size: 8px; line-height: 1.7; border: 1px solid rgba(117, 159, 192, .1); background: rgba(23, 65, 97, .1); }

@keyframes flowH { from { left: -30px; } to { left: 100%; } }
@keyframes flowV { from { top: -30px; } to { top: 100%; } }
@keyframes rotateFan { to { transform: rotate(360deg); } }
@keyframes airPulse { 0%, 100% { opacity: .2; transform: translateX(0); } 50% { opacity: 1; transform: translateX(3px); } }

@media (max-width: 1450px) {
  .topbar { grid-template-columns: 1fr auto; }
  .header-center { display: none; }
  .main-grid { grid-template-columns: 1fr; }
  .indicators { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; padding: 10px; }
  .aside-heading, .quality-card { grid-column: 1 / -1; }
  .indicator-card { width: 100%; margin: 0; }
}

@media (max-width: 900px) {
  .indicators { grid-template-columns: repeat(2, 1fr); }
  .formula-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}

@media (max-width: 820px) {
  .topbar { height: auto; min-height: 64px; padding: 10px 16px; }
  .header-status .status-item { display: none; }
  .dashboard-shell { width: calc(100% - 24px); padding-top: 16px; }
  .summary-strip { align-items: flex-start; flex-direction: column; }
  .summary-stats { min-width: 0; width: 100%; overflow-x: auto; }
  .topology-canvas { min-width: 900px; }
  .topology-panel { overflow-x: auto; }
  .bottom-grid { grid-template-columns: 1fr; }
  .legend { display: none; }
  .formula-catalog-heading { height: auto; min-height: 52px; align-items: flex-start; gap: 8px; padding-top: 10px; padding-bottom: 10px; }
  .formula-count em { display: none; }
}

@media (max-width: 560px) {
  .formula-grid { grid-template-columns: 1fr; }
}
</style>
