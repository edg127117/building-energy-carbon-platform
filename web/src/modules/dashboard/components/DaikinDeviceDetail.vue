<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import {
  ElAlert, ElButton, ElDatePicker, ElDrawer, ElEmpty, ElOption,
  ElRadioButton, ElRadioGroup, ElSelect, ElSkeleton, ElTable,
  ElTableColumn, ElTabs, ElTabPane, ElTag,
} from '@/shared/ui'
import ChartView from '@/shared/charts/ChartView.vue'
import type { ChartOption } from '@/shared/charts/echarts'
import { formatDateTime } from '@/shared/utils/format'
import { t } from '@/locales'
import { daikinApi } from '../api/daikin'
import {
  daikinLabel, daikinFieldLabel, daikinFieldExplanation, daikinFieldTone,
  daikinCurrentFieldValue, daikinCurrentValue, daikinStructuredDetail,
  daikinTemperatureSummary, filterDaikinStateEvents,
  temperatureSeries, runtimePeriodTime, temperatureWindow,
} from '../models/daikin-display'
import { useDaikinResource } from '../composables/use-daikin-resource'

const props = withDefaults(defineProps<{
  equipmentId: string
  equipmentName?: string | null
  equipmentCode?: string | null
  deviceKind?: string | null
  spaceName?: string | null
  refreshTick: number
  visible?: boolean
}>(), {
  equipmentName: null,
  equipmentCode: null,
  deviceKind: null,
  spaceName: null,
  visible: true,
})
defineEmits<{ close: [] }>()

const text = (key: string) => t(`dashboard.daikin.${key}`)
const date = (value: number | null) => value == null ? t('common.missing') : formatDateTime(value)
const current = useDaikinResource<Awaited<ReturnType<typeof daikinApi.current>>>()
const events = useDaikinResource<Awaited<ReturnType<typeof daikinApi.events>>>()
const roomTempCurrent = useDaikinResource<Awaited<ReturnType<typeof daikinApi.temperature>>>()
const setpointCurrent = useDaikinResource<Awaited<ReturnType<typeof daikinApi.temperature>>>()
const roomHistory = useDaikinResource<Awaited<ReturnType<typeof daikinApi.history>>>()
const setHistory = useDaikinResource<Awaited<ReturnType<typeof daikinApi.history>>>()
const runtime = useDaikinResource<Awaited<ReturnType<typeof daikinApi.observedRuntime>>>()

const quickRange = ref<'6H' | '24H' | '7D'>('24H')
const seriesMode = ref<'BOTH' | 'ROOM' | 'SET'>('BOTH')
const eventQuickRange = ref<'ALL' | '24H' | '7D' | '30D'>('ALL')
const eventFieldFilter = ref('')
const eventTimeRange = ref<[number, number] | null>(null)
const eventCursorStack = ref<Array<string | undefined>>([])
const currentEventCursor = ref<string | undefined>(undefined)
const granularity = ref('DAY')
const tab = ref('state')
const range = ref<[number, number]>([Date.now() - 86400000, Date.now()])
const queriedRange = ref<[number, number] | null>(null)
const rangeError = ref(false)
const showCapabilities = ref(false)
const showExtended = ref(false)

const isOutdoor = computed(() => props.deviceKind === 'OUTDOOR')
const structured = computed(() => daikinStructuredDetail(current.data.value?.fields ?? [], props.deviceKind))
const tempUnit = computed(() => roomHistory.data.value?.unit || setHistory.data.value?.unit || roomTempCurrent.data.value?.unit || setpointCurrent.data.value?.unit || '°C')

const heroRoomTemp = computed(() => {
  const live = roomTempCurrent.data.value?.reading?.value
  if (live != null) return `${live} ${roomTempCurrent.data.value?.unit || tempUnit.value}`
  if (structured.value.roomTempText !== '—') return `${structured.value.roomTempText} ${tempUnit.value}`
  return '—'
})

const heroSetTemp = computed(() => {
  const live = setpointCurrent.data.value?.reading?.value
  if (live != null) return `${live} ${setpointCurrent.data.value?.unit || tempUnit.value}`
  if (structured.value.setTempText !== '—') return `${structured.value.setTempText} ${tempUnit.value}`
  return '—'
})

