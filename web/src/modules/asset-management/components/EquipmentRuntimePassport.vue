<script setup lang="ts">
import { computed } from 'vue'
import ChartView from '@/shared/charts/ChartView.vue'
import type { ChartOption } from '@/shared/charts/echarts'
import {
  Activity,
  Cable,
  ChartNoAxesCombined,
  ChevronDown,
  CircleAlert,
  ElAlert,
  ElButton,
  FileClock,
  PanelLeftClose,
  Wrench,
} from '@/shared/ui'
import { formatDateTime } from '@/shared/utils/format'
import { t } from '@/locales'
import type {
  AssetEquipmentDetail,
  AssetEquipmentReadings,
  AssetEquipmentTrendHistory,
  AssetPointReading,
  AssetPointTrendSeries,
} from '../models/assets'
import {
  isLedgerCommunicationDelayed,
  ledgerCommunicationDisplayValue,
  ledgerCommunicationReasons,
  type AssetLedgerItem,
  type LedgerCommunicationState,
  type LedgerOperationState,
} from '../models/ledger'
import { formatLedgerPointNumber, formatLedgerPointValue, ledgerPointDisplay } from '../models/point-display'
import { peopleTrendAxis, presentRuntimeTrend, runtimeTrendAxis } from '../models/runtime-trend'
import { roomSensorDoorValue, roomSensorEvidence, roomSensorName, roomSensorValue } from '../models/room-sensor-display'

const props = withDefaults(defineProps<{
  equipment: AssetEquipmentDetail | null
  ledgerItem?: AssetLedgerItem | null
  readings: AssetEquipmentReadings | null
  trend: AssetEquipmentTrendHistory | null
  loading?: boolean
  error?: string | null
  readingsLoading?: boolean
  readingsError?: string | null
  trendLoading?: boolean
  trendError?: string | null
  selectedPointId?: string
}>(), {
  ledgerItem: null,
  loading: false,
  error: null,
  readingsLoading: false,
  readingsError: null,
  trendLoading: false,
  trendError: null,
  selectedPointId: undefined,
})

const emit = defineEmits<{
  (event: 'selectPoint', pointId: string): void
  (event: 'archive'): void
  (event: 'collapse'): void
}>()

const text = (key: string, params?: Record<string, string | number>) =>
  t(`assetManagement.ledgerRuntime.${key}`, params)

const readingsForEquipment = computed<AssetEquipmentReadings | null>(() => {
  if (!props.equipment || props.readings?.equipmentId !== props.equipment.equipmentId || props.readings.buildingId !== props.equipment.buildingId) return null
  return props.readings
})

const ledgerForEquipment = computed<AssetLedgerItem | null>(() => {
  if (!props.equipment || !props.ledgerItem) return null
  return props.ledgerItem.equipment.equipmentId === props.equipment.equipmentId
    && props.ledgerItem.equipment.buildingId === props.equipment.buildingId
    ? props.ledgerItem
    : null
})

const operationState = computed(() => ledgerForEquipment.value?.operation ?? null)

// 设备通讯只信任后端按设备身份聚合的结果；控制器连接、档案启用和读数存在均不能证明设备在线。
const communicationState = computed(() => ledgerForEquipment.value?.communication ?? null)

const points = computed(() => readingsForEquipment.value?.points ?? [])
const roomSensors = computed(() => readingsForEquipment.value?.roomSensors ?? [])
const trendSensor = computed(() => selectedPoint.value ? null : ledgerForEquipment.value?.roomSensor ?? null)
const readingCards = computed(() => points.value.map(point => {
  const { unit, multiplier } = ledgerPointDisplay(point.unit, props.equipment?.category)
  // 使用状态先于单位换算，避免展示后端意外携带的阻断值。
  const allowedValue = point.usageStatus.trim().toUpperCase() === 'AVAILABLE' ? point.value : null
  return {
    point,
    unit,
    value: allowedValue != null && Number.isFinite(allowedValue)
      ? formatLedgerPointNumber(allowedValue * multiplier, unit)
      : text('unavailable'),
  }
}))

const selectedPoint = computed<AssetPointReading | null>(() => {
  if (!props.selectedPointId) return null
  return points.value.find(point => point.pointId === props.selectedPointId) ?? null
})

const trendForEquipment = computed<AssetEquipmentTrendHistory | null>(() => {
  if (!props.equipment || props.trend?.equipmentId !== props.equipment.equipmentId
    || (props.trend.buildingId != null && props.trend.buildingId !== props.equipment.buildingId)) return null
  return props.trend
})

