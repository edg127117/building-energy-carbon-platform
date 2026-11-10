<script setup lang="ts">
import { computed } from 'vue'
import ChartView from '@/shared/charts/ChartView.vue'
import type { ChartOption } from '@/shared/charts/echarts'
import { t } from '@/locales'
import type { ScreenMirrorData } from '../models/screen-analytical'
import { periodBuckets, type ScreenPeriod } from '../models/screen-period'
import { formatScreenReading } from '../models/screen-reading-format'

type TooltipItem = Record<string, unknown>

const props = defineProps<{ data: ScreenMirrorData | null; period: ScreenPeriod; expanded?: boolean; loading?: boolean; failed?: boolean }>()

const buckets = computed(() => periodBuckets(props.period))
const dynamicEmissions = computed(() => valuesFor(props.data?.dynamicEmissions, buckets.value.length))
const staticEmissions = computed(() => valuesFor(props.data?.staticEmissions, buckets.value.length))
const dynamicFactors = computed(() => valuesFor(props.data?.dynamicFactors, buckets.value.length))
const staticFactors = computed(() => valuesFor(props.data?.staticFactors, buckets.value.length))
const mirroredStaticEmissions = computed(() => staticEmissions.value.map(mirrorCoordinate))
const mirroredStaticFactors = computed(() => staticFactors.value.map(mirrorCoordinate))
const dynamicEmissionExtent = computed(() => symmetricExtent(dynamicEmissions.value))
const staticEmissionExtent = computed(() => symmetricExtent(staticEmissions.value))
const dynamicFactorExtent = computed(() => symmetricExtent(dynamicFactors.value))
const staticFactorExtent = computed(() => symmetricExtent(staticFactors.value))
const separateEmissionUnits = computed(() => !!props.data?.dynamicEmissionUnit && !!props.data?.staticEmissionUnit && props.data.dynamicEmissionUnit !== props.data.staticEmissionUnit)
const separateFactorUnits = computed(() => !!props.data?.dynamicFactorUnit && !!props.data?.staticFactorUnit && props.data.dynamicFactorUnit !== props.data.staticFactorUnit)
const hasChartData = computed(() => [dynamicEmissions.value, staticEmissions.value, dynamicFactors.value, staticFactors.value]
  .some(values => values.some(isFiniteValue)))