const tempSummary = computed(() => daikinTemperatureSummary(
  seriesMode.value === 'SET' ? [] : (roomHistory.data.value?.items ?? []),
  seriesMode.value === 'ROOM' ? [] : (setHistory.data.value?.items ?? []),
))
const tempEmpty = computed(() => {
  const roomLen = seriesMode.value === 'SET' ? 0 : (roomHistory.data.value?.items.length ?? 0)
  const setLen = seriesMode.value === 'ROOM' ? 0 : (setHistory.data.value?.items.length ?? 0)
  return roomLen === 0 && setLen === 0
})

const filteredEvents = computed(() => filterDaikinStateEvents(
  events.data.value?.items ?? [],
  eventFieldFilter.value,
  eventTimeRange.value,
))

const latestRuntimeItem = computed(() => runtime.data.value?.items[0] ?? null)
const hours = (millis: number) => (millis / 3_600_000).toFixed(2)
const coverage = (covered: number, elapsed: number) => elapsed > 0 ? `${(covered / elapsed * 100).toFixed(1)}%` : '—'

const option = computed<ChartOption>(() => {
  const series: NonNullable<ChartOption['series']> = []
  if (seriesMode.value !== 'SET') {
    series.push({
      name: text('roomTemp'),
      type: 'line',
      smooth: true,
      connectNulls: false,
      showSymbol: false,
      symbol: 'circle',
      symbolSize: 6,
      lineStyle: { width: 2.5 },
      areaStyle: { opacity: 0.14 },
      emphasis: { focus: 'series' },
      // 缺口插入空节点仅用于断线，不新增任何业务读数或插值。
      data: temperatureSeries(roomHistory.data.value?.items ?? []),
    })
  }
  if (seriesMode.value !== 'ROOM') {
    series.push({
      name: text('setpoint'),
      type: 'line',
      step: 'end',
      connectNulls: false,
      showSymbol: false,
      symbol: 'circle',
      symbolSize: 6,
      lineStyle: { type: 'dashed', width: 2 },
      emphasis: { focus: 'series' },
      data: temperatureSeries(setHistory.data.value?.items ?? []),
    })
  }
  return {
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'line' },
    },
    legend: { top: 0 },
    grid: { left: 48, right: 24, top: 38, bottom: 36, containLabel: true },
    xAxis: { type: 'time' },
    yAxis: {
      type: 'value',
      scale: true,
      name: tempUnit.value,
      minInterval: 1,
      min: (extent: { min: number; max: number }) => Number.isFinite(extent.min) ? Math.floor(extent.min - 1) : 16,
      max: (extent: { min: number; max: number }) => Number.isFinite(extent.max) ? Math.ceil(extent.max + 1) : 30,
    },
    series,
  }
})

function applyQuickRange(preset: '6H' | '24H' | '7D') {
  quickRange.value = preset
  const now = Date.now()
  const hoursMap = { '6H': 6, '24H': 24, '7D': 168 }
  range.value = [now - hoursMap[preset] * 3_600_000, now]
  loadTemperature()
}

function applyEventQuickRange(preset: 'ALL' | '24H' | '7D' | '30D') {
  eventQuickRange.value = preset
  if (preset === 'ALL') {
    eventTimeRange.value = null
    return
  }
  const now = Date.now()
  const hoursMap = { '24H': 24, '7D': 168, '30D': 720 }
  eventTimeRange.value = [now - hoursMap[preset] * 3_600_000, now]
}

function onEventCustomRangeChange(val: [number, number] | null) {
  if (!val || val.length !== 2) {
    eventQuickRange.value = 'ALL'
    eventTimeRange.value = null
    return
  }
  eventTimeRange.value = [Number(val[0]), Number(val[1])]
}

function loadCurrent() {
  void current.run(() => daikinApi.current(props.equipmentId))
}

function loadEvents(cursor?: string, resetStack = false) {
  if (resetStack) {
    eventCursorStack.value = []
    currentEventCursor.value = undefined
  }
  void events.run(() => daikinApi.events(props.equipmentId, cursor))
}

function nextEventPage() {
  const next = events.data.value?.nextCursor
  if (!next) return
  eventCursorStack.value.push(currentEventCursor.value)
  currentEventCursor.value = next
  loadEvents(next, false)
}

function prevEventPage() {
  if (!eventCursorStack.value.length) return
  const prev = eventCursorStack.value.pop()
  currentEventCursor.value = prev
  loadEvents(prev, false)
}

