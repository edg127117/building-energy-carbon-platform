<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import ChartView from '@/shared/charts/ChartView.vue'
import type { ChartOption } from '@/shared/charts/echarts'
import {
  ElButton,
  ElDatePicker,
  ElDialog,
  ElRadioButton,
  ElRadioGroup,
  ElTag,
  ElTooltip,
  Info,
  Maximize,
  Minimize,
  RefreshCw,
} from '@/shared/ui'
import { t } from '@/locales'
import type { TrendDateRange, TrendRangeType } from '../../composables/use-equipment-trend-history'
import {
  buildTimeSeriesData,
  type MeterTrendRecord,
} from './meter-display'

export type { MeterTrendRecord }

const props = withDefaults(defineProps<{
  phase: '3P' | '1P'
  records: MeterTrendRecord[]
  loading?: boolean
  historyLoading?: boolean
  apiPending?: boolean
  rangeType?: TrendRangeType
  customRange?: TrendDateRange
  isLargeDataset?: boolean
}>(), {
  loading: false,
  historyLoading: false,
  apiPending: false,
  rangeType: 'realtime',
  customRange: null,
  isLargeDataset: false,
})

const emit = defineEmits<{
  (e: 'change-range', type: TrendRangeType): void
  (e: 'change-custom-range', range: TrendDateRange): void
  (e: 'refresh-history'): void
}>()

const isZoomed = ref(false)

const selectedRange = ref<TrendRangeType>(props.rangeType)
watch(() => props.rangeType, (newVal) => {
  if (newVal) selectedRange.value = newVal
})

const customDateRange = ref<TrendDateRange>(props.customRange)
watch(() => props.customRange, (newVal) => {
  customDateRange.value = newVal
})

function openZoom(): void {
  isZoomed.value = true
}

function closeZoom(): void {
  isZoomed.value = false
}

function handleRangeChange(val: string | number | boolean | undefined): void {
  const type = String(val) as TrendRangeType
  selectedRange.value = type
  emit('change-range', type)
}

function handleCustomDateChange(val: unknown): void {
  const range = (val as TrendDateRange) ?? null
  emit('change-custom-range', range)
}

function handleRefresh(): void {
  emit('refresh-history')
}

function disableFutureDates(date: Date): boolean {
  return date.getTime() > Date.now()
}

const latestRecord = computed(() => {
  if (props.records.length === 0) return null
  return props.records[props.records.length - 1]
})

function formatValueWithUnit(val: number | null | undefined, unit: string): string {
  if (val == null || Number.isNaN(val)) return '--'
  const numStr = Math.abs(val) >= 100 ? val.toFixed(1) : val.toFixed(2)
  return `${numStr} ${unit}`
}

function autoScalePowerMin(value: { min: number; max: number }) {
  if (!Number.isFinite(value.min) || !Number.isFinite(value.max)) return 0
  const span = value.max - value.min
  const padding = Math.max(span * 0.25, 0.04)
  const calculated = value.min - padding
  const minVal = (value.min >= 0 && calculated < 0 && value.min < 0.05) ? 0 : calculated
  return Number(minVal.toFixed(2))
}

function autoScalePowerMax(value: { min: number; max: number }) {
  if (!Number.isFinite(value.max)) return 1
  const span = value.max - value.min
  const padding = Math.max(span * 0.25, 0.04)
  const calculated = value.max + padding
  return Number(calculated.toFixed(2))
}

function autoScaleVoltageMin(value: { min: number; max: number }) {
  if (!Number.isFinite(value.min)) return 180
  const span = value.max - value.min
  const padding = Math.max(span * 0.3, 5)
  return Math.floor(Math.max(0, value.min - padding))
}

function autoScaleVoltageMax(value: { min: number; max: number }) {
  if (!Number.isFinite(value.max)) return 260
  const span = value.max - value.min
  const padding = Math.max(span * 0.3, 5)
  return Math.ceil(value.max + padding)
}