const option = computed<ChartOption>(() => ({
  animation: false,
  grid: props.expanded ? { top: 54, right: separateEmissionUnits.value || separateFactorUnits.value ? 170 : 46, bottom: 34, left: 40 } : { top: '22%', right: separateEmissionUnits.value || separateFactorUnits.value ? '25%' : '11%', bottom: '12%', left: '11%', containLabel: true },
  legend: { top: 0, left: 'center', itemWidth: 10, itemHeight: 8 },
  tooltip: {
    trigger: 'axis',
    axisPointer: { type: 'cross' },
    appendToBody: false,
    formatter: (params: unknown) => formatMirrorTooltip(params),
  },
  xAxis: { type: 'category', data: buckets.value, axisTick: { show: false }, axisLabel: { showMinLabel: true, showMaxLabel: true, interval: props.period.kind === 'year' ? 0 : 'auto', hideOverlap: false, rotate: props.period.kind === 'year' ? 45 : 0 } },
  yAxis: [
    {
      type: 'value',
      name: displayUnit(props.data?.dynamicEmissionUnit ?? props.data?.emissionUnit ?? 'tCO₂e'),
      position: 'left',
      ...extentOptions(dynamicEmissionExtent.value),
      axisLabel: { formatter: (value: number) => formatNumber(Math.abs(value)) },
      axisPointer: { label: { formatter: (params: { value: string | number }) => formatNumber(Math.abs(Number(params.value))) } },
    },
    {
      type: 'value',
      name: displayUnit(props.data?.dynamicFactorUnit ?? props.data?.factorUnit ?? 'kgCO₂e/kWh'),
      position: 'right', offset: separateEmissionUnits.value ? 42 : 0,
      ...extentOptions(dynamicFactorExtent.value),
      splitLine: { show: false },
      axisLabel: { formatter: (value: number) => formatNumber(Math.abs(value)) },
      axisPointer: { label: { formatter: (params: { value: string | number }) => formatNumber(Math.abs(Number(params.value))) } },
    },
    ...(separateEmissionUnits.value ? [{
      type: 'value', name: displayUnit(props.data?.staticEmissionUnit ?? props.data?.emissionUnit ?? 'tCO₂e'),
      position: 'right', ...extentOptions(staticEmissionExtent.value),
      axisLabel: { formatter: (value: number) => formatNumber(Math.abs(value)) },
      axisPointer: { label: { formatter: (params: { value: string | number }) => formatNumber(Math.abs(Number(params.value))) } },
    }] : []),
    ...(separateFactorUnits.value ? [{
      type: 'value', name: displayUnit(props.data?.staticFactorUnit ?? props.data?.factorUnit ?? 'kgCO₂e/kWh'),
      position: 'right', offset: separateEmissionUnits.value ? 84 : 42, ...extentOptions(staticFactorExtent.value),
      splitLine: { show: false },
      axisLabel: { formatter: (value: number) => formatNumber(Math.abs(value)) },
      axisPointer: { label: { formatter: (params: { value: string | number }) => formatNumber(Math.abs(Number(params.value))) } },
    }] : []),
  ],
  series: [
    {
      id: 'dynamic-emissions',
      name: t('largeScreen.mirrorDynamicEmissions'),
      type: 'bar',
      data: dynamicEmissions.value,
      yAxisIndex: 0,
      barMaxWidth: 18,
      itemStyle: { color: 'var(--bec-screen-blue)' },
    },
    {
      id: 'static-emissions',
      name: t('largeScreen.mirrorStaticEmissions'),
      type: 'bar',
      data: mirroredStaticEmissions.value,
      yAxisIndex: separateEmissionUnits.value ? 2 : 0,
      barGap: '-100%',
      barMaxWidth: 18,
      itemStyle: { color: 'var(--bec-screen-cyan)' },
    },
    {
      id: 'dynamic-factors',
      name: t('largeScreen.mirrorDynamicFactors'),
      type: 'line',
      data: dynamicFactors.value,
      yAxisIndex: 1,
      z: 5,
      smooth: false,
      showSymbol: true,
      symbol: 'circle',
      symbolSize: 6,
      connectNulls: false,
      lineStyle: { color: 'var(--bec-screen-factor-dynamic)', width: 3 },
      itemStyle: { color: 'var(--bec-screen-factor-dynamic)', borderColor: 'var(--bec-screen-border)', borderWidth: 1 },
    },
    {
      id: 'static-factors',
      name: t('largeScreen.mirrorStaticFactors'),
      type: 'line',
      data: mirroredStaticFactors.value,
      yAxisIndex: separateFactorUnits.value ? separateEmissionUnits.value ? 3 : 2 : 1,
      z: 5,
      smooth: false,
      showSymbol: true,
      symbol: 'circle',
      symbolSize: 6,
      connectNulls: false,
      lineStyle: { color: 'var(--bec-screen-factor-static)', width: 3, type: 'solid' },
      itemStyle: { color: 'var(--bec-screen-factor-static)', borderColor: 'var(--bec-screen-border)', borderWidth: 1 },
    },
  ],
}) as ChartOption)

function isFiniteValue(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value)
}

function valuesFor(values: Array<number | null> | undefined, length: number): Array<number | null> {
  return Array.from({ length }, (_, index) => {
    const value = values?.[index]
    return isFiniteValue(value) ? value : null
  })
}

// 静态量只映射为下半区坐标；业务展示和悬停读数仍保持实际的正值语义。
function mirrorCoordinate(value: number | null): number | null {
  return isFiniteValue(value) ? -Math.abs(value) : null
}

function symmetricExtent(values: Array<number | null>): number | null {
  const maximum = Math.max(0, ...values.filter(isFiniteValue).map(value => Math.abs(value)))
  return maximum > 0 ? maximum : null
}