function loadCurrentTemperatures() {
  if (isOutdoor.value) return
  void roomTempCurrent.run(() => daikinApi.temperature(props.equipmentId, 'roomTemp'))
  void setpointCurrent.run(() => daikinApi.temperature(props.equipmentId, 'temperature'))
}

function loadTemperature(after?: number) {
  if (after == null) {
    const window = temperatureWindow(range.value, Date.now())
    if (!window) {
      rangeError.value = true
      roomHistory.clear()
      setHistory.clear()
      return
    }
    // 滚动保留边界随时钟推进；裁剪首端，后续翻页保持同一查询窗口。
    rangeError.value = false
    queriedRange.value = window
  }
  const selected = queriedRange.value
  if (!selected) return
  void roomHistory.run(() => daikinApi.history(props.equipmentId, 'roomTemp', selected[0], selected[1], after))
  void setHistory.run(() => daikinApi.history(props.equipmentId, 'temperature', selected[0], selected[1], after))
}

function loadRuntime(before?: number) {
  void runtime.run(() => daikinApi.observedRuntime(props.equipmentId, granularity.value, before))
}

function loadTab() {
  if (tab.value === 'events') loadEvents(undefined, true)
  if (tab.value === 'temperature') {
    loadCurrentTemperatures()
    loadTemperature()
  }
  if (tab.value === 'runtime') loadRuntime()
}

watch(tab, loadTab)
watch(granularity, () => loadRuntime())
watch(() => props.refreshTick, () => {
  loadCurrent()
  loadCurrentTemperatures()
})
onMounted(() => {
  loadCurrent()
  loadCurrentTemperatures()
})
</script>