function autoScaleCurrentMin(value: { min: number; max: number }) {
  if (!Number.isFinite(value.min)) return 0
  const span = value.max - value.min
  const padding = Math.max(span * 0.25, 0.2)
  const minVal = Math.max(0, value.min - padding)
  return Number(minVal.toFixed(2))
}

function autoScaleCurrentMax(value: { min: number; max: number }) {
  if (!Number.isFinite(value.max)) return 10
  const span = value.max - value.min
  const padding = Math.max(span * 0.25, 0.2)
  const maxVal = value.max + padding
  return Number(maxVal.toFixed(2))
}


function buildOption(isEnlarged: boolean): ChartOption {
  const is3P = props.phase === '3P'
  const isLarge = props.isLargeDataset || props.records.length >= 1000

  const timeSpan = props.records.length > 1
    ? props.records[props.records.length - 1].time - props.records[0].time
    : 0
  const isMultiDay = timeSpan > 24 * 3600 * 1000

  const formatTimeTick = (val: number | string) => {
    const d = new Date(Number(val))
    if (isMultiDay) {
      const MM = String(d.getMonth() + 1).padStart(2, '0')
      const DD = String(d.getDate()).padStart(2, '0')
      const hh = String(d.getHours()).padStart(2, '0')
      const mm = String(d.getMinutes()).padStart(2, '0')
      return `${MM}-${DD} ${hh}:${mm}`
    }
    const hh = String(d.getHours()).padStart(2, '0')
    const mm = String(d.getMinutes()).padStart(2, '0')
    const ss = String(d.getSeconds()).padStart(2, '0')
    return `${hh}:${mm}:${ss}`
  }

  const grid = isEnlarged
    ? { left: 20, right: 20, top: 64, bottom: 65, containLabel: true }
    : { left: 10, right: 10, top: 40, bottom: 32, containLabel: true }

  const legend = isEnlarged
    ? { top: 10, left: 'center', itemGap: 24 }
    : { bottom: '0%' }

  const splitNumber = isEnlarged ? 6 : 4
  const symbolSize = isLarge ? 0 : (isEnlarged ? 6 : 4)
  const showSymbol = !isLarge && props.records.length <= 100

  const dataZoom = isEnlarged
    ? [
        {
          type: 'slider',
          show: true,
          xAxisIndex: [0],
          bottom: 8,
          height: 22,
          start: 0,
          end: 100,
        },
        {
          type: 'inside',
          xAxisIndex: [0],
        },
      ]
    : undefined

  const minTime = props.records.length === 1 ? props.records[0].time - 1800_000 : undefined
  const maxTime = props.records.length === 1 ? props.records[0].time + 1800_000 : undefined
  const xAxis = {
    type: 'time' as const,
    min: minTime,
    max: maxTime,
    axisLabel: {
      margin: 12,
      formatter: formatTimeTick,
    },
  }

  const tooltip = {
    trigger: 'axis' as const,
    axisPointer: {
      type: 'cross' as const,
    },
  }

  if (is3P) {
    return {
      animation: !isLarge,
      animationDuration: isLarge ? 0 : 300,
      tooltip,
      legend,
      grid,
      dataZoom,
      xAxis,
      yAxis: [
        {
          type: 'value',
          name: t('assetManagement.meter.unitKwTotal'),
          nameLocation: 'end',
          nameGap: isEnlarged ? 14 : 10,
          scale: true,
          min: autoScalePowerMin,
          max: autoScalePowerMax,
          splitNumber,
          nameTextStyle: {
            align: 'left',
            fontWeight: 600,
          },
        },
        {
          type: 'value',
          name: t('assetManagement.meter.unitA'),
          nameLocation: 'end',
          nameGap: isEnlarged ? 14 : 10,
          scale: true,
          min: autoScaleCurrentMin,
          max: autoScaleCurrentMax,
          splitNumber,
          nameTextStyle: {
            align: 'right',
            fontWeight: 600,
          },
          splitLine: { show: false },
        },
      ],
      series: [
        {
          name: t('assetManagement.meter.realtimePower'),
          type: 'line',
          yAxisIndex: 0,
          smooth: true,
          showSymbol,
          symbol: 'circle',
          symbolSize,
          sampling: isLarge ? 'lttb' : undefined,
          connectNulls: false,
          areaStyle: {
            opacity: 0.12,
          },
          data: buildTimeSeriesData(props.records, (r) => r.power),
        },
        {
          name: t('assetManagement.meter.phaseACurrent'),
          type: 'line',
          yAxisIndex: 1,
          smooth: true,
          showSymbol,
          symbol: 'circle',
          symbolSize,
          sampling: isLarge ? 'lttb' : undefined,
          connectNulls: false,
          data: buildTimeSeriesData(props.records, (r) => r.currentA),
        },
        {
          name: t('assetManagement.meter.phaseBCurrent'),
          type: 'line',
          yAxisIndex: 1,
          smooth: true,
          showSymbol,
          symbol: 'circle',
          symbolSize,
          sampling: isLarge ? 'lttb' : undefined,
          connectNulls: false,
          data: buildTimeSeriesData(props.records, (r) => r.currentB),
        },
        {
          name: t('assetManagement.meter.phaseCCurrent'),
          type: 'line',
          yAxisIndex: 1,
          smooth: true,
          showSymbol,
          symbol: 'circle',
          symbolSize,
          sampling: isLarge ? 'lttb' : undefined,
          connectNulls: false,
          data: buildTimeSeriesData(props.records, (r) => r.currentC),
        },
      ],
    }
  }

  return {
    animation: !isLarge,
    animationDuration: isLarge ? 0 : 300,
    tooltip,
    legend,
    grid,
    dataZoom,
    xAxis,
    yAxis: [
      {
        type: 'value',
        name: t('assetManagement.meter.unitKw'),
        nameLocation: 'end',
        nameGap: isEnlarged ? 14 : 10,
        scale: true,
        min: autoScalePowerMin,
        max: autoScalePowerMax,
        splitNumber,
        nameTextStyle: {
          align: 'left',
          fontWeight: 600,
        },
      },
      {
        type: 'value',
        name: t('assetManagement.meter.unitV'),
        nameLocation: 'end',
        nameGap: isEnlarged ? 14 : 10,
        scale: true,
        min: autoScaleVoltageMin,
        max: autoScaleVoltageMax,
        splitNumber,
        nameTextStyle: {
          align: 'right',
          fontWeight: 600,
        },
        splitLine: { show: false },
      },
    ],
    series: [
      {
        name: t('assetManagement.meter.singlePhasePower'),
        type: 'line',
        yAxisIndex: 0,
        smooth: true,
        showSymbol,
        symbol: 'circle',
        symbolSize,
        sampling: isLarge ? 'lttb' : undefined,
        connectNulls: false,
        areaStyle: {
          opacity: 0.12,
        },
        data: buildTimeSeriesData(props.records, (r) => r.power),
      },
      {
        name: t('assetManagement.meter.voltage'),
        type: 'line',
        yAxisIndex: 1,
        smooth: true,
        showSymbol,
        symbol: 'circle',
        symbolSize,
        sampling: isLarge ? 'lttb' : undefined,
        connectNulls: false,
        data: buildTimeSeriesData(props.records, (r) => r.voltage),
      },
    ],
  }
}

