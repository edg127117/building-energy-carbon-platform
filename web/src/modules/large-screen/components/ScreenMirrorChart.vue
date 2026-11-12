<script setup lang="ts">
import { computed } from 'vue'
import ChartView from '@/shared/charts/ChartView.vue'
import type { ChartOption } from '@/shared/charts/echarts'
import { t } from '@/locales'
import type { ScreenMirrorData } from '../models/screen-analytical'
import { periodBuckets, type ScreenPeriod } from '../models/screen-period'

type TooltipItem = Record<string, unknown>

const props = defineProps<{ data: ScreenMirrorData | null; period: ScreenPeriod; expanded?: boolean; loading?: boolean; failed?: boolean }>()

const buckets = computed(() => periodBuckets(props.period))
const dynamicEmissionDisplay = computed(() => emissionDisplay(props.data?.dynamicEmissionUnit ?? props.data?.emissionUnit ?? 'tCO₂e'))
const staticEmissionDisplay = computed(() => emissionDisplay(props.data?.staticEmissionUnit ?? props.data?.emissionUnit ?? 'tCO₂e'))
const dynamicFactorUnit = computed(() => displayUnit(props.data?.dynamicFactorUnit ?? props.data?.factorUnit ?? 'kgCO₂e/kWh'))
const staticFactorUnit = computed(() => displayUnit(props.data?.staticFactorUnit ?? props.data?.factorUnit ?? 'kgCO₂e/kWh'))
const dynamicEmissions = computed(() => valuesFor(props.data?.dynamicEmissions, buckets.value.length, dynamicEmissionDisplay.value.multiplier))
const staticEmissions = computed(() => valuesFor(props.data?.staticEmissions, buckets.value.length, staticEmissionDisplay.value.multiplier))
const dynamicFactors = computed(() => valuesFor(props.data?.dynamicFactors, buckets.value.length))
const staticFactors = computed(() => valuesFor(props.data?.staticFactors, buckets.value.length))
const mirroredStaticEmissions = computed(() => staticEmissions.value.map(mirrorCoordinate))
const mirroredStaticFactors = computed(() => staticFactors.value.map(mirrorCoordinate))
const dynamicEmissionExtent = computed(() => symmetricExtent(separateEmissionUnits.value ? dynamicEmissions.value : [...dynamicEmissions.value, ...staticEmissions.value]))
const staticEmissionExtent = computed(() => symmetricExtent(staticEmissions.value))
const dynamicFactorExtent = computed(() => symmetricExtent(separateFactorUnits.value ? dynamicFactors.value : [...dynamicFactors.value, ...staticFactors.value]))
const staticFactorExtent = computed(() => symmetricExtent(staticFactors.value))
const separateEmissionUnits = computed(() => dynamicEmissionDisplay.value.unit !== staticEmissionDisplay.value.unit)
const separateFactorUnits = computed(() => dynamicFactorUnit.value !== staticFactorUnit.value)
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
      name: dynamicEmissionDisplay.value.unit,
      position: 'left',
      ...axisScale(dynamicEmissionExtent.value),
    },
    {
      type: 'value',
      name: dynamicFactorUnit.value,
      position: 'right', offset: separateEmissionUnits.value ? 42 : 0,
      ...axisScale(dynamicFactorExtent.value),
      splitLine: { show: false },
    },
    ...(separateEmissionUnits.value ? [{
      type: 'value', name: staticEmissionDisplay.value.unit,
      position: 'right', ...axisScale(staticEmissionExtent.value),
    }] : []),
    ...(separateFactorUnits.value ? [{
      type: 'value', name: staticFactorUnit.value,
      position: 'right', offset: separateEmissionUnits.value ? 84 : 42, ...axisScale(staticFactorExtent.value),
      splitLine: { show: false },
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

function valuesFor(values: Array<number | null> | undefined, length: number, multiplier = 1): Array<number | null> {
  return Array.from({ length }, (_, index) => {
    const value = values?.[index]
    return isFiniteValue(value) ? value * multiplier : null
  })
}

// 小时图只把已知的吨单位换算为千克，保留 CO₂/CO₂e 口径；不修改传入的业务数据。
function emissionDisplay(rawUnit: string) {
  const unit = displayUnit(rawUnit)
  if (props.period.kind === 'day' && /^tCO[₂2]e?$/.test(unit)) return { unit: `kg${unit.slice(1)}`, multiplier: 1000 }
  return { unit, multiplier: 1 }
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
  const interval = (fraction <= 1 ? 1 : fraction <= 2 ? 2 : fraction <= 2.5 ? 2.5 : fraction <= 5 ? 5 : 10) * magnitude
  const visibleExtent = Number((Math.ceil(paddedExtent / interval) * interval).toPrecision(12))
  return { min: -visibleExtent, max: visibleExtent, interval }
}

function axisScale(extent: number | null) {
  const range = extentOptions(extent)
  // 精度依据实际步长，避免小量级刻度舍入后重复；镜像下半区仍显示实际正值。
  const digits = stepDigits(range.interval)
  const formatter = new Intl.NumberFormat('zh-CN', { maximumFractionDigits: digits })
  return {
    ...range,
    axisLabel: { formatter: (value: number) => formatter.format(Math.abs(value)) },
    axisPointer: { label: { formatter: (params: { value: string | number }) => formatNumber(Math.abs(Number(params.value)), digits) } },
  }
}

function stepDigits(interval: number) {
  const [mantissa, exponent] = Number(interval.toPrecision(12)).toExponential().split('e')
  return Math.min(20, Math.max(0, (mantissa.split('.')[1]?.length ?? 0) - Number(exponent)))
}

function formatNumber(value: number | null | undefined, digits = 2): string {
  return isFiniteValue(value) ? new Intl.NumberFormat('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: Math.max(2, digits) }).format(value) : t('largeScreen.missing')
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
      value: formatNumber(actualValue, stepDigits(extentOptions(tooltipExtent(seriesId)).interval)),
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
  if (seriesId === 'dynamic-factors') return dynamicFactorUnit.value
  if (seriesId === 'static-factors') return staticFactorUnit.value
  if (seriesId === 'dynamic-emissions') return dynamicEmissionDisplay.value.unit
  return staticEmissionDisplay.value.unit
}

function tooltipExtent(seriesId: string) {
  if (seriesId === 'dynamic-factors') return dynamicFactorExtent.value
  if (seriesId === 'static-factors') return separateFactorUnits.value ? staticFactorExtent.value : dynamicFactorExtent.value
  if (seriesId === 'dynamic-emissions') return dynamicEmissionExtent.value
  return separateEmissionUnits.value ? staticEmissionExtent.value : dynamicEmissionExtent.value
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
