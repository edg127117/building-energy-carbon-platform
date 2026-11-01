<script setup lang="ts">
import { computed } from 'vue'
import ChartView from '@/shared/charts/ChartView.vue'
import type { ChartOption } from '@/shared/charts/echarts'
import { t } from '@/locales'
import type { ScreenPlanData } from '../models/screen-analytical'
import { periodBuckets, type ScreenPeriod } from '../models/screen-period'

type QuotaState = 'remaining' | 'exceeded' | 'used' | 'unknown'

const props = defineProps<{ data: ScreenPlanData | null; period: ScreenPeriod }>()
const formatter = new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 2 })

const months = computed(() => annualBuckets(props.period))
const selectedMonthIndex = computed(() => {
  if (props.period.kind !== 'month') return -1
  const matched = /-(\d{2})$/.exec(props.period.value)
  const month = matched ? Number(matched[1]) : Number.NaN
  return month >= 1 && month <= 12 ? month - 1 : -1
})

const baseline = computed(() => valuesFor(props.data?.baseline, 12))
const quota = computed(() => valuesFor(props.data?.quota, 12))
const actual = computed(() => valuesFor(props.data?.actual, 12))
const hasChartData = computed(() => [baseline.value, quota.value, actual.value]
  .some(values => values.some(isFiniteValue)))
const quotaState = computed<QuotaState>(() => {
  const value = props.data?.remainingQuota
  if (!isFiniteValue(value)) return 'unknown'
  if (value > 0) return 'remaining'
  if (value < 0) return 'exceeded'
  return 'used'
})
const quotaStateLabel = computed(() => {
  switch (quotaState.value) {
    case 'remaining': return t('largeScreen.planQuotaRemaining')
    case 'exceeded': return t('largeScreen.planQuotaExceeded')
    case 'used': return t('largeScreen.planQuotaUsed')
    default: return t('largeScreen.planQuotaUnknown')
  }
})
const reductionValue = computed(() => formatNumber(props.data?.reductionPercent))
const annualBaselineValue = computed(() => formatNumber(props.data?.annualBaseline))
const remainingValue = computed(() => formatSignedNumber(props.data?.remainingQuota))
const unit = computed(() => displayUnit(props.data?.unit))

const option = computed<ChartOption>(() => ({
  animation: false,
  grid: { top: 30, right: 12, bottom: 34, left: 40 },
  legend: { top: '3%', right: 0, itemWidth: 10, itemHeight: 8 },
  tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' }, appendToBody: false },
  xAxis: {
    type: 'category',
    data: months.value,
    axisTick: { show: false },
    axisLabel: {
      interval: 0,
      hideOverlap: false,
      rotate: 45,
      showMinLabel: true,
      showMaxLabel: true,
      formatter: (value: string | number, index: number) => index === selectedMonthIndex.value
        ? `{selected|${String(value)}}`
        : String(value),
      rich: { selected: { color: 'var(--bec-screen-blue)', fontWeight: 650 } },
    },
  },
  yAxis: { type: 'value', name: unit.value, min: 0, splitNumber: 3 },
  series: [
    {
      id: 'baseline',
      name: t('largeScreen.planBaseline'),
      type: 'line',
      data: baseline.value,
      smooth: false,
      showSymbol: true,
      symbol: 'circle',
      symbolSize: 6,
      connectNulls: false,
      lineStyle: { color: 'var(--bec-screen-blue)', width: 2 },
      itemStyle: { color: 'var(--bec-screen-blue)' },
    },
    {
      id: 'quota',
      name: t('largeScreen.planQuota'),
      type: 'line',
      data: quota.value,
      smooth: false,
      showSymbol: true,
      symbol: 'circle',
      symbolSize: 6,
      connectNulls: false,
      lineStyle: { color: 'var(--bec-screen-cyan)', width: 2 },
      itemStyle: { color: 'var(--bec-screen-cyan)' },
    },
    {
      id: 'actual',
      name: t('largeScreen.planActual'),
      type: 'bar',
      data: actual.value,
      barMaxWidth: 22,
      itemStyle: { color: 'var(--bec-screen-blue-soft)' },
    },
  ],
}) as ChartOption)

function annualBuckets(period: ScreenPeriod): string[] {
  const year = period.kind === 'year' ? period.value : period.value.slice(0, 4)
  return periodBuckets({ kind: 'year', value: year })
}

function isFiniteValue(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value)
}

function valuesFor(values: Array<number | null> | undefined, length: number): Array<number | null> {
  return Array.from({ length }, (_, index) => {
    const value = values?.[index]
    return isFiniteValue(value) ? value : null
  })
}

function formatNumber(value: number | null | undefined): string {
  return isFiniteValue(value) ? formatter.format(value) : t('largeScreen.missing')
}

function formatSignedNumber(value: number | null | undefined): string {
  if (!isFiniteValue(value)) return t('largeScreen.missing')
  return value > 0 ? `+${formatter.format(value)}` : formatter.format(value)
}

function displayUnit(value: string | undefined): string {
  return value?.trim() || t('largeScreen.missing')
}
</script>

<template>
  <section class="plan-chart" :aria-label="t('largeScreen.planChartLabel')">
    <div class="plan-summary">
      <div class="plan-metric plan-metric--targets">
        <div class="plan-target">
          <span>{{ t('largeScreen.planAnnualBaseline') }}</span>
          <div class="plan-value">
            <strong data-test="plan-annual-baseline">{{ annualBaselineValue }}</strong>
            <small>{{ unit }}</small>
          </div>
        </div>
        <div class="plan-target">
          <span>{{ t('largeScreen.planReductionRate') }}</span>
          <div class="plan-value">
            <strong>{{ reductionValue }}</strong>
            <small>{{ t('largeScreen.percent') }}</small>
          </div>
        </div>
      </div>
      <div class="plan-metric plan-metric--quota">
        <span>{{ t('largeScreen.planAnnualRemainingQuota') }}</span>
        <div class="plan-value" data-test="plan-quota-state" :aria-label="t('largeScreen.planQuotaAccessible', { value: remainingValue, unit, state: quotaStateLabel })">
          <strong :class="`quota-value quota-value--${quotaState}`">{{ remainingValue }}</strong>
          <small>{{ unit }}</small>
        </div>
      </div>
    </div>
    <div class="plan-plot">
      <ChartView :option="option" :accessible-label="t('largeScreen.planChartLabel')" />
      <p v-if="!hasChartData" class="chart-state" data-test="plan-empty">{{ t('largeScreen.planEmpty') }}</p>
    </div>
  </section>
</template>

<style scoped src="./screen-plan-chart.css"></style>