const option = computed<ChartOption>(() => buildOption(false))
const enlargedOption = computed<ChartOption>(() => buildOption(true))
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

      <div class="trend-actions-wrap">
        <div v-if="latestRecord" class="trend-chips">
          <template v-if="props.phase === '3P'">
            <div v-if="latestRecord.power != null" class="trend-chip">
              <span class="chip-dot dot-blue" />
              <span class="chip-label">{{ t('assetManagement.meter.realtimePower') }}</span>
              <span class="chip-val font-mono">{{ formatValueWithUnit(latestRecord.power, 'kW') }}</span>
            </div>
            <div v-if="latestRecord.currentA != null" class="trend-chip">
              <span class="chip-dot dot-amber" />
              <span class="chip-label">{{ t('assetManagement.meter.phaseA') }}</span>
              <span class="chip-val font-mono">{{ formatValueWithUnit(latestRecord.currentA, 'A') }}</span>
            </div>
            <div v-if="latestRecord.currentB != null" class="trend-chip">
              <span class="chip-dot dot-green" />
              <span class="chip-label">{{ t('assetManagement.meter.phaseB') }}</span>
              <span class="chip-val font-mono">{{ formatValueWithUnit(latestRecord.currentB, 'A') }}</span>
            </div>
            <div v-if="latestRecord.currentC != null" class="trend-chip">
              <span class="chip-dot dot-red" />
              <span class="chip-label">{{ t('assetManagement.meter.phaseC') }}</span>
              <span class="chip-val font-mono">{{ formatValueWithUnit(latestRecord.currentC, 'A') }}</span>
            </div>
          </template>
          <template v-else>
            <div v-if="latestRecord.power != null" class="trend-chip">
              <span class="chip-dot dot-blue" />
              <span class="chip-label">{{ t('assetManagement.meter.singlePhasePower') }}</span>
              <span class="chip-val font-mono">{{ formatValueWithUnit(latestRecord.power, 'kW') }}</span>
            </div>
            <div v-if="latestRecord.voltage != null" class="trend-chip">
              <span class="chip-dot dot-amber" />
              <span class="chip-label">{{ t('assetManagement.meter.voltage') }}</span>
              <span class="chip-val font-mono">{{ formatValueWithUnit(latestRecord.voltage, 'V') }}</span>
            </div>
          </template>
        </div>

        <ElTooltip :content="t('assetManagement.meter.zoomInTooltip')" placement="top">
          <ElButton
            class="zoom-btn"
            size="small"
            plain
            data-test="btn-trend-zoom-in"
            @click="openZoom"
          >
            <Maximize class="action-icon" />
            <span>{{ t('assetManagement.meter.zoomIn') }}</span>
          </ElButton>
        </ElTooltip>
      </div>
    </div>

    <div class="trend-toolbar">
      <div class="toolbar-left">
        <span class="toolbar-label">{{ t('assetManagement.meter.timeRange') }}</span>
        <ElRadioGroup
          v-model="selectedRange"
          size="small"
          class="range-radio-group"
          data-test="trend-range-selector"
          @change="handleRangeChange"
        >
          <ElRadioButton value="realtime">{{ t('assetManagement.meter.rangeRealtimeShort') }}</ElRadioButton>
          <ElRadioButton value="6h">{{ t('assetManagement.meter.range6h') }}</ElRadioButton>
          <ElRadioButton value="today">{{ t('assetManagement.meter.rangeToday') }}</ElRadioButton>
          <ElRadioButton value="24h">{{ t('assetManagement.meter.range24h') }}</ElRadioButton>
          <ElRadioButton value="custom">{{ t('assetManagement.meter.rangeCustom') }}</ElRadioButton>
        </ElRadioGroup>

        <ElDatePicker
          v-if="selectedRange === 'custom'"
          v-model="customDateRange"
          type="datetimerange"
          size="small"
          :range-separator="'-'"
          :start-placeholder="t('assetManagement.meter.customRangeStart')"
          :end-placeholder="t('assetManagement.meter.customRangeEnd')"
          :disabled-date="disableFutureDates"
          class="custom-range-picker"
          data-test="trend-custom-date-picker"
          @change="handleCustomDateChange"
        />

        <ElButton
          size="small"
          plain
          :loading="props.historyLoading"
          class="refresh-btn"
          data-test="btn-refresh-history"
          @click="handleRefresh"
        >
          <RefreshCw class="action-icon" />
          <span>{{ t('assetManagement.meter.refreshNow') }}</span>
        </ElButton>
      </div>

      <div class="toolbar-right">
        <ElTag
          v-if="props.apiPending"
          size="small"
          type="info"
          effect="plain"
          class="api-pending-badge"
          data-test="badge-api-pending"
        >
          <Info class="tip-icon-inline" />
          <span>{{ t('assetManagement.meter.historyPendingApi') }}</span>
        </ElTag>
        <span v-if="props.records.length > 0" class="points-count-badge font-mono">
          {{ t('assetManagement.meter.pointsCount', { count: props.records.length }) }}
        </span>
      </div>
    </div>

    <div
      class="chart-wrapper"
      :class="props.phase === '3P' ? 'three-phase' : 'single-phase'"
    >
      <ChartView
        :option="option"
        :loading="props.loading || props.historyLoading"
        :empty="props.records.length === 0"
        :accessible-label="t('assetManagement.meter.chartAccessible')"
      />
    </div>

    <ElDialog
      v-model="isZoomed"
      width="min(1440px, 96vw)"
      append-to-body
      destroy-on-close
      align-center
      :show-close="false"
      class="meter-zoom-dialog"
    >
      <template #header>
        <div class="zoom-dialog-header">
          <div class="zoom-dialog-title-group">
            <h3 class="zoom-dialog-title">
              {{ props.phase === '3P' ? t('assetManagement.meter.enlargedTrendTitleThree') : t('assetManagement.meter.enlargedTrendTitleSingle') }}
            </h3>
            <ElTag size="small" type="primary" effect="plain">
              {{ t('assetManagement.meter.fullscreenModeBadge') }}
            </ElTag>
          </div>

          <div class="zoom-dialog-header-right">
            <div v-if="latestRecord" class="trend-chips zoom-header-chips">
              <template v-if="props.phase === '3P'">
                <div v-if="latestRecord.power != null" class="trend-chip">
                  <span class="chip-dot dot-blue" />
                  <span class="chip-label">{{ t('assetManagement.meter.realtimePower') }}</span>
                  <span class="chip-val font-mono">{{ formatValueWithUnit(latestRecord.power, 'kW') }}</span>
                </div>
                <div v-if="latestRecord.currentA != null" class="trend-chip">
                  <span class="chip-dot dot-amber" />
                  <span class="chip-label">{{ t('assetManagement.meter.phaseA') }}</span>
                  <span class="chip-val font-mono">{{ formatValueWithUnit(latestRecord.currentA, 'A') }}</span>
                </div>
                <div v-if="latestRecord.currentB != null" class="trend-chip">
                  <span class="chip-dot dot-green" />
                  <span class="chip-label">{{ t('assetManagement.meter.phaseB') }}</span>
                  <span class="chip-val font-mono">{{ formatValueWithUnit(latestRecord.currentB, 'A') }}</span>
                </div>
                <div v-if="latestRecord.currentC != null" class="trend-chip">
                  <span class="chip-dot dot-red" />
                  <span class="chip-label">{{ t('assetManagement.meter.phaseC') }}</span>
                  <span class="chip-val font-mono">{{ formatValueWithUnit(latestRecord.currentC, 'A') }}</span>
                </div>
              </template>
              <template v-else>
                <div v-if="latestRecord.power != null" class="trend-chip">
                  <span class="chip-dot dot-blue" />
                  <span class="chip-label">{{ t('assetManagement.meter.singlePhasePower') }}</span>
                  <span class="chip-val font-mono">{{ formatValueWithUnit(latestRecord.power, 'kW') }}</span>
                </div>
                <div v-if="latestRecord.voltage != null" class="trend-chip">
                  <span class="chip-dot dot-amber" />
                  <span class="chip-label">{{ t('assetManagement.meter.voltage') }}</span>
                  <span class="chip-val font-mono">{{ formatValueWithUnit(latestRecord.voltage, 'V') }}</span>
                </div>
              </template>
            </div>

            <ElButton
              size="small"
              plain
              data-test="btn-trend-zoom-out"
              @click="closeZoom"
            >
              <Minimize class="action-icon" />
              <span>{{ t('assetManagement.meter.zoomOut') }}</span>
            </ElButton>
          </div>
        </div>
      </template>

      <!-- 大图内时间范围筛选工具栏 -->
      <div class="zoom-dialog-toolbar">
        <div class="toolbar-left">
          <span class="toolbar-label">{{ t('assetManagement.meter.timeRange') }}</span>
          <ElRadioGroup
            v-model="selectedRange"
            size="small"
            class="range-radio-group"
            @change="handleRangeChange"
          >
            <ElRadioButton value="realtime">{{ t('assetManagement.meter.rangeRealtimeShort') }}</ElRadioButton>
            <ElRadioButton value="6h">{{ t('assetManagement.meter.range6h') }}</ElRadioButton>
            <ElRadioButton value="today">{{ t('assetManagement.meter.rangeToday') }}</ElRadioButton>
            <ElRadioButton value="24h">{{ t('assetManagement.meter.range24h') }}</ElRadioButton>
            <ElRadioButton value="custom">{{ t('assetManagement.meter.rangeCustom') }}</ElRadioButton>
          </ElRadioGroup>

          <ElDatePicker
            v-if="selectedRange === 'custom'"
            v-model="customDateRange"
            type="datetimerange"
            size="small"
            :range-separator="'-'"
            :start-placeholder="t('assetManagement.meter.customRangeStart')"
            :end-placeholder="t('assetManagement.meter.customRangeEnd')"
            :disabled-date="disableFutureDates"
            class="custom-range-picker"
            @change="handleCustomDateChange"
          />

          <ElButton
            size="small"
            plain
            :loading="props.historyLoading"
            class="refresh-btn"
            @click="handleRefresh"
          >
            <RefreshCw class="action-icon" />
            <span>{{ t('assetManagement.meter.refreshNow') }}</span>
          </ElButton>
        </div>

        <div class="toolbar-right">
          <ElTag
            v-if="props.apiPending"
            size="small"
            type="info"
            effect="plain"
            class="api-pending-badge"
          >
            <Info class="tip-icon-inline" />
            <span>{{ t('assetManagement.meter.historyPendingApi') }}</span>
          </ElTag>
          <span v-if="props.records.length > 0" class="points-count-badge font-mono">
            {{ t('assetManagement.meter.pointsCount', { count: props.records.length }) }}
          </span>
        </div>
      </div>

      <div
        class="chart-wrapper enlarged-chart-wrapper"
        :class="props.phase === '3P' ? 'three-phase' : 'single-phase'"
      >
        <ChartView
          :option="enlargedOption"
          :loading="props.loading || props.historyLoading"
          :empty="props.records.length === 0"
          :accessible-label="t('assetManagement.meter.chartAccessible')"
        />
      </div>

      <div class="zoom-tips-bar">
        <Info class="tip-icon" />
        <span>{{ t('assetManagement.meter.zoomTips') }}</span>
      </div>
    </ElDialog>
  </div>
