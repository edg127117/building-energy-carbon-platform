<template>
  <!--
    同一工程单位只使用一个纵轴；数值、质量和缺口均来自历史领域适配层。
    本组件管理 ECharts 生命周期、当前图的曲线显隐、纵轴单位和空状态，
    不发请求、不访问 localStorage，也不计算业务指标。
  -->
  <section class="trend-chart" :aria-labelledby="titleId">
    <header class="trend-chart__heading">
      <div>
        <h3 :id="titleId">{{ groupTitle }}</h3>
        <p>{{ seriesSummary }}</p>
      </div>
      <div class="trend-chart__quality" aria-label="数据质量图例">
        <span><i class="quality-dot quality-q0" />Q0 真实数据</span>
        <span><i class="quality-dot quality-q1" />Q1 线性插值</span>
        <span><i class="quality-diamond quality-q2" />Q2 典型值</span>
      </div>
    </header>
    <p class="trend-chart__note">
      {{ resolutionMinutes }} 分钟分辨率 · 缺口保持断线 · 拖动底部范围条查看局部时段
    </p>
    <div class="trend-chart__series-legend" aria-label="趋势序列图例">
      <button
        v-for="(series, index) in group.series"
        :key="series.id"
        type="button"
        class="trend-chart__series-item"
        :class="{ 'trend-chart__series-item--hidden': !isSeriesVisible(series.id) }"
        :aria-pressed="isSeriesVisible(series.id)"
        @click="toggleSeries(series.id)"
      >
        <i
          class="trend-chart__series-swatch"
          :style="{ backgroundColor: seriesColor(index) }"
        />
        {{ series.label }}
      </button>
    </div>
    <div class="trend-chart__plot">
      <p v-if="!hasFiniteGroupValue" class="trend-chart__empty" role="status">
        当前时段无有效数据
      </p>
      <div
        ref="chartElement"
        class="trend-chart__canvas"
        role="img"
        :aria-label="`${groupTitle}历史趋势图，${seriesSummary}`"
      />
    </div>
  </section>
</template>

<script setup lang="ts">
import {
  computed,
  getCurrentInstance,
  onBeforeUnmount,
  onMounted,
  ref,
  watch,
} from 'vue'
import * as echarts from 'echarts/core'
import { LineChart } from 'echarts/charts'
import {
  DataZoomComponent,
  GridComponent,
  LegendComponent,
  TooltipComponent,
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import type { EChartsCoreOption } from 'echarts/core'
import {
  historyQualityLabel,
  type HvacTrendGroup,
  type HvacTrendPoint,
  type HvacTrendSeries,
} from '@/domain/hvacHistoryTrends'

echarts.use([
  LineChart,
  GridComponent,
  LegendComponent,
  TooltipComponent,
  DataZoomComponent,
  CanvasRenderer,
])

const SERIES_COLORS = [
  '#55b9ff',
  '#42d6a5',
  '#ffad5c',
  '#e9c85f',
  '#ab8cff',
  '#56d5d8',
]

const props = defineProps<{
  group: HvacTrendGroup
  from: number
  to: number
  resolutionMinutes: number
}>()

const chartElement = ref<HTMLElement | null>(null)
const hiddenSeriesIds = ref<Set<string>>(new Set())
const titleId = `hvac-trend-${getCurrentInstance()?.uid ?? 'chart'}`
const groupTitle = computed(() =>
  props.group.unit ? `单位：${props.group.unit}` : '无量纲',
)
const seriesSummary = computed(() => {
  const names = props.group.series.map((series) => series.label).join('、')
  return `包含 ${props.group.series.length} 条真实曲线：${names}`
})
const hasFiniteGroupValue = computed(() => props.group.series.some((series) =>
  series.points.some((point) => isFiniteAverage(point)),
))

let chart: ReturnType<typeof echarts.init> | null = null
let resizeObserver: ResizeObserver | null = null

/** 初始化一个图表实例并监听容器尺寸；页面布局变化只调用 resize，不重新创建实例。 */
onMounted(() => {
  if (!chartElement.value) return
  chart = echarts.init(chartElement.value)
  updateChart()
  resizeObserver = new ResizeObserver(() => chart?.resize())
  resizeObserver.observe(chartElement.value)
})

/** Props 变化时清理已不属于当前组的隐藏 ID，再复用现有 ECharts 实例替换数据与范围。 */
watch(
  () => [props.group, props.from, props.to, props.resolutionMinutes],
  () => {
    const currentIds = new Set(props.group.series.map((series) => series.id))
    hiddenSeriesIds.value = new Set(
      [...hiddenSeriesIds.value].filter((seriesId) => currentIds.has(seriesId)),
    )
    updateChart()
  },
  { deep: true },
)

/** 卸载时释放观察器和 Canvas 事件，防止切换建筑或离开页面后残留监听。 */
onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  resizeObserver = null
  chart?.dispose()
  chart = null
})