const sensorHistory = computed(() => {
  const sensor = trendSensor.value
  return sensor ? trendForEquipment.value?.roomSensors?.find(item => item.sourceId === sensor.sourceId && item.kind === sensor.kind) : undefined
})
const doorHistory = computed(() => sensorHistory.value?.kind === 'DOOR' ? sensorHistory.value.samples.slice().reverse() : [])

// 历史接口按测点编码返回；未显式选择时不能擅自把首条曲线当作主测点。
const selectedSeries = computed<AssetPointTrendSeries | null>(() => {
  if (!selectedPoint.value) return null
  return trendForEquipment.value?.series.find(series => series.pointCode === selectedPoint.value?.pointCode) ?? null
})

const trendRange = computed(() => {
  const trend = trendForEquipment.value
  if (!trend) return null
  const start = Date.parse(trend.startTime)
  const end = Date.parse(trend.endTime)
  return Number.isFinite(start) && Number.isFinite(end) && start < end ? { start, end } : null
})

const trendTitle = computed(() => {
  if (trendSensor.value) return text(trendSensor.value.kind === 'DOOR' ? 'roomSensor.doorHistory' : 'roomSensor.radarHistory')
  return selectedPoint.value ? text('trendPointTitle', { name: selectedPoint.value.pointName }) : text('trendTitle')
})
const trendDisplay = computed(() => {
  const history = sensorHistory.value
  const range = trendRange.value
  if (history?.kind !== 'RADAR' || !range) return presentRuntimeTrend(selectedSeries.value, props.equipment?.category)
  const interval = history.intervalSeconds * 1000
  const data: AssetPointTrendSeries['data'] = []
  // 服务端返回每个非空时间桶的最后一次人数。空桶断线，保留实际观测时间和整数人数。
  let previousBucket = -1
  for (const sample of history.samples) {
    const bucket = Math.floor((sample.observedAt - range.start) / interval)
    if (previousBucket >= 0 && bucket > previousBucket + 1) data.push([range.start + (previousBucket + 1) * interval, null])
    data.push([sample.observedAt, sample.count])
    previousBucket = bucket
  }
  return {
    data,
    unit: text('roomSensor.peopleUnit'),
    lastValidAt: history.samples.at(-1)?.observedAt ?? null,
    hasGaps: data.some(([, value]) => value == null),
  }
})

const equipmentLocation = computed(() => {
  if (!props.equipment) return text('unconfigured')
  const values = [props.equipment.buildingName, props.equipment.spaceName]
    .filter((value): value is string => Boolean(value?.trim()))
  return values.length > 0 ? values.join(' / ') : text('unconfigured')
})

const trendOption = computed<ChartOption>(() => {
  const series = selectedSeries.value
  const radar = sensorHistory.value?.kind === 'RADAR'
  const name = radar ? text('roomSensor.radar') : series?.pointName
  const range = trendRange.value
  const { data, unit } = trendDisplay.value
  const axis = radar ? peopleTrendAxis(data) : runtimeTrendAxis(data, unit, {
    pointCode: series?.pointCode,
    category: props.equipment?.category,
  })
  const spansDays = range != null && new Date(range.start).toDateString() !== new Date(range.end).toDateString()

  return {
    animation: false,
    grid: {
      left: '4%',
      right: '4%',
      top: '12%',
      bottom: '16%',
      containLabel: true,
    },
    tooltip: {
      trigger: 'axis',
      renderMode: 'richText',
      axisPointer: { type: 'line' },
      formatter: (params: unknown) => formatTrendTooltip(params, name, unit),
    },
    xAxis: {
      type: 'time',
      // 首个分钟桶可能只覆盖请求起点之后的部分，仍保留该桶的起始时间。
      min: range ? Math.min(range.start, data[0]?.[0] ?? range.start) : undefined,
      max: range?.end,
      splitNumber: 4,
      axisLabel: {
        hideOverlap: true,
        formatter: (value: number | string) => formatTrendTick(value, spansDays),
      },
    },
    yAxis: {
      type: 'value',
      name: unit,
      scale: true,
      min: axis?.min,
      max: axis?.max,
      interval: axis?.interval,
      axisLabel: { formatter: axis?.formatter },
    },
    series: name
      ? [{
          name,
          type: 'line',
          step: radar ? 'end' : undefined,
          data,
          connectNulls: false,
          showSymbol: true,
          symbol: 'circle',
          symbolSize: (_value: unknown, { dataIndex }: { dataIndex: number }) =>
            data.length <= 100 || (data[dataIndex - 1]?.[1] == null && data[dataIndex + 1]?.[1] == null) ? 4 : 0,
        }]
      : [],
  } as ChartOption
})