</template>

<style scoped>
.meter-trend-container {
  display: grid;
  gap: var(--bec-space-tight);
  background: var(--bec-color-surface-primary);
  border: var(--bec-border-width) solid var(--bec-color-divider);
  border-radius: var(--bec-management-radius);
  padding: var(--bec-space-group) var(--bec-space-group) var(--bec-space-tight) var(--bec-space-group);
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
  margin: var(--bec-ref-space-0);
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

.trend-actions-wrap {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--bec-space-tight);
}

.zoom-btn {
  display: inline-flex;
  align-items: center;
  gap: var(--bec-ref-space-4);
}

.action-icon {
  width: var(--bec-icon-small);
  height: var(--bec-icon-small);
}

.trend-chips {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--bec-space-tight);
}

.trend-chip {
  display: inline-flex;
  align-items: center;
  gap: var(--bec-ref-space-4);
  background: var(--bec-color-surface-secondary);
  border: var(--bec-border-width) solid var(--bec-color-divider);
  border-radius: var(--bec-radius-card);
  padding: var(--bec-ref-space-4) var(--bec-ref-space-8);
  font-size: var(--bec-font-size-small);
  color: var(--bec-color-text-secondary);
}

.chip-dot {
  width: var(--bec-ref-space-8);
  height: var(--bec-ref-space-8);
  border-radius: var(--bec-ref-radius-pill);
  display: inline-block;
  flex-shrink: 0;
}

