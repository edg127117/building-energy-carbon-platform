<script setup lang="ts">
import { computed } from 'vue'
import ChartView from '@/shared/charts/ChartView.vue'
import type { ChartOption } from '@/shared/charts/echarts'
import { t } from '@/locales'

export interface ScreenPlotSeries {
  name: string
  values: Array<number | null>
  axis?: 0 | 1
  predicted?: boolean
}
const props = defineProps<{ kind: 'comparison' | 'temperature' | 'prediction' | 'trend'; label: string; periods?: string[]; series?: ScreenPlotSeries[]; loading?: boolean; emptyMessage?: string; valueAxisUnit?: string; secondaryValueAxisUnit?: string }>()
const hasData = computed(() => props.series?.some(series => series.values.some(value => value !== null && Number.isFinite(value))) === true)
// 未提供同口径序列时只保留坐标与图例，不用空数组补零或生成演示曲线。
const option = computed<ChartOption>(() => ({
  animation: false,
  grid: { top: '22%', left: '12%', right: props.kind === 'temperature' ? '12%' : '6%', bottom: props.kind === 'comparison' ? '24%' : '16%' },
  xAxis: { type: 'category', data: props.periods ?? (props.kind === 'comparison' ? ['previousYear', 'current', 'previousMonth'].map(key => t('largeScreen.' + key)) : ['00:00', '04:00', '08:00', '12:00', '16:00', '20:00', '24:00']), axisTick: { show: false } },
  yAxis: props.kind === 'temperature'
    ? [{ type: 'value', name: props.valueAxisUnit ?? t('largeScreen.tco2') }, { type: 'value', name: props.secondaryValueAxisUnit ?? '°C' }]
    : { type: 'value', name: props.valueAxisUnit ?? t(props.kind === 'trend' ? 'largeScreen.kwh' : 'largeScreen.tco2') },
  tooltip: { trigger: 'axis', appendToBody: false },
  series: props.series?.map(series => ({ type: props.kind === 'comparison' ? 'bar' as const : 'line' as const, name: series.name, data: series.values, yAxisIndex: series.axis ?? 0, stack: props.kind === 'comparison' ? 'carbon' : undefined, connectNulls: false, smooth: 0.25, symbolSize: 5, lineStyle: { type: series.predicted ? 'dashed' as const : 'solid' as const, width: 2 }, areaStyle: series.predicted || props.kind === 'comparison' ? undefined : { opacity: 0.08 } })) ?? [],
}))
</script>
<template><div class="screen-plot"><ChartView :option="option" :accessible-label="label" :loading="loading" /><span v-if="!hasData && !loading" class="plot-state">{{ emptyMessage ?? t('largeScreen.' + (kind === 'prediction' ? 'noForecast' : kind === 'comparison' ? 'noComparison' : kind === 'temperature' ? 'noTemperature' : 'noEnergy')) }}</span></div></template>
<style scoped>
.screen-plot { position: relative; height: 100%; min-height: 0; }
.screen-plot :deep(.chart-view) { height: 100%; }
.plot-state { position: absolute; inset: 44% var(--bec-screen-space-24) auto; text-align: center; color: var(--bec-screen-muted); font-size: var(--bec-screen-font-small); pointer-events: none; }
</style>