function formatNumber(value: number | null | undefined): string {
  if (value == null || !Number.isFinite(value)) return text('unavailable')
  return new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 2 }).format(value)
}

function formatEventTime(value: number | null | undefined): string {
  return value == null || !Number.isFinite(value) ? text('unavailable') : formatDateTime(value)
}

function formatTrendTick(value: number | string, spansDays: boolean): string {
  const time = Number(value)
  if (!Number.isFinite(time)) return text('unavailable')
  const date = new Date(time)
  return new Intl.DateTimeFormat('zh-CN', {
    ...(spansDays ? { month: '2-digit', day: '2-digit' } as const : {}),
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(date)
}

function formatTrendTooltip(params: unknown, pointName: string | undefined, unit: string): string {
  const item = Array.isArray(params) ? params[0] : params
  const value = item && typeof item === 'object'
    ? (item as { data?: unknown; value?: unknown }).data ?? (item as { value?: unknown }).value
    : null
  if (!Array.isArray(value) || typeof value[0] !== 'number') return text('unavailable')
  if (typeof value[1] !== 'number' || !Number.isFinite(value[1])) {
    return [formatEventTime(value[0]), text('trendGap')].join('\n')
  }
  return [
    formatEventTime(value[0]),
    text('trendTooltip', {
      name: pointName ?? text('unavailable'),
      value: formatLedgerPointValue(value[1], unit),
    }),
  ].join('\n')
}

function qualityKey(dataQuality: number | null): 'q0' | 'q1' | 'q2' | 'unknown' {
  if (dataQuality === 0) return 'q0'
  if (dataQuality === 1) return 'q1'
  if (dataQuality === 2) return 'q2'
  return 'unknown'
}

function usageKey(status: string | null | undefined): 'available' | 'qualityBlocked' | 'policySnapshotUnavailable' | 'unknown' {
  switch (status?.trim().toUpperCase()) {
    case 'AVAILABLE': return 'available'
    case 'QUALITY_BLOCKED': return 'qualityBlocked'
    case 'POLICY_SNAPSHOT_UNAVAILABLE': return 'policySnapshotUnavailable'
    default: return 'unknown'
  }
}

function statusValue(value: string | null | undefined): string {
  return value?.trim() || text('unavailable')
}

function parameterValue(value: string | number | null | undefined): string {
  if (typeof value === 'number') return formatNumber(value)
  return value?.trim() || text('unavailable')
}

function operationStateText(state: LedgerOperationState): string {
  return text(`operation.${state.scope}.${state.value}`)
}

function communicationStateText(state: LedgerCommunicationState | null): string {
  const displayValue = isLedgerCommunicationDelayed(state) ? 'ONLINE_DELAYED' : ledgerCommunicationDisplayValue(state)
  return text(`communication.${displayValue}`)
}

function operationStateEvidence(state: LedgerOperationState): string {
  const knownReasons = new Set(['VALID_OBSERVATION', 'IDENTITY_CONFLICT', 'STATE_NOT_CONNECTED', 'STATE_UNAVAILABLE_OR_STALE', 'UNRECOGNIZED_STATE', 'VENDOR_COMMUNICATION_ERROR', 'ONLINE_RULE_NOT_CONFIRMED', 'NO_DEVICE_ONLINE_SIGNAL'])
  const reason = text(`stateReason.${knownReasons.has(state.reason) ? state.reason : 'UNKNOWN'}`)
  const values = [reason, state.observedAt == null ? null : formatEventTime(state.observedAt)]
    .filter((value): value is string => Boolean(value?.trim()))
  return values.join(' · ')
}

function communicationStateEvidence(state: LedgerCommunicationState): string {
  const reasonKey = ledgerCommunicationReasons.includes(state.reason as typeof ledgerCommunicationReasons[number])
    ? state.reason
    : 'UNKNOWN'
  const reason = text(`communicationReason.${reasonKey}`)
  const values = [reason, state.observedAt == null ? null : formatEventTime(state.observedAt)]
    .filter((value): value is string => Boolean(value?.trim()))
  return values.join(' · ')
}

function selectPoint(pointId: string): void {
  emit('selectPoint', pointId)
}
</script>

<template>
  <aside class="runtime-passport" :aria-busy="loading" data-test="runtime-passport">
    <header class="runtime-passport__header">
      <div class="runtime-passport__identity">
        <div class="runtime-passport__title-row">
          <Activity aria-hidden="true" />
          <h2>{{ equipment?.equipmentName ?? text('title') }}</h2>
        </div>
        <p>{{ equipment ? `${equipment.equipmentCode ?? text('unavailable')} · ${equipment.productName ?? equipment.typeCode}` : text('title') }}</p>
      </div>
      <div class="runtime-passport__header-actions">
        <span class="runtime-passport__communication"><Cable aria-hidden="true" />{{ text('communicationHeader', { state: communicationStateText(communicationState) }) }}</span>
        <ElButton text size="small" :aria-label="text('collapseDetail')" @click="emit('collapse')">
          <PanelLeftClose aria-hidden="true" />
          <span>{{ text('collapseDetail') }}</span>
        </ElButton>
      </div>
    </header>

    <div class="runtime-passport__scroll">
      <div v-if="loading" class="runtime-passport__state" data-test="passport-loading">
        {{ text('loading') }}
      </div>
      <ElAlert
        v-else-if="error"
        class="runtime-passport__alert"
        :closable="false"
        :title="text('loadFailed')"
        :description="error"
        type="error"
        show-icon
      />
      <div v-else-if="!equipment" class="runtime-passport__state" data-test="passport-empty">
        {{ text('emptySelection') }}
      </div>
      <template v-else>
        <section class="runtime-passport__location" data-test="passport-location">
          <span>{{ text('location') }}</span>
          <strong>{{ equipmentLocation }}</strong>
        </section>

        <section class="runtime-passport__section">
          <div class="runtime-passport__section-heading">
            <Activity aria-hidden="true" />
            <h3>{{ text('realtimeTitle') }}</h3>
          </div>
          <div class="runtime-passport__status-grid">
            <div v-if="operationState?.applicable" data-test="operation-state">
              <span>{{ text('runningStatus') }}</span>
              <strong>{{ operationStateText(operationState) }}</strong>
              <small v-if="operationStateEvidence(operationState)">{{ operationStateEvidence(operationState) }}</small>
            </div>
            <div data-test="communication-state">
              <span>{{ text('communicationStatus') }}</span>
              <strong>{{ communicationStateText(communicationState) }}</strong>
              <small v-if="communicationState && communicationStateEvidence(communicationState)">{{ communicationStateEvidence(communicationState) }}</small>
            </div>
          </div>

          <div v-if="readingsLoading" class="runtime-passport__substate" data-test="readings-loading">
            {{ text('readingLoading') }}
          </div>
          <ElAlert
            v-else-if="readingsError"
            class="runtime-passport__alert"
            :closable="false"
            :title="text('readingsLoadFailed')"
            :description="readingsError"
            type="error"
            show-icon
          />
          <template v-else>
            <div v-if="roomSensors.length" class="runtime-passport__status-grid">
              <div v-for="sensor in roomSensors" :key="sensor.sourceId" data-test="room-sensor-reading">
                <span>{{ roomSensorName(sensor) }}</span>
                <strong data-test="room-sensor-value">{{ roomSensorValue(sensor) }}</strong>
                <small>{{ roomSensorEvidence(sensor) }}</small>
              </div>
            </div>
            <div v-if="readingCards.length" class="runtime-passport__reading-grid" data-test="reading-grid">
              <button
                v-for="{ point, value, unit } in readingCards"
                :key="point.pointId"
                class="runtime-passport__reading-card"
                :class="{ 'runtime-passport__reading-card--selected': point.pointId === selectedPointId }"
                type="button"
                :aria-pressed="point.pointId === selectedPointId"
                data-test="reading-card"
                @click="selectPoint(point.pointId)"
              >
                <span class="runtime-passport__reading-name">{{ point.pointName || point.pointCode }}</span>
                <strong class="runtime-passport__reading-value" data-test="reading-value">
                  {{ value }}
                  <small v-if="unit">{{ unit }}</small>
                </strong>
                <span class="runtime-passport__quality" :class="`runtime-passport__quality--${qualityKey(point.dataQuality)}`">
                  {{ text(`quality.${qualityKey(point.dataQuality)}`) }}
                </span>
                <span class="runtime-passport__reading-time">{{ formatEventTime(point.eventTime) }}</span>
                <span class="runtime-passport__reading-evidence">
                  {{ text(`usage.${usageKey(point.usageStatus)}`) }}
                </span>
              </button>
            </div>
            <div v-else-if="!roomSensors.length" class="runtime-passport__substate" data-test="readings-empty">
              {{ text('noReadings') }}
            </div>
          </template>
        </section>

        <section class="runtime-passport__section">
          <div class="runtime-passport__section-heading">
            <ChartNoAxesCombined aria-hidden="true" />
            <h3 data-test="trend-title">{{ trendTitle }}</h3>
          </div>
          <p v-if="trendSensor?.kind === 'DOOR'" class="runtime-passport__trend-range">{{ text('roomSensor.doorPeriod') }}</p>
          <p v-else-if="sensorHistory?.kind === 'RADAR'" class="runtime-passport__trend-range">{{ text('roomSensor.radarPeriod', { seconds: sensorHistory.intervalSeconds }) }}</p>
          <p v-else-if="selectedPoint" class="runtime-passport__trend-range">{{ text('trendPeriod') }}</p>
          <div v-if="!selectedPoint && !trendSensor" class="runtime-passport__chart-state" data-test="trend-no-selection">
            {{ text('trendSelectPoint') }}
          </div>
          <div v-else-if="trendLoading" class="runtime-passport__chart-state" data-test="trend-loading">
            {{ text('trendLoading') }}
          </div>
          <ElAlert
            v-else-if="trendError"
            class="runtime-passport__alert"
            :closable="false"
            :title="text('trendLoadFailed')"
            :description="trendError"
            type="error"
            show-icon
          />
          <template v-else-if="trendSensor?.kind === 'DOOR' && doorHistory.length">
            <ul class="runtime-passport__door-history" data-test="door-history">
              <li v-for="(reading, index) in doorHistory" :key="`${reading.observedAt}:${index}`">
                <span>{{ formatEventTime(reading.observedAt) }}</span>
                <strong>{{ roomSensorDoorValue(reading.open) }}</strong>
              </li>
            </ul>
          </template>
          <div v-else-if="trendDisplay.lastValidAt == null" class="runtime-passport__chart-state" data-test="trend-empty">
            {{ text(trendSensor ? 'roomSensor.historyEmpty' : 'trendNoData') }}
          </div>
          <template v-else>
            <ChartView
              class="runtime-passport__chart"
              :option="trendOption"
              :accessible-label="trendTitle"
            />
            <p v-if="trendRange" class="runtime-passport__trend-range" data-test="trend-range">
              {{ text('trendRange', { start: formatEventTime(trendRange.start), end: formatEventTime(trendRange.end) }) }}
            </p>
            <p class="runtime-passport__trend-range" data-test="trend-last">
              {{ text('trendLast', { time: formatEventTime(trendDisplay.lastValidAt) }) }}
            </p>
            <p v-if="trendDisplay.hasGaps" class="runtime-passport__trend-range" data-test="trend-gap">
              {{ text('trendGapNote') }}
            </p>
          </template>
          <p v-if="sensorHistory?.truncated" class="runtime-passport__trend-range">{{ text('roomSensor.historyTruncated') }}</p>
        </section>

        <details class="runtime-passport__details">
          <summary>
            <span><FileClock aria-hidden="true" />{{ text('pointDetails') }}</span>
            <ChevronDown class="runtime-passport__details-icon" aria-hidden="true" />
          </summary>
          <p>{{ text('pointDetailsHint') }}</p>
          <div v-if="points.length" class="runtime-passport__detail-list">
            <article v-for="point in points" :key="point.pointId" class="runtime-passport__detail-row">
              <div>
                <strong>{{ point.pointName || point.pointCode }}</strong>
                <span>{{ point.pointCode }}</span>
              </div>
              <dl>
                <div><dt>{{ text('pointStatus') }}</dt><dd>{{ statusValue(point.status) }}</dd></div>
                <div><dt>{{ text('eventTime') }}</dt><dd>{{ formatEventTime(point.eventTime) }}</dd></div>
                <div><dt>{{ text('usageStatus') }}</dt><dd>{{ text(`usage.${usageKey(point.usageStatus)}`) }}{{ ' · ' }}{{ statusValue(point.usageStatus) }}</dd></div>
                <div><dt>{{ text('reason') }}</dt><dd>{{ statusValue(point.reason) }}</dd></div>
              </dl>
            </article>
          </div>
          <div v-else class="runtime-passport__substate">{{ text('noReadings') }}</div>
        </details>

        <section class="runtime-passport__section">
          <div class="runtime-passport__section-heading">
            <FileClock aria-hidden="true" />
            <h3>{{ text('nameplateTitle') }}</h3>
          </div>
          <dl class="runtime-passport__nameplate">
            <div><dt>{{ text('manufacturer') }}</dt><dd>{{ parameterValue(equipment.manufacturer) }}</dd></div>
            <div><dt>{{ text('ratedCapacity') }}</dt><dd>{{ parameterValue(equipment.ratedCapacity) }}</dd></div>
            <div><dt>{{ text('ratedPower') }}</dt><dd>{{ parameterValue(equipment.ratedPower) }}</dd></div>
            <div><dt>{{ text('designCop') }}</dt><dd>{{ parameterValue(equipment.designCop) }}</dd></div>
          </dl>
        </section>

        <section class="runtime-passport__gaps">
          <article>
            <CircleAlert aria-hidden="true" />
            <div><h3>{{ text('relatedExceptionsTitle') }}</h3><p>{{ text('relatedExceptionsHint') }}</p></div>
          </article>
          <article>
            <Wrench aria-hidden="true" />
            <div><h3>{{ text('maintenanceTitle') }}</h3><p>{{ text('maintenanceHint') }}</p></div>
          </article>
          <article>
            <FileClock aria-hidden="true" />
            <div><h3>{{ text('workOrdersTitle') }}</h3><p>{{ text('workOrdersHint') }}</p></div>
          </article>
        </section>
      </template>
    </div>

    <footer class="runtime-passport__footer">
      <p class="runtime-passport__footer-hint"><Cable aria-hidden="true" />{{ text('createWorkOrderHint') }}{{ ' · ' }}{{ text('locateSpaceHint') }}</p>
      <div class="runtime-passport__actions">
        <ElButton type="primary" disabled>{{ text('createWorkOrder') }}</ElButton>
        <ElButton disabled>{{ text('locateSpace') }}</ElButton>
        <ElButton :disabled="!equipment" @click="emit('archive')">{{ text('fullArchive') }}</ElButton>
      </div>
    </footer>
  </aside>
</template>

<style scoped>
.runtime-passport {
  display: flex;
  flex-direction: column;
  width: 100%;
  height: 100%;
  min-height: 0;
  overflow: hidden;
  background: var(--bec-color-surface);
  border: var(--bec-border-width) solid var(--bec-color-border);
  border-radius: var(--bec-radius-card);
  color: var(--bec-color-text-primary);
  font-family: var(--bec-font-family-body);
}

.runtime-passport__header,
.runtime-passport__footer {
  flex-shrink: 0;
  padding: var(--bec-space-group);
}

.runtime-passport__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--bec-space-tight);
  border-bottom: var(--bec-border-width) solid var(--bec-color-divider);
}