.dot-blue { background-color: var(--bec-ref-blue); }
.dot-amber { background-color: var(--bec-ref-amber); }
.dot-green { background-color: var(--bec-ref-green); }
.dot-red { background-color: var(--bec-ref-red); }

.chip-label {
  color: var(--bec-color-text-secondary);
}

.chip-val {
  font-family: var(--bec-font-family-number);
  font-weight: var(--bec-font-weight-heading);
  color: var(--bec-color-text-primary);
}

.chart-wrapper {
  height: var(--bec-chart-height);
  width: 100%;
  min-width: var(--bec-ref-space-0);
}

.enlarged-chart-wrapper {
  height: calc(var(--bec-ref-space-64) * 8 + var(--bec-ref-space-32));
  width: 100%;
  min-width: var(--bec-ref-space-0);
}

.zoom-dialog-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: var(--bec-space-tight);
  padding-right: var(--bec-ref-space-8);
}

.zoom-dialog-title-group {
  display: inline-flex;
  align-items: center;
  gap: var(--bec-ref-space-8);
}

.zoom-dialog-title {
  margin: var(--bec-ref-space-0);
  font-size: var(--bec-font-size-title);
  font-weight: var(--bec-font-weight-heading);
  color: var(--bec-color-text-primary);
}

.zoom-dialog-header-right {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--bec-space-tight);
}