<template>
  <ElDrawer
    :model-value="visible"
    :title="equipmentName || text('unnamed')"
    size="58%"
    destroy-on-close
    @close="$emit('close')"
  >
    <section class="detail-drawer">
      <header class="drawer-meta">
        <div class="meta-primary">
          <ElTag :type="isOutdoor ? 'warning' : 'primary'">{{ text(isOutdoor ? 'outdoorBadge' : 'indoorBadge') }}</ElTag>
          <span v-if="equipmentCode" class="meta-item">{{ text('code') }}{{ '：' }}{{ equipmentCode }}</span>
          <span v-if="spaceName" class="meta-item">{{ text('space') }}{{ '：' }}{{ spaceName }}</span>
          <span class="meta-item">{{ text('syncTimeLabel') }}{{ '：' }}{{ date(current.data.value?.lastValidAt ?? null) }}</span>
        </div>
        <ElButton @click="$emit('close')">{{ text('close') }}</ElButton>
      </header>

      <ElAlert v-if="current.error.value" :title="current.error.value" type="error" :closable="false" />
      <ElSkeleton v-if="current.loading.value" :rows="5" animated />

      <section v-else-if="current.data.value?.fields.length" class="core-telemetry" :aria-label="text('coreTelemetryTitle')">
        <div v-if="!isOutdoor" class="temp-hero">
          <div class="temp-hero-item">
            <span class="temp-hero-label">{{ text('indoorTempCard') }}</span>
            <strong class="temp-hero-value" :class="heroRoomTemp === '—' ? 'tone-muted' : 'tone-success'">{{ heroRoomTemp }}</strong>
          </div>
          <div class="temp-hero-divider" />
          <div class="temp-hero-item">
            <span class="temp-hero-label">{{ text('setTempCard') }}</span>
            <strong class="temp-hero-value" :class="heroSetTemp === '—' ? 'tone-muted' : 'tone-primary'">{{ heroSetTemp }}</strong>
          </div>
        </div>
        <div class="core-tiles">
          <div v-for="tile in structured.coreTiles" :key="tile.key" class="core-tile">
            <span class="core-tile-label">{{ tile.label }}</span>
            <strong class="core-tile-value" :class="`tone-${tile.tone}`">{{ tile.value }}</strong>
          </div>
        </div>
      </section>

      <ElTabs v-model="tab">
        <ElTabPane :label="text('state')" name="state">
          <div v-if="current.data.value?.fields.length" class="detail-sections">
            <section v-if="structured.healthRows.length" class="detail-card">
              <header class="card-header card-header-interactive">
                <h3>{{ text('healthSectionTitle') }}</h3>
                <ElTag :type="structured.healthBadgeType">{{ structured.healthBadgeLabel }}</ElTag>
              </header>
              <div class="kv-grid">
                <div v-for="row in structured.healthRows" :key="row.key" class="kv-row">
                  <span class="kv-label" :title="row.explanation">{{ row.label }}</span>
                  <span class="kv-value" :class="`tone-${row.tone}`">
                    {{ row.value }}
                    <ElTag v-if="row.stale" type="warning">{{ text('stale') }}</ElTag>
                  </span>
                </div>
              </div>
            </section>

            <section v-if="structured.archiveRows.length" class="detail-card">
              <header class="card-header">
                <h3>{{ text('archiveSectionTitle') }}</h3>
              </header>
              <div class="kv-grid">
                <div v-for="row in structured.archiveRows" :key="row.key" class="kv-row">
                  <span class="kv-label">{{ row.label }}</span>
                  <span class="kv-value" :class="`tone-${row.tone}`">{{ row.value }}</span>
                </div>
              </div>
            </section>

            <section v-if="structured.capabilityRows.length" class="detail-card">
              <header class="card-header card-header-interactive">
                <h3>{{ text('capabilitySectionTitle') }}</h3>
                <ElButton text type="primary" @click="showCapabilities = !showCapabilities">
                  {{ text(showCapabilities ? 'collapseCapability' : 'expandCapability') }}
                </ElButton>
              </header>
              <div v-if="showCapabilities" class="kv-grid">
                <div v-for="row in structured.capabilityRows" :key="row.key" class="kv-row">
                  <span class="kv-label">{{ row.label }}</span>
                  <span class="kv-value" :class="`tone-${row.tone}`">{{ row.value }}</span>
                </div>
              </div>
            </section>

            <section v-if="structured.extendedRows.length" class="detail-card">
              <header class="card-header card-header-interactive">
                <h3>{{ text('extendedFields') }}</h3>
                <ElButton text type="primary" @click="showExtended = !showExtended">
                  {{ text(showExtended ? 'hideExtendedFields' : 'showExtendedFields') }}{{ ' ' }}{{ structured.extendedRows.length }}
                </ElButton>
              </header>
              <template v-if="showExtended">
                <p class="section-notice">{{ text('extendedNotice') }}</p>
                <div class="kv-grid" :aria-label="text('extendedFields')">
                  <div v-for="row in structured.extendedRows" :key="row.fieldName" class="kv-row">
                    <span class="kv-label" :title="daikinFieldExplanation(row.fieldName)">{{ daikinFieldLabel(row.fieldName) }}</span>
                    <span class="kv-value">
                      {{ daikinCurrentFieldValue({ fieldName: row.fieldName, status: row.status, valueVisible: row.valueVisible, normalizedValue: row.normalizedValue }) }}
                      <ElTag type="info">{{ daikinLabel(row.status) }}</ElTag>
                    </span>
                  </div>
                </div>
              </template>
            </section>
          </div>
          <ElEmpty v-else-if="!current.loading.value && !current.error.value" :description="text('empty')" />
        </ElTabPane>

        <ElTabPane :label="text('temperature')" name="temperature">
          <p class="section-notice">{{ text('temperatureNotice') }}</p>
          <div class="temp-toolbar">
            <ElRadioGroup :model-value="quickRange" @change="val => applyQuickRange(val as '6H' | '24H' | '7D')">
              <ElRadioButton value="6H">{{ text('range6h') }}</ElRadioButton>
              <ElRadioButton value="24H">{{ text('range24h') }}</ElRadioButton>
              <ElRadioButton value="7D">{{ text('range7d') }}</ElRadioButton>
            </ElRadioGroup>
            <ElRadioGroup v-model="seriesMode">
              <ElRadioButton value="BOTH">{{ text('seriesBoth') }}</ElRadioButton>
              <ElRadioButton value="ROOM">{{ text('seriesRoomOnly') }}</ElRadioButton>
              <ElRadioButton value="SET">{{ text('seriesSetOnly') }}</ElRadioButton>
            </ElRadioGroup>
          </div>
          <div class="controls">
            <ElDatePicker
              v-model="range"
              class="temperature-range"
              type="datetimerange"
              format="YYYY-MM-DD HH:mm"
              value-format="x"
              :aria-label="text('fromTo')"
              @change="range = range?.map(Number) as [number, number]"
            />
            <ElButton :loading="roomHistory.loading.value || setHistory.loading.value" @click="loadTemperature()">{{ text('query') }}</ElButton>
          </div>

          <ElAlert v-if="rangeError" :title="text('invalidRange')" type="warning" :closable="false" />
          <ElAlert v-if="roomTempCurrent.error.value" :title="roomTempCurrent.error.value" type="warning" :closable="false" />
          <ElAlert v-if="roomHistory.error.value" :title="roomHistory.error.value" type="error" :closable="false" />

          <div class="temp-kpi-grid">
            <div class="temp-kpi-card">
              <span class="temp-kpi-label">{{ text('currentRoomTempLabel') }}</span>
              <strong class="temp-kpi-value tone-success">
                {{ roomTempCurrent.data.value?.reading?.value ?? t('common.missing') }}{{ ' ' }}{{ roomTempCurrent.data.value?.unit ?? '' }}
              </strong>
              <div class="temp-kpi-tags">
                <ElTag v-if="roomTempCurrent.data.value" type="success">{{ daikinLabel(roomTempCurrent.data.value.fieldStatus) }}</ElTag>
                <ElTag v-if="roomTempCurrent.data.value?.reading?.quality && roomTempCurrent.data.value.reading.quality.decision !== 'ALLOW'" type="warning">{{ text('qualityBlocked') }}</ElTag>
                <ElTag v-if="roomTempCurrent.data.value?.reading?.stale" type="warning">{{ text('stale') }}</ElTag>
              </div>
            </div>

            <div class="temp-kpi-card">
              <span class="temp-kpi-label">{{ text('currentSetTempLabel') }}</span>
              <strong class="temp-kpi-value tone-primary">
                {{ setpointCurrent.data.value?.reading?.value ?? t('common.missing') }}{{ ' ' }}{{ setpointCurrent.data.value?.unit ?? '' }}
              </strong>
              <div class="temp-kpi-tags">
                <ElTag v-if="setpointCurrent.data.value" type="primary">{{ daikinLabel(setpointCurrent.data.value.fieldStatus) }}</ElTag>
              </div>
            </div>

            <div class="temp-kpi-card">
              <span class="temp-kpi-label">{{ text('latestObservedLabel') }}</span>
              <strong class="temp-kpi-meta">{{ date(roomTempCurrent.data.value?.reading?.observedAt ?? setpointCurrent.data.value?.reading?.observedAt ?? null) }}</strong>
            </div>

            <div class="temp-kpi-card">
              <span class="temp-kpi-label">{{ text('sampleIntegrityLabel') }}</span>
              <strong class="temp-kpi-meta">{{ tempSummary.sampleCount }}{{ ' ' }}{{ text('samplePointsSuffix') }}</strong>
              <span class="temp-kpi-sub" :class="tempSummary.gapCount > 0 ? 'tone-warning' : 'tone-success'">
                {{ tempSummary.gapCount > 0 ? `${text('gapCountPrefix')} ${tempSummary.gapCount} ${text('gapCountSuffix')}` : text('noGapNotice') }}
              </span>
            </div>
          </div>

          <p class="chart-unit">{{ text('temperatureUnit') }}{{ '（' }}{{ tempUnit }}{{ '）' }}</p>
          <div class="chart" :class="{ 'chart-set-only': seriesMode === 'SET' }">
            <ChartView
              :option="option"
              :loading="roomHistory.loading.value || setHistory.loading.value"
              :empty="tempEmpty"
              :accessible-label="text('temperature')"
            />
          </div>
          <div class="tab-actions">
            <ElButton :disabled="roomHistory.loading.value || setHistory.loading.value" @click="loadTemperature()">
              {{ text('first') }}
            </ElButton>
            <ElButton
              :disabled="roomHistory.data.value?.nextCursor == null && setHistory.data.value?.nextCursor == null"
              @click="loadTemperature(roomHistory.data.value?.nextCursor ?? setHistory.data.value?.nextCursor ?? undefined)"
            >
              {{ text('next') }}
            </ElButton>
          </div>
        </ElTabPane>

        <ElTabPane :label="text('events')" name="events">
          <p class="section-notice">{{ text('eventNotice') }}</p>
          <div class="events-toolbar">
            <ElRadioGroup :model-value="eventQuickRange" @change="val => applyEventQuickRange(val as 'ALL' | '24H' | '7D' | '30D')">
              <ElRadioButton value="ALL">{{ text('eventRangeAll') }}</ElRadioButton>
              <ElRadioButton value="24H">{{ text('eventRange24h') }}</ElRadioButton>
              <ElRadioButton value="7D">{{ text('eventRange7d') }}</ElRadioButton>
              <ElRadioButton value="30D">{{ text('eventRange30d') }}</ElRadioButton>
            </ElRadioGroup>
            <ElSelect v-model="eventFieldFilter" :aria-label="text('field')">
              <ElOption value="" :label="text('eventFieldAll')" />
              <ElOption v-for="fieldKey in ['onOff', 'unitStatus', 'mode', 'fanSpeed', 'airflowDirection', 'compressorOnOff']" :key="fieldKey" :value="fieldKey" :label="daikinFieldLabel(fieldKey)" />
            </ElSelect>
          </div>
          <div class="controls">
            <ElDatePicker
              :model-value="eventTimeRange"
              class="temperature-range"
              type="datetimerange"
              format="YYYY-MM-DD HH:mm"
              value-format="x"
              clearable
              :aria-label="text('fromTo')"
              @update:model-value="val => onEventCustomRangeChange(val as [number, number] | null)"
            />
            <ElButton :loading="events.loading.value" @click="loadEvents(undefined, true)">{{ text('eventLatestPage') }}</ElButton>
          </div>

          <ElAlert v-if="events.error.value" :title="events.error.value" type="error" :closable="false" />
          <ElSkeleton v-if="events.loading.value" :rows="4" animated />
          <ElEmpty v-else-if="!filteredEvents.length" :description="text('empty')" />
          <div v-else class="event-timeline">
            <article v-for="row in filteredEvents" :key="row.eventId" class="event-card">
              <div class="event-card-main">
                <div class="event-card-header">
                  <strong class="event-field-name">{{ daikinFieldLabel(row.fieldName) }}</strong>
                  <ElTag v-if="row.afterGap" type="warning">{{ text('gap') }}</ElTag>
                </div>
                <div class="event-transition">
                  <span>{{ text('eventFromPrefix') }}</span>
                  <strong :class="`tone-${daikinFieldTone(row.fieldName, row.beforeNormalizedValue)}`">
                    {{ daikinCurrentValue(row.fieldName, row.beforeNormalizedValue) }}
                  </strong>
                  <span>{{ text('eventToConnector') }}</span>
                  <strong :class="`tone-${daikinFieldTone(row.fieldName, row.afterNormalizedValue)}`">
                    {{ text('eventValueLeftQuote') }}{{ daikinCurrentValue(row.fieldName, row.afterNormalizedValue) }}{{ text('eventValueRightQuote') }}
                  </strong>
                </div>
              </div>
              <div class="event-card-time">
                <strong class="event-observed-at">{{ text('eventObservedTimePrefix') }}{{ date(row.observedAt) }}</strong>
                <span class="event-prev-at">{{ text('eventPreviousTimePrefix') }}{{ date(row.previousObservedAt) }}</span>
              </div>
            </article>
          </div>

          <div class="tab-actions">
            <ElButton :loading="events.loading.value" @click="loadEvents(undefined, true)">{{ text('eventLatestPage') }}</ElButton>
            <ElButton :disabled="!eventCursorStack.length || events.loading.value" @click="prevEventPage">{{ text('eventPrevPage') }}</ElButton>
            <ElButton :disabled="!events.data.value?.nextCursor || events.loading.value" @click="nextEventPage">{{ text('eventNextPage') }}</ElButton>
          </div>
        </ElTabPane>

        <ElTabPane :label="text('runtime')" name="runtime">
          <p class="section-notice">{{ text('runtimeNotice') }}</p>
          <div class="controls">
            <ElSelect v-model="granularity" :aria-label="text('runtime')">
              <ElOption v-for="key in ['DAY', 'MONTH', 'YEAR']" :key="key" :value="key" :label="text(key)" />
            </ElSelect>
          </div>
          <div v-if="latestRuntimeItem" class="temp-kpi-grid">
            <div class="temp-kpi-card">
              <span class="temp-kpi-label">{{ text('latestPeriodOnHours') }}</span>
              <strong class="temp-kpi-value tone-success">
                {{ hours(latestRuntimeItem.onMillis) }}{{ ' ' }}{{ text('hourUnit') }}
              </strong>
            </div>
            <div class="temp-kpi-card">
              <span class="temp-kpi-label">{{ text('latestPeriodCoverage') }}</span>
              <strong class="temp-kpi-value tone-primary">
                {{ coverage(latestRuntimeItem.coveredMillis, latestRuntimeItem.elapsedMillis) }}
              </strong>
              <span class="temp-kpi-sub">{{ hours(latestRuntimeItem.coveredMillis) }}{{ ' ' }}{{ text('hourUnit') }}</span>
            </div>
          </div>
          <ElAlert v-if="runtime.error.value" :title="runtime.error.value" type="error" :closable="false" />
          <ElTable :data="runtime.data.value?.items ?? []">
            <ElTableColumn :label="text('period')" min-width="220">
              <template #default="{ row }">{{ runtimePeriodTime(row.periodStart, runtime.data.value?.statisticsZone ?? 'Asia/Shanghai') }}{{ ' — ' }}{{ runtimePeriodTime(row.periodEnd, runtime.data.value?.statisticsZone ?? 'Asia/Shanghai') }}</template>
            </ElTableColumn>
            <ElTableColumn :label="text('observedOnHours')" min-width="130">
              <template #default="{ row }">
                <strong :class="row.onMillis > 0 ? 'tone-success' : 'tone-muted'">{{ hours(row.onMillis) }}{{ ' ' }}{{ text('hourUnit') }}</strong>
              </template>
            </ElTableColumn>
            <ElTableColumn :label="text('observedCoverage')" min-width="160">
              <template #default="{ row }">
                <span class="tone-primary">{{ hours(row.coveredMillis) }}{{ ' ' }}{{ text('hourUnit') }}{{ '（' }}{{ coverage(row.coveredMillis, row.elapsedMillis) }}{{ '）' }}</span>
              </template>
            </ElTableColumn>
          </ElTable>
          <div class="tab-actions">
            <ElButton :loading="runtime.loading.value" @click="loadRuntime()">{{ text('first') }}</ElButton>
            <ElButton :disabled="runtime.data.value?.nextCursor == null" @click="loadRuntime(runtime.data.value?.nextCursor ?? undefined)">{{ text('next') }}</ElButton>
          </div>
        </ElTabPane>
      </ElTabs>
    </section>
  </ElDrawer>