.runtime-passport__identity,
.runtime-passport__header-actions,
.runtime-passport__section,
.runtime-passport__gaps,
.runtime-passport__details,
.runtime-passport__location {
  min-width: 0;
}

.runtime-passport__title-row,
.runtime-passport__section-heading,
.runtime-passport__communication,
.runtime-passport__footer-hint,
.runtime-passport__details summary > span,
.runtime-passport__gaps article {
  display: flex;
  align-items: center;
  gap: var(--bec-space-tight);
}

.runtime-passport__title-row > svg,
.runtime-passport__section-heading > svg,
.runtime-passport__gaps article > svg {
  flex-shrink: 0;
  width: var(--bec-icon-small);
  height: var(--bec-icon-small);
  color: var(--bec-color-action-primary);
}

.runtime-passport__title-row h2,
.runtime-passport__section-heading h3,
.runtime-passport__gaps h3,
.runtime-passport__identity p,
.runtime-passport__trend-range,
.runtime-passport__details p,
.runtime-passport__footer-hint {
  margin: 0;
}

.runtime-passport__title-row h2 {
  min-width: 0;
  overflow-wrap: anywhere;
  font-size: var(--bec-ref-font-18);
  font-weight: var(--bec-font-weight-heading);
}

.runtime-passport__identity p,
.runtime-passport__communication,
.runtime-passport__footer-hint,
.runtime-passport__trend-range,
.runtime-passport__details p,
.runtime-passport__gaps p {
  color: var(--bec-color-text-secondary);
  font-size: var(--bec-ref-font-12);
  line-height: var(--bec-line-height);
}