.zoom-tips-bar {
  display: flex;
  align-items: center;
  gap: var(--bec-ref-space-8);
  margin-top: var(--bec-space-tight);
  padding: var(--bec-ref-space-8) var(--bec-ref-space-12);
  background-color: var(--bec-color-surface-secondary);
  border-radius: var(--bec-radius-card);
  font-size: var(--bec-font-size-small);
  color: var(--bec-color-text-secondary);
}

.tip-icon {
  width: var(--bec-icon-small);
  height: var(--bec-icon-small);
  flex-shrink: 0;
  color: var(--bec-ref-blue);
}

.trend-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: var(--bec-space-tight);
  padding-block: var(--bec-ref-space-4);
}

.zoom-dialog-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: var(--bec-space-tight);
  margin-bottom: var(--bec-space-tight);
  padding-block: var(--bec-ref-space-4);
}

.toolbar-left {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--bec-ref-space-8);
}

.toolbar-right {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--bec-ref-space-8);
}

.toolbar-label {
  font-size: var(--bec-font-size-small);
  color: var(--bec-color-text-secondary);
  font-weight: var(--bec-font-weight-heading);
}

.range-radio-group {
  display: inline-flex;
}

.custom-range-picker {
  max-width: calc(var(--bec-ref-space-64) * 4);
}

