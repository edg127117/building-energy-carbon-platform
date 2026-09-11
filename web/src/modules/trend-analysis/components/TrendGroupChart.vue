<script setup lang="ts">
import { computed } from 'vue'
import ChartView from '@/shared/charts/ChartView.vue'
import type { ChartOption } from '@/shared/charts/echarts'
import { t } from '@/locales'
import type { HvacTrendGroup } from '../models/hvac-history'

const props = defineProps<{ group: HvacTrendGroup; loading: boolean }>()
const option = computed<ChartOption>(() => ({
  tooltip: { trigger: 'axis' },
  legend: { type: 'scroll' },
  grid: { left: '4%', right: '4%', top: '16%', bottom: '8%', containLabel: true },
  xAxis: {
    type: 'time',
    axisLabel: {
      formatter: (value: number) => new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false }).format(value),
    },
  },
  yAxis: { type: 'value', name: props.group.unit },
  series: props.group.series.map(series => ({
    id: series.id,
    name: series.label,
    type: 'line',
    showSymbol: false,
    connectNulls: false,
    data: series.points.map(point => [point.time, point.average]),
  })),
}))
</script>

<template>
  <div class="trend-chart">
    <ChartView :option="option" :loading="loading" :empty="group.series.length === 0" :accessible-label="t('trendAnalysis.title')" />
  </div>
</template>

<style scoped>
.trend-chart { height: var(--bec-chart-height); min-width: 0; }
</style>