/** 使用完整新配置替换图表，避免旧建筑系列、图例或时间范围残留。 */
function updateChart(): void {
  if (!chart) return
  chart.setOption(buildOption(), true)
}

/**
 * 构建同单位折线配置：隐藏图例只承载按钮选择状态，单位由纵轴展示；
 * 连续 Q0 依靠线段显示，孤立 Q0 与 Q1/Q2 使用可辨识符号，空值永不连接。
 */
function buildOption(): EChartsCoreOption {
  const reducedMotion = typeof window.matchMedia === 'function'
    && window.matchMedia('(prefers-reduced-motion: reduce)').matches
  const axisExtent = singleValueAxisExtent(props.group)
  return {
    animation: !reducedMotion,
    animationDuration: reducedMotion ? 0 : 260,
    color: SERIES_COLORS,
    grid: { left: 24, right: 24, top: 38, bottom: 72, containLabel: true },
    legend: {
      show: false,
      selected: Object.fromEntries(props.group.series.map((series) => [
        series.label,
        isSeriesVisible(series.id),
      ])),
    },
    tooltip: {
      trigger: 'axis',
      confine: true,
      backgroundColor: 'rgba(5, 17, 30, 0.96)',
      borderColor: 'rgba(104, 172, 224, 0.28)',
      textStyle: { color: '#dce9f6', fontSize: 12 },
      formatter: formatTooltip,
    },
    xAxis: {
      type: 'time',
      min: props.from,
      max: props.to,
      boundaryGap: false,
      axisLine: { lineStyle: { color: 'rgba(126, 166, 198, 0.28)' } },
      axisLabel: { color: '#71889f', hideOverlap: true },
      splitLine: { show: false },
    },
    yAxis: {
      type: 'value',
      name: props.group.unit || '无量纲',
      nameLocation: 'end',
      nameGap: 12,
      nameTextStyle: { color: '#71889f', fontSize: 11 },
      axisLabel: { color: '#71889f' },
      axisLine: { show: false },
      splitLine: { lineStyle: { color: 'rgba(126, 166, 198, 0.10)' } },
      scale: true,
      ...axisExtent,
    },
    dataZoom: [
      { type: 'inside', filterMode: 'none' },
      {
        type: 'slider',
        bottom: 18,
        height: 18,
        borderColor: 'rgba(126, 166, 198, 0.16)',
        backgroundColor: 'rgba(5, 17, 30, 0.62)',
        fillerColor: 'rgba(66, 165, 255, 0.16)',
        handleStyle: { color: '#55b9ff', borderColor: '#55b9ff' },
        textStyle: { color: '#71889f' },
      },
    ],
    series: props.group.series.map(buildSeries),
  }
}

/** 单一有效值以自身和展示精度生成留白；多值继续交给 ECharts 自动缩放。 */
function singleValueAxisExtent(
  group: HvacTrendGroup,
): { min?: number; max?: number } {
  const values = group.series.flatMap((series) => series.points.flatMap((point) =>
    isFiniteAverage(point) ? [point.average] : [],
  ))
  if (values.length === 0) return {}
  const minimum = Math.min(...values)
  const maximum = Math.max(...values)
  if (minimum !== maximum) return {}
  const maximumPrecision = Math.max(0, ...group.series.map((series) => series.precision))
  const displayStep = 10 ** -maximumPrecision
  const padding = Math.max(Math.abs(minimum) * 0.1, displayStep * 2)
  return { min: minimum - padding, max: maximum + padding }
}