.refresh-btn {
  display: inline-flex;
  align-items: center;
  gap: var(--bec-ref-space-4);
}

.api-pending-badge {
  display: inline-flex;
  align-items: center;
  gap: var(--bec-ref-space-4);
}

.tip-icon-inline {
  width: var(--bec-icon-small);
  height: var(--bec-icon-small);
}

.points-count-badge {
  font-size: var(--bec-font-size-small);
  color: var(--bec-color-text-secondary);
  background-color: var(--bec-color-surface-secondary);
  border: var(--bec-border-width) solid var(--bec-color-divider);
  border-radius: var(--bec-radius-card);
  padding: var(--bec-ref-space-4) var(--bec-ref-space-8);
}

/* 区分三相电表曲线与图例色彩：总功率=蓝，A相=黄/琥珀，B相=绿，C相=红 */
.chart-wrapper.three-phase {
  --bec-chart-series-1: var(--bec-ref-blue);
  --bec-chart-series-2: var(--bec-ref-amber);
  --bec-chart-series-3: var(--bec-ref-green);
  --bec-chart-series-4: var(--bec-ref-red);
}

/* 单相电表曲线与图例色彩：功率=蓝，工作电压=黄/琥珀 */
.chart-wrapper.single-phase {
  --bec-chart-series-1: var(--bec-ref-blue);
  --bec-chart-series-2: var(--bec-ref-amber);
}
</style>