.runtime-passport__identity p {
  margin-top: var(--bec-ref-space-4);
  overflow-wrap: anywhere;
}

.runtime-passport__header-actions {
  display: grid;
  justify-items: end;
  gap: var(--bec-ref-space-4);
  flex-shrink: 0;
}

.runtime-passport__communication {
  padding: var(--bec-ref-space-4) var(--bec-space-tight);
  border-radius: var(--bec-radius-tag);
  background: var(--bec-color-surface-secondary);
}

.runtime-passport__communication > svg,
.runtime-passport__footer-hint > svg {
  width: var(--bec-icon-small);
  height: var(--bec-icon-small);
}

.runtime-passport__header-actions :deep(.el-button) {
  margin: 0;
  font-size: var(--bec-ref-font-12);
}

.runtime-passport__header-actions :deep(.el-button svg) {
  width: var(--bec-icon-small);
  height: var(--bec-icon-small);
}

.runtime-passport__scroll {
  display: grid;
  align-content: start;
  flex: 1;
  min-height: 0;
  overflow: auto;
}

.runtime-passport__state,
.runtime-passport__substate,
.runtime-passport__chart-state {
  padding: var(--bec-space-group);
  color: var(--bec-color-text-secondary);
  font-size: var(--bec-ref-font-12);
  line-height: var(--bec-line-height);
}

