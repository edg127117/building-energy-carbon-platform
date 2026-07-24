<template>
  <div class="h-full w-full">
    <div class="mb-3 flex items-center justify-between">
      <div class="text-sm font-semibold text-zinc-100">{{ title }}</div>
      <div class="text-xs text-zinc-500">{{ subtitle }}</div>
    </div>
    <div ref="el" class="h-[260px] w-full rounded-xl border border-white/10 bg-black/20" />
  </div>
</template>

<script setup lang="ts">
import * as echarts from 'echarts'
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'

type SeriesLine = {
  name: string
  color: string
  values: Array<{ t: number; v: number | null }>
}

const props = defineProps<{
  title: string
  subtitle?: string
  series: SeriesLine[]
  /** 自定义 X 轴起始时间（毫秒），不传则默认最近 15 分钟 */
  xMin?: number
  /** 自定义 X 轴结束时间（毫秒），不传则默认当前时间 */
  xMax?: number
}>()

const el = ref<HTMLDivElement | null>(null)
let chart: echarts.ECharts | null = null

function render() {
  if (!chart) return
  const now = Date.now()
  const xMin = props.xMin ?? now - 15 * 60 * 1000
  const xMax = props.xMax ?? now

  const option: echarts.EChartsOption = {
    backgroundColor: 'transparent',
    animation: true,
    grid: { left: 12, right: 12, top: 26, bottom: 24, containLabel: true },
    tooltip: {
      trigger: 'axis',
      backgroundColor: 'rgba(10,16,32,0.92)',
      borderColor: 'rgba(255,255,255,0.08)',
      textStyle: { color: '#E5E7EB' },
    },
    xAxis: {
      type: 'time',
      min: xMin,
      max: xMax,
      axisLine: { lineStyle: { color: 'rgba(255,255,255,0.12)' } },
      axisLabel: { color: 'rgba(255,255,255,0.55)' },
      splitLine: { lineStyle: { color: 'rgba(255,255,255,0.06)' } },
    },
    yAxis: {
      type: 'value',
      axisLine: { lineStyle: { color: 'rgba(255,255,255,0.12)' } },
      axisLabel: { color: 'rgba(255,255,255,0.55)' },
      splitLine: { lineStyle: { color: 'rgba(255,255,255,0.06)' } },
    },
    dataZoom: [
      { type: 'inside', throttle: 60 },
      {
        type: 'slider',
        height: 18,
        bottom: 0,
        borderColor: 'rgba(255,255,255,0.08)',
        fillerColor: 'rgba(47,125,255,0.14)',
        handleStyle: { color: 'rgba(47,125,255,0.95)' },
        textStyle: { color: 'rgba(255,255,255,0.45)' },
      },
    ],
    series: props.series.map((s) => ({
      name: s.name,
      type: 'line',
      showSymbol: false,
      smooth: true,
      lineStyle: { width: 2, color: s.color },
      areaStyle: { opacity: 0.08, color: s.color },
      emphasis: { focus: 'series' },
      data: s.values.map((p) => [p.t, p.v]),
    })),
  }
  chart.setOption(option, { notMerge: true })
}

onMounted(() => {
  if (!el.value) return
  chart = echarts.init(el.value)
  render()
  window.addEventListener('resize', onResize)
})

function onResize() {
  chart?.resize()
}

onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  chart?.dispose()
  chart = null
})

watch(
  [() => props.series, () => props.xMin, () => props.xMax],
  () => render(),
  { deep: true },
)
</script>

