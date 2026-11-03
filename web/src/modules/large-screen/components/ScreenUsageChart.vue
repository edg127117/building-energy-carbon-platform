<script setup lang="ts">
import { computed } from 'vue'
import ChartView from '@/shared/charts/ChartView.vue'
import type { ChartOption } from '@/shared/charts/echarts'
import { t } from '@/locales'
import type { ScreenUsageData } from '../models/screen-analytical'
import { periodBuckets, type ScreenPeriod } from '../models/screen-period'
import { formatScreenReading } from '../models/screen-reading-format'

const props = defineProps<{ data: ScreenUsageData | null; period: ScreenPeriod; expanded?: boolean; wide?: boolean; loading?: boolean }>()
const buckets = computed(() => periodBuckets(props.period))
const electricity = computed(() => valuesFor(props.data?.electricity, buckets.value.length))
const temperature = computed(() => valuesFor(props.data?.temperature, buckets.value.length))
const hasChartData = computed(() => electricity.value.some(isFiniteValue) || temperature.value.some(isFiniteValue))

const option = computed<ChartOption>(() => ({
  animation: false,
  grid: props.expanded ? { top: 34, right: 42, bottom: 34, left: 48 } : { top: '18%', right: '10%', bottom: '12%', left: '10%', containLabel: true },
  legend: { top: 0, left: 'center', itemWidth: 10, itemHeight: 8 },
  tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' }, appendToBody: false },
  xAxis: { type: 'category', data: buckets.value, axisTick: { show: false }, axisLabel: { showMinLabel: true, showMaxLabel: true, interval: props.period.kind === 'year' ? 0 : 'auto', hideOverlap: false, rotate: props.period.kind === 'year' && !props.wide ? 45 : 0 } },
  yAxis: [
    { type: 'value', name: displayUnit(props.data?.electricityUnit ?? 'kWh'), position: 'left', min: 0 },
    { type: 'value', name: displayUnit(props.data?.temperatureUnit ?? '℃'), position: 'right', scale: true, minInterval: 1, axisLabel: { formatter: (value: number) => formatScreenReading(value, 0) } },
  ],
  series: [
    {
      id: 'electricity',
      name: t('largeScreen.usageElectricity'),
      type: 'bar',
      data: electricity.value,
      tooltip: { valueFormatter: (value: unknown) => formatValue(value, 2) },
      yAxisIndex: 0,
      barMaxWidth: props.expanded ? 36 : 24,
      itemStyle: { color: 'var(--bec-screen-blue)' },
    },
    {
      id: 'temperature',
      name: t('largeScreen.usageTemperature'),
      type: 'line',
      data: temperature.value,
      tooltip: { valueFormatter: (value: unknown) => formatValue(value, 0) },
      yAxisIndex: 1,
      smooth: false,
      showSymbol: true,
      symbol: 'circle',
      symbolSize: 6,
      connectNulls: false,
      lineStyle: { color: 'var(--bec-screen-cyan)', width: 2 },
      itemStyle: { color: 'var(--bec-screen-cyan)' },
    },
  ],
}) as ChartOption)

function isFiniteValue(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value)
}

function formatValue(value: unknown, digits: 0 | 2): string {
  return isFiniteValue(value) ? formatScreenReading(value, digits) : t('largeScreen.missing')
}

function valuesFor(values: Array<number | null> | undefined, length: number): Array<number | null> {
  return Array.from({ length }, (_, index) => {
    const value = values?.[index]
    return isFiniteValue(value) ? value : null
  })
}

function displayUnit(value: string | undefined): string {
  return value?.trim() || t('largeScreen.missing')
}
</script>

<template>
  <section class="usage-chart" :aria-label="t('largeScreen.usageChartLabel')">
    <ChartView :option="option" :accessible-label="t('largeScreen.usageChartLabel')" />
    <p v-if="loading" class="chart-state" role="status">{{ t('common.loading') }}</p>
    <p v-else-if="!hasChartData" class="chart-state" data-test="usage-empty">{{ t('largeScreen.usageEmpty') }}</p>
  </section>
</template>

<style scoped src="./screen-usage-chart.css"></style>