/** 画布外图例与 ECharts 折线共用同一色板，保证换行后颜色含义仍稳定。 */
function seriesColor(index: number): string {
  return SERIES_COLORS[index % SERIES_COLORS.length]!
}

/** 图例按钮状态只控制当前单位图内的 ECharts 系列，不改变后端数据或其他单位图。 */
function isSeriesVisible(seriesId: string): boolean {
  return !hiddenSeriesIds.value.has(seriesId)
}

/** 独立切换一条曲线并立即刷新隐藏图例选择，允许同单位图只保留任意一条序列。 */
function toggleSeries(seriesId: string): void {
  const next = new Set(hiddenSeriesIds.value)
  if (next.has(seriesId)) next.delete(seriesId)
  else next.add(seriesId)
  hiddenSeriesIds.value = next
  updateChart()
}

/** 把统一趋势序列映射为 ECharts 数据项，并把 Tooltip 所需的后端窗口事实就近保留。 */
function buildSeries(series: HvacTrendSeries) {
  return {
    id: series.id,
    name: series.label,
    type: 'line',
    showSymbol: true,
    connectNulls: false,
    smooth: false,
    symbolSize: 7,
    lineStyle: { width: 1.8 },
    emphasis: { focus: 'series' },
    data: series.points.map((point, index, points) => ({
      value: [point.time, point.average],
      symbol: qualitySymbol(point, index, points),
      symbolSize: point.dataQuality === 2 ? 9 : 7,
      trendPoint: point,
      trendSeries: series,
    })),
  }
}

/** 有限平均值才能形成折线；空分隔点和异常数字均视为不可连接。 */
function isFiniteAverage(
  point: HvacTrendPoint | undefined,
): point is HvacTrendPoint & { average: number } {
  return typeof point?.average === 'number' && Number.isFinite(point.average)
}

/** Q1/Q2 始终标记；Q0 仅在两侧都没有有效线段时显示圆点。 */
function qualitySymbol(
  point: HvacTrendPoint,
  index: number,
  points: HvacTrendPoint[],
): string {
  if (!isFiniteAverage(point)) return 'none'
  if (point.dataQuality === 1) return 'circle'
  if (point.dataQuality === 2) return 'diamond'
  if (point.dataQuality !== 0) return 'none'
  return isFiniteAverage(points[index - 1]) || isFiniteAverage(points[index + 1])
    ? 'none'
    : 'circle'
}

type TooltipDatum = {
  marker?: string
  data?: {
    trendPoint?: HvacTrendPoint
    trendSeries?: HvacTrendSeries
  }
}

/** Tooltip 只格式化适配层提供的平均、极值、样本和质量，不在 Canvas 内推导新业务值。 */
function formatTooltip(rawParams: unknown): string {
  const params = (Array.isArray(rawParams) ? rawParams : [rawParams])
    .filter(Boolean) as TooltipDatum[]
  const firstPoint = params[0]?.data?.trendPoint
  const header = firstPoint
    ? `<strong>${escapeHtml(formatTime(firstPoint.time))}</strong>`
    : '<strong>历史趋势</strong>'
  const rows = params.flatMap((param) => {
    const point = param.data?.trendPoint
    const series = param.data?.trendSeries
    if (!point || !series || point.average === null) return []
    const unit = series.unit ? ` ${escapeHtml(series.unit)}` : ''
    return [`
      <div style="margin-top:8px">
        ${param.marker ?? ''}<b>${escapeHtml(series.label)}</b><br />
        平均值 ${formatValue(point.average, series.precision)}${unit}<br />
        最小值 ${formatValue(point.minimum, series.precision)} ·
        最大值 ${formatValue(point.maximum, series.precision)}<br />
        样本数 ${point.sampleCount} · ${escapeHtml(historyQualityLabel(point.dataQuality))}
      </div>
    `]
  })
  return [header, ...rows].join('')
}