</template>

<style scoped>
.detail-drawer { display: grid; gap: var(--bec-space-group); min-width: 0; }
.drawer-meta { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: var(--bec-space-tight); padding-bottom: var(--bec-space-tight); border-bottom: var(--bec-border-width) solid var(--bec-color-border); }
.meta-primary { display: flex; flex-wrap: wrap; align-items: center; gap: var(--bec-space-group); }
.meta-item { color: var(--bec-color-text-secondary); font-size: var(--bec-font-size-small); }
.core-telemetry { display: grid; grid-template-columns: repeat(auto-fit, minmax(calc(var(--bec-control-height) * 7), 1fr)); gap: var(--bec-space-group); padding: var(--bec-panel-padding); background: var(--bec-color-surface-secondary); border: var(--bec-border-width) solid var(--bec-color-border); border-radius: var(--bec-radius-card); }
.temp-hero { display: flex; align-items: center; justify-content: space-around; gap: var(--bec-space-group); padding: var(--bec-space-tight); background: var(--bec-color-surface); border: var(--bec-border-width) solid var(--bec-color-border); border-radius: var(--bec-radius-card); }
.temp-hero-item { display: grid; gap: var(--bec-space-tight); text-align: center; }
.temp-hero-label { color: var(--bec-color-text-secondary); font-size: var(--bec-font-size-small); }
.temp-hero-value { font-size: var(--bec-font-size-system); font-weight: var(--bec-font-weight-heading); }
.temp-hero-divider { width: var(--bec-border-width); align-self: stretch; background: var(--bec-color-border); }
.core-tiles { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: var(--bec-space-tight); }
.core-tile { display: flex; align-items: center; justify-content: space-between; gap: var(--bec-space-tight); padding: var(--bec-space-tight) var(--bec-space-group); background: var(--bec-color-surface); border: var(--bec-border-width) solid var(--bec-color-border); border-radius: var(--bec-radius-card); }
.core-tile-label { color: var(--bec-color-text-secondary); font-size: var(--bec-font-size-small); }
.core-tile-value { font-weight: var(--bec-font-weight-heading); }
.tone-success { color: var(--bec-color-success); }
.tone-primary { color: var(--bec-color-brand-primary); }
.tone-warning { color: var(--bec-color-warning); }
.tone-danger { color: var(--bec-color-error); }
.tone-muted { color: var(--bec-color-text-secondary); }
.tone-default { color: var(--bec-color-text-primary); }
.detail-sections { display: grid; gap: var(--bec-space-group); }
.detail-card { padding: var(--bec-panel-padding); background: var(--bec-color-surface); border: var(--bec-border-width) solid var(--bec-color-border); border-radius: var(--bec-radius-card); display: grid; gap: var(--bec-space-group); }
.card-header h3 { margin: 0; font-size: var(--bec-font-size-title); font-weight: var(--bec-font-weight-heading); }
.card-header-interactive { display: flex; align-items: center; justify-content: space-between; gap: var(--bec-space-tight); }
.kv-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(calc(var(--bec-control-height) * 7), 1fr)); gap: var(--bec-space-tight) var(--bec-space-section); }
.kv-row { display: flex; align-items: center; justify-content: space-between; gap: var(--bec-space-group); padding-block: var(--bec-space-tight); border-bottom: var(--bec-border-width) solid var(--bec-color-divider); }
.kv-label { color: var(--bec-color-text-secondary); }
.kv-value { display: inline-flex; align-items: center; gap: var(--bec-space-tight); font-weight: var(--bec-font-weight-heading); text-align: right; }
.section-notice { margin: 0; color: var(--bec-color-text-secondary); line-height: var(--bec-line-height); }
.temp-toolbar, .events-toolbar { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: var(--bec-space-tight); margin-top: var(--bec-space-tight); }
.controls { display: flex; flex-wrap: wrap; align-items: center; gap: var(--bec-space-tight); margin-block: var(--bec-space-group); }
.controls :deep(.temperature-range) { flex: 0 1 calc(var(--bec-control-height) * 12); width: calc(var(--bec-control-height) * 12); max-width: 100%; box-sizing: border-box; }
.temp-kpi-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(calc(var(--bec-control-height) * 4), 1fr)); gap: var(--bec-space-tight); margin-bottom: var(--bec-space-group); }
.temp-kpi-card { display: grid; gap: var(--bec-space-tight); padding: var(--bec-space-group); background: var(--bec-color-surface-secondary); border: var(--bec-border-width) solid var(--bec-color-border); border-radius: var(--bec-radius-card); }
.temp-kpi-label { color: var(--bec-color-text-secondary); font-size: var(--bec-font-size-small); }
.temp-kpi-value { font-size: var(--bec-font-size-title); font-weight: var(--bec-font-weight-heading); }
.temp-kpi-meta { font-weight: var(--bec-font-weight-heading); color: var(--bec-color-text-primary); }
.temp-kpi-sub { font-size: var(--bec-font-size-small); font-weight: var(--bec-font-weight-heading); }
.temp-kpi-tags { display: flex; flex-wrap: wrap; gap: var(--bec-space-tight); }
.chart-unit { margin: 0 0 var(--bec-space-tight); color: var(--bec-color-text-secondary); }
.chart { --bec-chart-series-1: var(--bec-color-success); --bec-chart-series-2: var(--bec-color-brand-primary); height: var(--bec-chart-height); min-height: var(--bec-chart-height); }
.chart-set-only { --bec-chart-series-1: var(--bec-color-brand-primary); }
.event-timeline { display: grid; gap: var(--bec-space-tight); }
.event-card { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: var(--bec-space-group); padding: var(--bec-space-group); background: var(--bec-color-surface); border: var(--bec-border-width) solid var(--bec-color-border); border-radius: var(--bec-radius-card); box-shadow: var(--bec-shadow-card); }
.event-card-main { display: grid; gap: var(--bec-space-tight); }
.event-card-header { display: flex; align-items: center; gap: var(--bec-space-tight); }
.event-field-name { font-size: var(--bec-font-size-title); font-weight: var(--bec-font-weight-heading); color: var(--bec-color-text-primary); }
.event-transition { display: flex; flex-wrap: wrap; align-items: center; gap: var(--bec-space-tight); color: var(--bec-color-text-secondary); }
.event-card-time { display: grid; gap: var(--bec-space-tight); text-align: right; }
.event-observed-at { color: var(--bec-color-text-primary); font-size: var(--bec-font-size-small); font-weight: var(--bec-font-weight-heading); }
.event-prev-at { color: var(--bec-color-text-secondary); font-size: var(--bec-font-size-small); }
.tab-actions { display: flex; gap: var(--bec-space-tight); margin-top: var(--bec-space-group); }
.el-select { width: calc(var(--bec-control-height) * 5); }
.el-alert { margin-block: var(--bec-space-tight); }
</style>