.runtime-passport__alert {
  margin: var(--bec-space-group);
}

.runtime-passport__location {
  display: flex;
  gap: var(--bec-ref-space-4);
  padding: var(--bec-space-group);
  border-bottom: var(--bec-border-width) solid var(--bec-color-divider);
}
.runtime-passport__location > span { display: none; }

.runtime-passport__location span,
.runtime-passport__status-grid span,
.runtime-passport__detail-row dt,
.runtime-passport__nameplate dt {
  color: var(--bec-color-text-secondary);
  font-size: var(--bec-ref-font-12);
}

.runtime-passport__location strong {
  overflow-wrap: anywhere;
  font-size: var(--bec-ref-font-14);
  font-weight: var(--bec-font-weight-heading);
}

.runtime-passport__section,
.runtime-passport__details,
.runtime-passport__gaps {
  padding: var(--bec-space-group);
  border-bottom: var(--bec-border-width) solid var(--bec-color-divider);
}

.runtime-passport__section {
  display: grid;
  gap: var(--bec-space-tight);
  padding-block: var(--bec-space-tight);
}

.runtime-passport__section-heading h3,
.runtime-passport__gaps h3 {
  font-size: var(--bec-ref-font-14);
  font-weight: var(--bec-font-weight-heading);
}

.runtime-passport__status-grid,
.runtime-passport__reading-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, calc(var(--bec-ref-space-64) * 2)), 1fr));
  gap: var(--bec-space-tight);
}