/** 按冻结精度格式化 Tooltip 数值；空极值保持明确占位，不补零。 */
function formatValue(value: number | null, precision: number): string {
  return value === null ? '--' : value.toFixed(precision)
}

/** 图表时间使用浏览器本地时区，和自定义范围控件的时间语义保持一致。 */
function formatTime(time: number): string {
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(new Date(time))
}

/** 转义来自后端的测点名称和单位，避免 HTML Tooltip 把业务文本解释成标签。 */
function escapeHtml(value: string): string {
  return value.replace(/[&<>'"]/g, (character) => ({
    '&': '&amp;',
    '<': '&lt;',
    '>': '&gt;',
    "'": '&#39;',
    '"': '&quot;',
  })[character] ?? character)
}
</script>

<style scoped>
.trend-chart {
  min-width: 0;
  padding: 18px 0 8px;
  border-top: 1px solid rgba(127, 167, 201, 0.12);
}

.trend-chart:first-child {
  border-top: 0;
}

.trend-chart__heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 18px;
  padding: 0 16px;
}

.trend-chart__heading h3 {
  margin: 0;
  color: #dce9f6;
  font-size: 14px;
  font-weight: 650;
}

.trend-chart__heading p,
.trend-chart__note {
  margin: 5px 0 0;
  color: #7890a7;
  font-size: 11px;
  line-height: 1.55;
}

.trend-chart__quality {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 10px;
  color: #8ba1b6;
  font-size: 10px;
}

.trend-chart__quality span {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.quality-dot,
.quality-diamond {
  width: 7px;
  height: 7px;
  display: inline-block;
}

.quality-dot {
  border-radius: 50%;
}

.quality-diamond {
  transform: rotate(45deg);
}

.quality-q0 { background: #55b9ff; }
.quality-q1 { background: #e7b14f; }
.quality-q2 { background: #ff806f; }

.trend-chart__note {
  padding: 0 16px;
}

.trend-chart__series-legend {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 10px;
  padding: 10px 16px 0;
}

.trend-chart__series-item {
  min-height: 36px;
  display: inline-flex;
  align-items: center;
  gap: 7px;
  min-width: 0;
  padding: 7px 10px;
  border: 1px solid rgba(126, 166, 198, 0.22);
  border-radius: 8px;
  background: rgba(12, 32, 51, 0.72);
  color: #b7cadb;
  font: inherit;
  font-size: 12px;
  line-height: 1.4;
  cursor: pointer;
  transition:
    border-color 160ms ease-out,
    background-color 160ms ease-out,
    color 160ms ease-out;
}

.trend-chart__series-item:hover {
  border-color: rgba(85, 185, 255, 0.55);
  background: rgba(19, 48, 73, 0.78);
}

.trend-chart__series-item:focus-visible {
  outline: 2px solid #72c4ff;
  outline-offset: 2px;
}

.trend-chart__series-item--hidden {
  border-color: rgba(126, 166, 198, 0.12);
  background: rgba(8, 23, 39, 0.50);
  color: #71889f;
}

.trend-chart__series-swatch {
  width: 18px;
  height: 3px;
  flex: 0 0 auto;
  border-radius: 2px;
}

.trend-chart__series-item--hidden .trend-chart__series-swatch {
  opacity: 0.34;
}

.trend-chart__plot {
  position: relative;
}

.trend-chart__empty {
  position: absolute;
  z-index: 1;
  inset: 38px 24px 72px;
  display: grid;
  place-items: center;
  margin: 0;
  color: #9fb4c9;
  font-size: 13px;
  pointer-events: none;
}

.trend-chart__canvas {
  width: 100%;
  min-height: 300px;
  margin-top: 0;
}

@media (max-width: 760px) {
  .trend-chart__heading {
    flex-direction: column;
    gap: 10px;
  }

  .trend-chart__quality {
    justify-content: flex-start;
  }

  .trend-chart__canvas {
    min-height: 280px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .quality-diamond {
    transform: rotate(45deg);
  }
}
</style>