// 镜像双轴保持零点居中；峰值外留出余量，再取规整步长，避免最大读数贴住边界。
function extentOptions(extent: number | null): { min: number; max: number; interval: number } {
  if (extent === null) return { min: -1, max: 1, interval: 0.25 }
  const paddedExtent = extent * 1.15
  const rawStep = paddedExtent / 4
  const magnitude = 10 ** Math.floor(Math.log10(rawStep))
  const fraction = rawStep / magnitude
  const interval = (fraction < 1.5 ? 1 : fraction < 3 ? 2 : fraction < 7 ? 5 : 10) * magnitude
  const visibleExtent = Number((Math.ceil(paddedExtent / interval) * interval).toPrecision(12))
  return { min: -visibleExtent, max: visibleExtent, interval }
}

function formatNumber(value: number | null | undefined): string {
  return isFiniteValue(value) ? formatScreenReading(value) : t('largeScreen.missing')
}

function displayUnit(value: string | undefined): string {
  return value?.trim() || t('largeScreen.missing')
}

function formatMirrorTooltip(params: unknown): string {
  const items = Array.isArray(params)
    ? params.filter((item): item is TooltipItem => typeof item === 'object' && item !== null)
    : []
  const axis = items.length ? String(items[0].axisValueLabel ?? items[0].axisValue ?? '') : ''
  const index = Number(items[0]?.dataIndex)
  const detail = Number.isInteger(index) ? props.data?.hourDetails?.[index] : undefined
  const rows = items.map((item) => {
    const value = tooltipValue(item)
    if (!isFiniteValue(value)) return ''
    const seriesId = String(item.seriesId ?? '')
    const actualValue = isStaticSeries(seriesId) ? Math.abs(value) : value
    return t('largeScreen.mirrorTooltipItem', {
      name: String(item.seriesName ?? t('largeScreen.missing')),
      value: formatNumber(actualValue),
      unit: tooltipUnit(seriesId),
    })
  }).filter(Boolean)
  const actual = detail?.actualStart && detail.actualEnd ? `${detail.actualStart} — ${detail.actualEnd}` : ''
  const gas = detail ? t(detail.gasCoverage === 'CO2_ONLY_ELECTRICITY' || detail.gasCoverage === 'CO2_ONLY' ? 'largeScreen.hourGasCo2' : 'largeScreen.hourGasCo2e') : ''
  const notes = detail ? [actual ? t('largeScreen.hourActualWindow', { value: actual }) : '', detail.actualEnd ? t('largeScreen.hourCutoff', { value: detail.actualEnd }) : '', detail.approximate ? t('largeScreen.hourApproximate') : '', gas, detail.reasonCode ?? ''].filter(Boolean) : []
  return [axis, ...rows, ...notes].filter(Boolean).join('\n')
}

function tooltipValue(item: TooltipItem): number | null {
  const value = item.value ?? item.data
  return isFiniteValue(value) ? value : null
}

function isStaticSeries(seriesId: string): boolean {
  return seriesId === 'static-emissions' || seriesId === 'static-factors'
}

function tooltipUnit(seriesId: string): string {
  if (seriesId === 'dynamic-factors') return displayUnit(props.data?.dynamicFactorUnit ?? props.data?.factorUnit ?? 'kgCO₂e/kWh')
  if (seriesId === 'static-factors') return displayUnit(props.data?.staticFactorUnit ?? props.data?.factorUnit ?? 'kgCO₂e/kWh')
  if (seriesId === 'dynamic-emissions') return displayUnit(props.data?.dynamicEmissionUnit ?? props.data?.emissionUnit ?? 'tCO₂e')
  return displayUnit(props.data?.staticEmissionUnit ?? props.data?.emissionUnit ?? 'tCO₂e')
}
</script>

<template>
  <section class="mirror-chart" :aria-label="t('largeScreen.mirrorChartLabel')">
    <ChartView :option="option" :accessible-label="t('largeScreen.mirrorChartLabel')" />
    <p v-if="failed" class="chart-state" role="alert">{{ t(data?.hourDetails ? 'largeScreen.failedStale' : 'largeScreen.failed') }}</p>
    <p v-else-if="loading" class="chart-state" role="status">{{ t('common.loading') }}</p>
    <p v-else-if="!hasChartData" class="chart-state" data-test="mirror-empty">{{ t('largeScreen.mirrorEmpty') }}</p>
  </section>
</template>

<style scoped src="./screen-mirror-chart.css"></style>