.runtime-passport__status-grid > div,
.runtime-passport__reading-card {
  display: grid;
  gap: var(--bec-ref-space-4);
  padding: var(--bec-space-tight);
  background: var(--bec-color-surface-secondary);
  border: var(--bec-border-width) solid var(--bec-color-divider);
  border-radius: var(--bec-radius-card);
}

.runtime-passport__status-grid strong {
  font-size: var(--bec-ref-font-14);
  font-weight: var(--bec-font-weight-heading);
}

.runtime-passport__status-grid small {
  color: var(--bec-color-text-secondary);
  overflow-wrap: anywhere;
}

.runtime-passport__reading-card {
  min-width: 0;
  color: var(--bec-color-text-primary);
  font-family: var(--bec-font-family-body);
  text-align: left;
  cursor: pointer;
}

.runtime-passport__reading-card--selected {
  border-color: var(--bec-color-action-primary);
  background: var(--bec-color-action-soft);
}

.runtime-passport__reading-card:focus-visible,
.runtime-passport__details summary:focus-visible {
  outline: var(--bec-focus-width) solid var(--bec-color-action-primary);
  outline-offset: var(--bec-focus-offset);
}

.runtime-passport__reading-name,
.runtime-passport__reading-time,
.runtime-passport__reading-evidence {
  overflow-wrap: anywhere;
  font-size: var(--bec-ref-font-12);
  line-height: var(--bec-line-height);
}

.runtime-passport__reading-name {
  color: var(--bec-color-text-secondary);
}

.runtime-passport__reading-value {
  overflow-wrap: anywhere;
  font-family: var(--bec-font-family-number);
  font-size: var(--bec-ref-font-18);
  font-weight: var(--bec-font-weight-heading);
}

.runtime-passport__reading-value small {
  margin-left: var(--bec-ref-space-4);
  color: var(--bec-color-text-secondary);
  font-family: var(--bec-font-family-body);
  font-size: var(--bec-ref-font-12);
  font-weight: var(--bec-font-weight-normal);
}

.runtime-passport__quality {
  justify-self: start;
  padding: var(--bec-ref-space-4) var(--bec-space-tight);
  border-radius: var(--bec-radius-tag);
  font-size: var(--bec-ref-font-12);
  line-height: var(--bec-line-height);
}

