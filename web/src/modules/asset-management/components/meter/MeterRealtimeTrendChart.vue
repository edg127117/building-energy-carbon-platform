<script setup lang="ts">
import { computed } from 'vue'
import ChartView from '@/shared/charts/ChartView.vue'
import type { ChartOption } from '@/shared/charts/echarts'
import { t } from '@/locales'

export type MeterTrendRecord = {
  time: number
  power: number | null
  currentA?: number | null
  currentB?: number | null
  currentC?: number | null
  voltage?: number | null
  dataQuality?: number | null
}

const props = withDefaults(defineProps<{
  phase: '3P' | '1P'
  records: MeterTrendRecord[]
  loading?: boolean
}>(), {
  loading: false,
})

const option = computed<ChartOption>(() => {
  const is3P = props.phase === '3P'
  const timeLabels = props.records.map(r => {
    const d = new Date(r.time)
    const hh = String(d.getHours()).padStart(2, '0')
    const mm = String(d.getMinutes()).padStart(2, '0')
    const ss = String(d.getSeconds()).padStart(2, '0')
    return `${hh}:${mm}:${ss}`
  })

  if (is3P) {
    return {
      tooltip: {
        trigger: 'axis',
      },
      legend: {
        bottom: '0%',
      },
      grid: {
        left: '2%',
        right: '4%',
        top: '12%',
        bottom: '16%',
        containLabel: true,
      },
      xAxis: {
        type: 'category',
        boundaryGap: false,
        data: timeLabels,
      },
      yAxis: [
        {
          type: 'value',
          name: t('assetManagement.meter.unitKw'),
        },
        {
          type: 'value',
          name: t('assetManagement.meter.unitA'),
          splitLine: { show: false },
        },
      ],
      series: [
        {
          name: t('assetManagement.meter.realtimePower'),
          type: 'line',
          yAxisIndex: 0,
          smooth: true,
          showSymbol: false,
          areaStyle: {
            opacity: 0.12,
          },
          data: props.records.map(r => r.power),
        },
        {
          name: t('assetManagement.meter.phaseACurrent'),
          type: 'line',
          yAxisIndex: 1,
          smooth: true,
          showSymbol: false,
          data: props.records.map(r => r.currentA ?? null),
        },
        {
          name: t('assetManagement.meter.phaseBCurrent'),
          type: 'line',
          yAxisIndex: 1,
          smooth: true,
          showSymbol: false,
          data: props.records.map(r => r.currentB ?? null),
        },
        {
          name: t('assetManagement.meter.phaseCCurrent'),
          type: 'line',
          yAxisIndex: 1,
          smooth: true,
          showSymbol: false,
          data: props.records.map(r => r.currentC ?? null),
        },
      ],
    }
  }

  // 1P 单相走势
  return {
    tooltip: {
      trigger: 'axis',
    },
    legend: {
      bottom: '0%',
    },
    grid: {
      left: '2%',
      right: '4%',
      top: '12%',
      bottom: '16%',
      containLabel: true,
    },
    xAxis: {
      type: 'category',
      boundaryGap: false,
      data: timeLabels,
    },
    yAxis: [
      {
        type: 'value',
        name: t('assetManagement.meter.unitKw'),
      },
      {
        type: 'value',
        name: t('assetManagement.meter.unitV'),
        splitLine: { show: false },
      },
    ],
    series: [
      {
        name: t('assetManagement.meter.singlePhasePower'),
        type: 'line',
        yAxisIndex: 0,
        smooth: true,
        showSymbol: false,
        areaStyle: {
          opacity: 0.12,
        },
        data: props.records.map(r => r.power),
      },
      {
        name: t('assetManagement.meter.voltage'),
        type: 'line',
        yAxisIndex: 1,
        smooth: true,
        showSymbol: false,
        data: props.records.map(r => r.voltage ?? null),
      },
    ],
  }
})
</script>

<template>
  <div class="meter-trend-container">
    <div class="trend-heading">
      <div class="trend-title-wrap">
        <h4 class="section-title">
          {{ props.phase === '3P' ? t('assetManagement.meter.threePhaseTrendTitle') : t('assetManagement.meter.singlePhaseTrendTitle') }}
        </h4>
        <span class="live-pulse" :title="t('assetManagement.meter.realtimePulse')" />
      </div>
      <span class="trend-subtitle">{{ t('assetManagement.meter.trendSubtitle') }}</span>
    </div>

    <div class="chart-wrapper">
      <ChartView
        :option="option"
        :loading="props.loading"
        :empty="props.records.length === 0"
        :accessible-label="t('assetManagement.meter.chartAccessible')"
      />
    </div>
  </div>
</template>

<style scoped>
.meter-trend-container {
  display: grid;
  gap: var(--bec-space-tight);
  background: var(--bec-color-surface-primary);
  border: var(--bec-border-width) solid var(--bec-color-divider);
  border-radius: var(--bec-management-radius);
  padding: var(--bec-space-section);
}

.trend-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: var(--bec-space-tight);
}

.trend-title-wrap {
  display: inline-flex;
  align-items: center;
  gap: var(--bec-ref-space-8);
}

.section-title {
  margin: 0;
  font-size: var(--bec-font-size-body);
  font-weight: var(--bec-font-weight-heading);
  color: var(--bec-color-text-primary);
}

.live-pulse {
  width: var(--bec-ref-space-8);
  height: var(--bec-ref-space-8);
  border-radius: var(--bec-ref-radius-pill);
  background-color: var(--bec-ref-blue);
  animation: pulse-ring 2s cubic-bezier(0.4, 0, 0.6, 1) infinite;
}

@keyframes pulse-ring {
  0%, 100% { opacity: 1; transform: scale(1); }
  50% { opacity: 0.4; transform: scale(1.3); }
}

.trend-subtitle {
  font-size: var(--bec-font-size-small);
  color: var(--bec-color-text-secondary);
}

.chart-wrapper {
  height: var(--bec-chart-height);
  width: 100%;
  min-width: 0;
}
</style>
