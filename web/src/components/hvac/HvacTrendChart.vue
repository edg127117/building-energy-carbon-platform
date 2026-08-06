<template>
  <!--
    同一工程单位只使用一个纵轴；数值、质量和缺口均来自历史领域适配层。
    本组件只管理 ECharts 生命周期，不发请求、不访问 localStorage，也不计算业务指标。
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
    <div
      ref="chartElement"
      class="trend-chart__canvas"
      role="img"
      :aria-label="`${groupTitle}历史趋势图，${seriesSummary}`"
    />
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
  TooltipComponent,
  LegendComponent,
  DataZoomComponent,
  CanvasRenderer,
])

const props = defineProps<{
  group: HvacTrendGroup
  from: number
  to: number
  resolutionMinutes: number
}>()

const chartElement = ref<HTMLElement | null>(null)
const titleId = `hvac-trend-${getCurrentInstance()?.uid ?? 'chart'}`
const groupTitle = computed(() =>
  props.group.unit ? `单位：${props.group.unit}` : '无量纲',
)
const seriesSummary = computed(() => {
  const names = props.group.series.map((series) => series.label).join('、')
  return `包含 ${props.group.series.length} 条真实曲线：${names}`
})

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

/** Props 变化只替换图表数据与范围，保留当前 DOM 上的 ECharts 实例。 */
watch(
  () => [props.group, props.from, props.to, props.resolutionMinutes],
  () => updateChart(),
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
 * 构建同单位折线配置：Q0 不显示普通点，Q1/Q2 使用可辨识符号，空值永不连接。
 */
function buildOption(): EChartsCoreOption {
  const reducedMotion = typeof window.matchMedia === 'function'
    && window.matchMedia('(prefers-reduced-motion: reduce)').matches
  return {
    animation: !reducedMotion,
    animationDuration: reducedMotion ? 0 : 260,
    color: ['#55b9ff', '#42d6a5', '#ffad5c', '#e9c85f', '#ab8cff', '#56d5d8'],
    grid: { left: 54, right: 24, top: 48, bottom: 72, containLabel: false },
    legend: {
      top: 4,
      left: 4,
      textStyle: { color: '#9fb4c9', fontSize: 11 },
      itemWidth: 18,
      itemHeight: 3,
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
      nameTextStyle: { color: '#71889f', padding: [0, 0, 8, 0] },
      axisLabel: { color: '#71889f' },
      axisLine: { show: false },
      splitLine: { lineStyle: { color: 'rgba(126, 166, 198, 0.10)' } },
      scale: true,
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
    data: series.points.map((point) => ({
      value: [point.time, point.average],
      symbol: qualitySymbol(point),
      symbolSize: point.dataQuality === 2 ? 9 : 7,
      trendPoint: point,
      trendSeries: series,
    })),
  }
}

/** Q1 使用圆点、Q2 使用菱形；Q0 与缺口不显示普通标记，避免高密度图表过载。 */
function qualitySymbol(point: HvacTrendPoint): string {
  if (point.average === null) return 'none'
  if (point.dataQuality === 1) return 'circle'
  if (point.dataQuality === 2) return 'diamond'
  return 'none'
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

.trend-chart__canvas {
  width: 100%;
  min-height: 300px;
  margin-top: 6px;
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