.runtime-passport__quality--q0 { background: var(--bec-color-success); color: var(--bec-color-on-action); }
.runtime-passport__quality--q1 { background: var(--bec-color-action-soft); color: var(--bec-color-action-primary); }
.runtime-passport__quality--q2,
.runtime-passport__quality--unknown { background: var(--bec-color-surface-secondary); color: var(--bec-color-text-secondary); }
.runtime-passport__reading-time,
.runtime-passport__reading-evidence { color: var(--bec-color-text-secondary); }

.runtime-passport__chart {
  height: var(--bec-chart-height);
}

.runtime-passport__trend-range {
  overflow-wrap: anywhere;
}

.runtime-passport__door-history {
  list-style: none;
  padding: 0;
  margin: 0;
  max-height: var(--bec-chart-height);
  overflow: auto;
}

.runtime-passport__door-history li {
  display: flex;
  justify-content: space-between;
  gap: var(--bec-space-tight);
  padding: var(--bec-space-tight) 0;
  border-bottom: var(--bec-border-width) solid var(--bec-color-divider);
}

.runtime-passport__details summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--bec-space-tight);
  cursor: pointer;
  font-size: var(--bec-ref-font-14);
  font-weight: var(--bec-font-weight-heading);
}

.runtime-passport__details summary::-webkit-details-marker { display: none; }
.runtime-passport__details-icon { width: var(--bec-icon-small); height: var(--bec-icon-small); }
.runtime-passport__details[open] .runtime-passport__details-icon { transform: rotate(180deg); }
.runtime-passport__details p { margin-top: var(--bec-space-tight); }

.runtime-passport__detail-list,
.runtime-passport__nameplate {
  display: grid;
  gap: var(--bec-space-tight);
  margin: var(--bec-space-tight) 0 0;
}

.runtime-passport__detail-row,
.runtime-passport__nameplate > div {
  display: grid;
  gap: var(--bec-space-tight);
  padding: var(--bec-space-tight);
  border: var(--bec-border-width) solid var(--bec-color-divider);
  border-radius: var(--bec-radius-card);
}

.runtime-passport__detail-row > div,
.runtime-passport__detail-row dl,
.runtime-passport__nameplate > div {
  min-width: 0;
}

.runtime-passport__detail-row > div {
  display: grid;
  gap: var(--bec-ref-space-4);
}

.runtime-passport__detail-row strong,
.runtime-passport__detail-row > div > span {
  overflow-wrap: anywhere;
}

.runtime-passport__detail-row strong,
.runtime-passport__nameplate dd {
  font-size: var(--bec-ref-font-14);
  font-weight: var(--bec-font-weight-heading);
}

.runtime-passport__detail-row > div > span {
  color: var(--bec-color-text-secondary);
  font-family: var(--bec-font-family-number);
  font-size: var(--bec-ref-font-12);
}

.runtime-passport__detail-row dl {
  display: grid;
  gap: var(--bec-ref-space-4);
  margin: 0;
}

.runtime-passport__detail-row dl > div,
.runtime-passport__nameplate > div {
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
}

.runtime-passport__detail-row dt,
.runtime-passport__detail-row dd,
.runtime-passport__nameplate dt,
.runtime-passport__nameplate dd {
  margin: 0;
  overflow-wrap: anywhere;
}

.runtime-passport__detail-row dd {
  font-size: var(--bec-ref-font-12);
  text-align: right;
}

.runtime-passport__nameplate {
  grid-template-columns: repeat(auto-fit, minmax(min(100%, calc(var(--bec-ref-space-64) * 2)), 1fr));
}

.runtime-passport__nameplate > div {
  align-items: baseline;
}

.runtime-passport__nameplate dd {
  text-align: right;
}

.runtime-passport__gaps {
  display: grid;
  gap: var(--bec-space-tight);
}

.runtime-passport__gaps article {
  align-items: flex-start;
  padding: var(--bec-space-tight);
  background: var(--bec-color-surface-secondary);
  border-radius: var(--bec-radius-card);
}

.runtime-passport__gaps article > div {
  min-width: 0;
}

.runtime-passport__gaps p {
  margin: var(--bec-ref-space-4) 0 0;
}

.runtime-passport__footer {
  display: grid;
  gap: var(--bec-space-tight);
  border-top: var(--bec-border-width) solid var(--bec-color-divider);
}

.runtime-passport__footer-hint {
  align-items: flex-start;
}

.runtime-passport__actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--bec-space-tight);
}

.runtime-passport__actions :deep(.el-button) {
  flex: 1;
  min-width: 0;
  margin: 0;
  font-size: var(--bec-ref-font-12);
}
</style>
