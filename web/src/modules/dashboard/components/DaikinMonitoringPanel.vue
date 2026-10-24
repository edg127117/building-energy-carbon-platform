<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import {
  ElAlert, ElButton, ElCheckbox, ElEmpty, ElInput, ElOption, ElPagination,
  ElRadioButton, ElRadioGroup, ElSelect, ElSkeleton, ElTable, ElTableColumn, ElTag,
} from '@/shared/ui'
import { t } from '@/locales'
import { formatDateTime } from '@/shared/utils/format'
import { daikinApi } from '../api/daikin'
import { hvacControlApi } from '../api/hvac-control'
import {
  daikinDeviceRunState, daikinRunStateLabel, daikinCurrentValue, daikinFieldTone, daikinLabel, daikinOutdoorStatusLabel, daikinOutdoorStatusTone,
  daikinQuickStatusCounts, filterDaikinDevicesByQuickStatus,
  groupDaikinDevicesBySpace, groupDaikinDevicesBySystem, isDaikinDeviceRunning, normalizeDaikinSpaceName,
  type DaikinQuickStatus,
} from '../models/daikin-display'
import type { DaikinDevice, DeviceCurrent, StateSummary } from '../models/daikin'
import type { HvacControlBatch, HvacControlOperation } from '../models/hvac-control'
import { useDaikinResource } from '../composables/use-daikin-resource'
import { useHvacControlAccess } from '../composables/use-hvac-control-access'
import DaikinDeviceDetail from './DaikinDeviceDetail.vue'
import HvacBatchControlPanel from './HvacBatchControlPanel.vue'
import HvacDeviceGrantPanel from './HvacDeviceGrantPanel.vue'

interface CardTelemetry {
  fanSpeed: string | null
  isFilterDirty: boolean
  compressorOnOff: StateSummary | null
  inCommunicationError: boolean
  hasHardFault: boolean
  roomTemp: number | null
  setTemp: number | null
  unit: string
}

const props = withDefaults(defineProps<{ alarms?: boolean; history?: boolean }>(), { alarms: false, history: false })
const route = useRoute()
const text = (key: string) => t(`dashboard.daikin.${key}`)
const summaryText = (key: string) => t(`dashboard.spaceSummary.${key}`)
const controlAccess = useHvacControlAccess()
const buildings = useDaikinResource<Awaited<ReturnType<typeof hvacControlApi.buildings>>>()
const devices = useDaikinResource<Awaited<ReturnType<typeof daikinApi.devices>>>()
const exceptions = useDaikinResource<Awaited<ReturnType<typeof daikinApi.exceptions>>>()
const spaces = useDaikinResource<Awaited<ReturnType<typeof daikinApi.spaces>>>()

const building = ref('')
const kind = ref('')
const space = ref('')
const state = ref('')
const exceptionFilter = ref('')
const quickStatus = ref<DaikinQuickStatus>('ALL')
const groupMode = ref<'SYSTEM' | 'SPACE'>('SYSTEM')
const viewMode = ref<'CARD' | 'TABLE'>('CARD')
const showSpaceSummary = ref(true)
const spaceSummaryFilter = ref<'ALL' | 'RUNNING' | 'STOPPED' | 'UNKNOWN'>('ALL')
const spaceSummaryPage = ref(1)
const SPACE_SUMMARY_PAGE_SIZE = 6
const collapsedGroups = ref<Record<string, boolean>>({})
const cardTelemetry = ref<Record<string, CardTelemetry>>({})
const searchInput = ref('')
const keyword = ref('')
const page = ref(1)
const selected = ref<string | null>(null)
const refreshTick = ref(0)
const monitorRefreshTick = ref(0)
const batchSelectedEquipmentIds = ref<string[]>([])
const batchDeviceById = ref<Record<string, DaikinDevice>>({})
const batchSelectionLoading = ref(false)
const batchSelectionError = ref<string | null>(null)
let batchSelectionRequest = 0

const spaceFilterOptions = computed(() => {
  const map = new Map<string, { value: string; label: string; spaceIds: string[] }>()
  for (const item of spaces.data.value ?? []) {
    const label = normalizeDaikinSpaceName(item.spaceName)
    let entry = map.get(label)
    if (!entry) {
      entry = { value: item.spaceId, label, spaceIds: [] }
      map.set(label, entry)
    }
    entry.spaceIds.push(item.spaceId)
  }
  return Array.from(map.values()).sort((a, b) => a.label.localeCompare(b.label, 'zh-CN', { numeric: true }))
})
const selectedSpaceOption = computed(() => spaceFilterOptions.value.find(item => item.value === space.value))
const rawDevices = computed(() => {
  const items = devices.data.value?.items ?? []
  const opt = selectedSpaceOption.value
  if (opt && opt.spaceIds.length > 1) {
    const allowed = new Set(opt.spaceIds)
    return items.filter(item => item.spaceId != null && allowed.has(item.spaceId))
  }
  return items
})
const statusCounts = computed(() => daikinQuickStatusCounts(rawDevices.value, compressorState))
const filteredDevices = computed(() => filterDaikinDevicesByQuickStatus(rawDevices.value, quickStatus.value, compressorState))
const systemGroups = computed(() => groupDaikinDevicesBySystem(filteredDevices.value, rawDevices.value, compressorState))
const spaceGroups = computed(() => groupDaikinDevicesBySpace(filteredDevices.value, spaces.data.value ?? [], compressorState))
const selectedDevice = computed(() => rawDevices.value.find(item => item.equipmentId === selected.value))
const buildingName = computed(() => buildings.data.value?.find(item => item.buildingId === building.value)?.buildingName ?? building.value)
const batchSelectedDevices = computed(() => batchSelectedEquipmentIds.value
  .map(equipmentId => batchDeviceById.value[equipmentId])
  .filter((item): item is DaikinDevice => item != null))

const spaceSummaryItems = computed(() => {
  const groups = groupDaikinDevicesBySpace(rawDevices.value, spaces.data.value ?? [], compressorState)
  return groups.map(group => {
    const opt = spaceFilterOptions.value.find(item => item.label === group.spaceName)
    const roomTemps: number[] = []
    const setTemps: number[] = []
    for (const dev of group.devices) {
      if (dev.deviceKind === 'OUTDOOR') continue
      const tele = cardTelemetry.value[dev.equipmentId]
      if (tele?.roomTemp != null) roomTemps.push(tele.roomTemp)
      if (tele?.setTemp != null) setTemps.push(tele.setTemp)
    }
    const avgRoomTemp = roomTemps.length
      ? Math.round((roomTemps.reduce((sum, val) => sum + val, 0) / roomTemps.length) * 10) / 10
      : null
    const avgSetTemp = setTemps.length
      ? Math.round((setTemps.reduce((sum, val) => sum + val, 0) / setTemps.length) * 10) / 10
      : null
    return {
      groupKey: group.groupKey,
      spaceName: group.spaceName,
      spaceOptionValue: opt?.value ?? '',
      runningCount: group.runningCount,
      stoppedCount: group.stoppedCount,
      unknownCount: group.unknownCount,
      avgRoomTemp,
      avgSetTemp,
    }
  }).sort((a, b) => {
    if (b.runningCount !== a.runningCount) return b.runningCount - a.runningCount
    const aHasTemp = a.avgRoomTemp != null ? 1 : 0
    const bHasTemp = b.avgRoomTemp != null ? 1 : 0
    if (bHasTemp !== aHasTemp) return bHasTemp - aHasTemp
    return a.spaceName.localeCompare(b.spaceName, 'zh-CN', { numeric: true })
  })
})

const spaceSummaryCounts = computed(() => ({
  ALL: spaceSummaryItems.value.length,
  RUNNING: spaceSummaryItems.value.filter(item => item.runningCount > 0).length,
  STOPPED: spaceSummaryItems.value.filter(item => item.runningCount === 0 && item.unknownCount === 0).length,
  UNKNOWN: spaceSummaryItems.value.filter(item => item.unknownCount > 0).length,
}))

const filteredSpaceSummaryItems = computed(() => {
  if (spaceSummaryFilter.value === 'RUNNING') {
    return spaceSummaryItems.value.filter(item => item.runningCount > 0)
  }
  if (spaceSummaryFilter.value === 'UNKNOWN') return spaceSummaryItems.value.filter(item => item.unknownCount > 0)
  if (spaceSummaryFilter.value === 'STOPPED') {
    return spaceSummaryItems.value.filter(item => item.runningCount === 0 && item.unknownCount === 0)
  }
  return spaceSummaryItems.value
})

const pagedSpaceSummaryItems = computed(() => {
  const start = (spaceSummaryPage.value - 1) * SPACE_SUMMARY_PAGE_SIZE
  return filteredSpaceSummaryItems.value.slice(start, start + SPACE_SUMMARY_PAGE_SIZE)
})

function selectSpaceSummaryFilter(filter: 'ALL' | 'RUNNING' | 'STOPPED' | 'UNKNOWN') {
  spaceSummaryFilter.value = filter
  spaceSummaryPage.value = 1
}

function toggleSpaceChip(spaceOptionValue: string) {
  if (!spaceOptionValue) return
  space.value = space.value === spaceOptionValue ? '' : spaceOptionValue
}

function spaceNameOf(spaceId: string | null | undefined): string {
  if (!spaceId) return text('unassignedSpace')
  const rawName = spaces.data.value?.find(item => item.spaceId === spaceId)?.spaceName
  return rawName ? normalizeDaikinSpaceName(rawName) : text('unassignedSpace')
}

function formatModeAndFan(item: Partial<Pick<DaikinDevice, 'equipmentId' | 'mode'>>): string {
  const modeText = daikinLabel(item.mode?.value)
  const fanRaw = item.equipmentId ? cardTelemetry.value[item.equipmentId]?.fanSpeed : null
  if (!fanRaw) return modeText
  const fanText = daikinCurrentValue('fanSpeed', fanRaw)
  if (fanText === '—') return modeText
  return `${modeText} · ${fanText}`
}

function formatRoomTemp(item: Partial<Pick<DaikinDevice, 'equipmentId'>>): string {
  const info = item.equipmentId ? cardTelemetry.value[item.equipmentId] : undefined
  if (!info || info.roomTemp == null) return '—'
  return `${info.roomTemp}${info.unit || '°C'}`
}

function formatSetTemp(item: Partial<Pick<DaikinDevice, 'equipmentId'>>): string {
  const info = item.equipmentId ? cardTelemetry.value[item.equipmentId] : undefined
  if (!info || info.setTemp == null) return '—'
  return `${info.setTemp}${info.unit || '°C'}`
}

function showHardExceptionTag(item: DaikinDevice): boolean {
  if (!item.hasActiveException) return false
  const info = cardTelemetry.value[item.equipmentId]
  if (info?.isFilterDirty && !info.hasHardFault) return false
  return true
}

function toggleGroup(groupKey: string) {
  collapsedGroups.value[groupKey] = !collapsedGroups.value[groupKey]
}

function rememberBatchDevices(items: DaikinDevice[]) {
  if (!items.length) return
  batchDeviceById.value = {
    ...batchDeviceById.value,
    ...Object.fromEntries(items.map(item => [item.equipmentId, item])),
  }
}

function clearBatchSelection() {
  batchSelectionRequest++
  batchSelectionLoading.value = false
  batchSelectedEquipmentIds.value = []
  batchDeviceById.value = {}
  batchSelectionError.value = null
}

function isBatchSelected(equipmentId: string): boolean {
  return batchSelectedEquipmentIds.value.includes(equipmentId)
}

function batchDeviceFromRow(row: unknown): DaikinDevice { return row as DaikinDevice }

function setBatchSelected(item: DaikinDevice, selectedForBatch: boolean) {
  batchSelectionRequest++
  batchSelectionLoading.value = false
  batchSelectionError.value = null
  rememberBatchDevices([item])
  batchSelectedEquipmentIds.value = selectedForBatch
    ? Array.from(new Set([...batchSelectedEquipmentIds.value, item.equipmentId])).sort()
    : batchSelectedEquipmentIds.value.filter(equipmentId => equipmentId !== item.equipmentId)
}

function batchSelectionScopeKey(): string {
  return [building.value, kind.value, space.value, keyword.value, state.value, exceptionFilter.value, quickStatus.value].join('\u0001')
}

function batchSelectionLabel(item: DaikinDevice): string {
  return text('batchControl.selectDevice').replace('{name}', item.equipmentName || item.equipmentCode || item.equipmentId)
}

async function selectAllFilteredDevices() {
  if (!building.value || batchSelectionLoading.value) return
  const request = ++batchSelectionRequest
  const scopeKey = batchSelectionScopeKey()
  const selectedSpace = selectedSpaceOption.value
  const selectedSpaceIds = new Set(selectedSpace?.spaceIds ?? [])
  const backendSpaceId = selectedSpace && selectedSpace.spaceIds.length > 1 ? '' : space.value
  const buildingId = building.value
  const selectedKind = kind.value
  const selectedKeyword = keyword.value
  const selectedState = state.value
  const selectedException = exceptionFilter.value === '' ? undefined : exceptionFilter.value === 'yes'
  const selectedQuickStatus = quickStatus.value
  const pageSize = 100
  batchSelectionLoading.value = true
  batchSelectionError.value = null
  const isCurrentRequest = () => !disposed && request === batchSelectionRequest && scopeKey === batchSelectionScopeKey()
  const rejectSelection = (messageKey: 'filteredTooMany' | 'filteredListChanged') => {
    batchSelectedEquipmentIds.value = []
    batchDeviceById.value = {}
    batchSelectionError.value = text(`batchControl.${messageKey}`)
  }
  try {
    // 完整读取服务端分页后才应用快捷和合并空间筛选，避免把当前页伪装成“全选”。
    const seenEquipmentIds = new Set<string>()
    const matchingItems: DaikinDevice[] = []
    let total: number | null = null
    for (let currentPage = 1; total == null || seenEquipmentIds.size < total; currentPage++) {
      const result = await daikinApi.devices(
        buildingId,
        currentPage,
        selectedKind,
        selectedKeyword,
        backendSpaceId,
        selectedState,
        selectedException,
        pageSize,
      )
      if (!isCurrentRequest()) return
      if (!Number.isSafeInteger(result.total) || result.total < 0 || (total != null && result.total !== total)) {
        rejectSelection('filteredListChanged')
        return
      }
      total = result.total
      const expectedItemCount = Math.min(pageSize, total - seenEquipmentIds.size)
      if (result.items.length !== expectedItemCount) {
        rejectSelection('filteredListChanged')
        return
      }
      for (const item of result.items) {
        if (!item.equipmentId || seenEquipmentIds.has(item.equipmentId)) {
          rejectSelection('filteredListChanged')
          return
        }
        seenEquipmentIds.add(item.equipmentId)
        const inSelectedSpace = selectedSpace == null || selectedSpace.spaceIds.length <= 1
          || (item.spaceId != null && selectedSpaceIds.has(item.spaceId))
        if (inSelectedSpace && filterDaikinDevicesByQuickStatus([item], selectedQuickStatus, compressorState).length) {
          matchingItems.push(item)
          if (matchingItems.length > pageSize) {
            rejectSelection('filteredTooMany')
            return
          }
        }
      }
    }
    if (!isCurrentRequest()) return
    batchDeviceById.value = Object.fromEntries(matchingItems.map(item => [item.equipmentId, item]))
    batchSelectedEquipmentIds.value = matchingItems.map(item => item.equipmentId).sort()
  } catch {
    if (isCurrentRequest()) {
      batchSelectedEquipmentIds.value = []
      batchDeviceById.value = {}
      batchSelectionError.value = text('batchControl.selectFilteredFailed')
    }
  } finally {
    if (isCurrentRequest()) batchSelectionLoading.value = false
  }
}

function compressorState(device: DaikinDevice) {
  return cardTelemetry.value[device.equipmentId]?.compressorOnOff
}

function deviceRunState(row: DaikinDevice | Record<string, unknown>) {
  const device = row as DaikinDevice
  return daikinDeviceRunState(device, compressorState(device))
}

function deviceHealthLabel(row: DaikinDevice): string {
  if (!row.active) return text('inactive')
  if (row.lastValidAt == null) return text('noObservation')
  if (row.stale) return text('stale')
  return text('onlineNormal')
}

function pillToneClass(status: DaikinQuickStatus): string {
  if (status === 'RUNNING') return 'tone-success'
  if (status === 'STOPPED') return 'tone-muted'
  if (status === 'EXCEPTION') return 'tone-warning'
  if (status === 'STALE') return 'tone-danger'
  return 'tone-default'
}

function search() {
  const nextKeyword = searchInput.value.trim()
  if (keyword.value !== nextKeyword) clearBatchSelection()
  keyword.value = nextKeyword
  selected.value = null
  if (page.value === 1) load()
  else page.value = 1
}

function selectQuickStatus(status: DaikinQuickStatus) {
  if (quickStatus.value !== status) clearBatchSelection()
  quickStatus.value = status
}

let timer: ReturnType<typeof setInterval> | undefined
let disposed = false
let telemetrySeq = 0
let listRequestSeq = 0
let listRequests = 0
let statusRefreshQueued = false
let nextTelemetryAt = 0
const telemetryObservedAt = new Map<string, number>()
const telemetryPending = new Map<string, number>()

function cardStatus(snapshot: DeviceCurrent) {
  const findField = (name: string) => {
    const field = snapshot.fields.find(row => row.fieldName === name)
    return field?.status === 'PRESENT' && field.valueVisible ? field.normalizedValue : null
  }
  return {
    fanSpeed: findField('fanSpeed'),
    isFilterDirty: findField('isFilterDirty') === 'true',
    compressorOnOff: (() => {
      const field = snapshot.fields.find(row => row.fieldName === 'compressorOnOff')
      return field ? { value: field.valueVisible && snapshot.active ? field.normalizedValue : null,
        status: field.status, stale: field.stale, lastValidAt: field.lastValidAt } : null
    })(),
    inCommunicationError: findField('inCommunicationError') === 'true',
    hasHardFault: findField('inError') === 'true' || findField('inEmergency') === 'true'
      || findField('inCommunicationError') === 'true' || Boolean(findField('errorCode')),
  }
}

async function hydrateCardTelemetry(items: DaikinDevice[]) {
  const seq = telemetrySeq
  await Promise.all(items.map(async item => {
    if (telemetryPending.get(item.equipmentId) === seq) return
    telemetryPending.set(item.equipmentId, seq)
    try {
      const isOutdoor = item.deviceKind === 'OUTDOOR'
      const [curRes, roomRes, setRes] = await Promise.all([
        daikinApi.current(item.equipmentId).catch(() => null),
        isOutdoor ? Promise.resolve(null) : daikinApi.temperature(item.equipmentId, 'roomTemp').catch(() => null),
        isOutdoor ? Promise.resolve(null) : daikinApi.temperature(item.equipmentId, 'temperature').catch(() => null),
      ])
      if (disposed || seq !== telemetrySeq) return
      const prior = cardTelemetry.value[item.equipmentId]
      const statusFresh = curRes && (curRes.lastValidAt ?? 0) >= (telemetryObservedAt.get(item.equipmentId) ?? 0)
      cardTelemetry.value[item.equipmentId] = {
        ...(statusFresh ? cardStatus(curRes) : prior ?? {
          fanSpeed: null, isFilterDirty: false, compressorOnOff: null, inCommunicationError: false, hasHardFault: false,
        }),
        roomTemp: roomRes?.reading?.value ?? null,
        setTemp: setRes?.reading?.value ?? null,
        unit: roomRes?.unit || setRes?.unit || '°C',
      }
      if (statusFresh) telemetryObservedAt.set(item.equipmentId, curRes.lastValidAt ?? 0)
    } catch {
      // 单台卡片摘要补全失败不阻断主列表渲染。
    } finally {
      if (telemetryPending.get(item.equipmentId) === seq) telemetryPending.delete(item.equipmentId)
    }
  }))
}

watch(() => devices.data.value, val => {
  if (val?.items?.length) rememberBatchDevices(val.items)
})

async function load(cursor?: string, silent = false, withTelemetry = true) {
  if (!building.value) return
  const backendSpaceId = selectedSpaceOption.value && selectedSpaceOption.value.spaceIds.length > 1 ? '' : space.value
  if (props.alarms) return exceptions.run(() => daikinApi.exceptions(building.value, props.history, cursor), silent)
  const seq = ++listRequestSeq
  listRequests++
  try {
    await devices.run(() => daikinApi.devices(building.value, page.value, kind.value, keyword.value, backendSpaceId, state.value, exceptionFilter.value === '' ? undefined : exceptionFilter.value === 'yes'), silent)
    if (disposed || seq !== listRequestSeq || devices.error.value) return
    const items = devices.data.value?.items ?? []
    // 高频列表只读平台摘要；仅首次出现的卡片补全温度，既有温度仍按分钟刷新。
    const telemetryItems = withTelemetry ? items : items.filter(item => !cardTelemetry.value[item.equipmentId]
      && telemetryPending.get(item.equipmentId) !== telemetrySeq)
    if (telemetryItems.length) void hydrateCardTelemetry(telemetryItems)
  } finally {
    listRequests--
    if (!listRequests && statusRefreshQueued) {
      statusRefreshQueued = false
      refreshStatus()
    }
  }
}

function refresh() {
  nextTelemetryAt = Date.now() + 60_000
  load(undefined, Boolean(devices.data.value || exceptions.data.value))
  refreshTick.value++
}

function refreshStatus(queue = false) {
  if (disposed || document.hidden || props.alarms || props.history || !building.value) return
  if (listRequests) {
    statusRefreshQueued ||= queue
    return
  }
  void load(undefined, true, false)
}

function detailStateRefreshed(snapshot: DeviceCurrent) {
  if (snapshot.equipmentId !== selected.value || document.hidden) return
  if ((snapshot.lastValidAt ?? 0) >= (telemetryObservedAt.get(snapshot.equipmentId) ?? 0)) {
    cardTelemetry.value[snapshot.equipmentId] = {
      ...(cardTelemetry.value[snapshot.equipmentId] ?? { roomTemp: null, setTemp: null, unit: '°C' }),
      ...cardStatus(snapshot),
    }
    telemetryObservedAt.set(snapshot.equipmentId, snapshot.lastValidAt ?? 0)
  }
  // 详情与列表共用后端事实；慢列表结束后合并补读一次，不用请求目标推算实际状态。
  refreshStatus(true)
}

function visibilityChanged() {
  if (!document.hidden) refreshStatus(true)
}

function refreshAfterControl(operation: HvacControlOperation) {
  if (operation.equipmentId !== selected.value) return
  // 这里只重读一次平台数据；后续由详情关注和服务端三分钟跟踪持续获取真实状态。
  void load(undefined, true)
}

function refreshAfterBatch(batch: HvacControlBatch) {
  if (!batch.completed) return
  // 批次完成后只重读平台监测数据；绝不将共同目标值填回设备的实际读值。
  void load(undefined, true)
  refreshTick.value++
}

watch(building, () => {
  clearBatchSelection()
  space.value = ''
  spaces.clear()
  if (!props.alarms && building.value) void spaces.run(() => daikinApi.spaces(building.value))
  selected.value = null
  page.value = 1
  quickStatus.value = 'ALL'
  devices.clear()
  cardTelemetry.value = {}
  telemetryObservedAt.clear()
  telemetrySeq++
  exceptions.clear()
  load()
})
watch([kind, space, state, exceptionFilter], () => {
  telemetrySeq++
  clearBatchSelection()
  page.value = 1
  selected.value = null
  load()
})
watch(page, () => {
  telemetrySeq++
  selected.value = null
  load()
})

onMounted(async () => {
  if (!props.alarms) await controlAccess.refresh()
  await buildings.run(hvacControlApi.buildings)
  if (disposed) return
  const requestedBuilding = typeof route.query.buildingId === 'string' ? route.query.buildingId : ''
  building.value = buildings.data.value?.find(item => item.buildingId === requestedBuilding)?.buildingId ?? buildings.data.value?.[0]?.buildingId ?? ''
  // 深链接只选控制监测范围内建筑；设备接口仍独立校验真实归属和菜单。
  if (!props.alarms && building.value === requestedBuilding && typeof route.query.equipmentId === 'string') {
    await Promise.resolve()
    if (!disposed) selected.value = route.query.equipmentId
  }
  nextTelemetryAt = Date.now() + 60_000
  document.addEventListener('visibilitychange', visibilityChanged)
  // 十秒刷新仅重读平台状态；温度、详情普通刷新和告警页面保留分钟节奏。
  timer = setInterval(() => {
    if (document.hidden || listRequests || devices.loading.value || exceptions.loading.value || props.history) return
    if (Date.now() >= nextTelemetryAt) refresh()
    else refreshStatus()
  }, 10_000)
})
onUnmounted(() => {
  disposed = true
  clearInterval(timer)
  document.removeEventListener('visibilitychange', visibilityChanged)
})
</script>

<template>
  <section class="daikin-panel" :class="{ 'daikin-panel-batch-control': building && !alarms }">
    <header class="panel-header">
      <div class="title-block">
        <h1>{{ text(alarms ? (history ? 'historyAlarms' : 'currentAlarms') : 'title') }}</h1>
        <p>{{ text(alarms ? 'alarmNotice' : 'notice') }}</p>
      </div>
      <div class="filters">
        <label class="filter-field">
          <span>{{ text('building') }}</span>
          <ElSelect v-model="building" filterable :placeholder="text('building')" :aria-label="text('building')">
            <ElOption v-for="item in buildings.data.value ?? []" :key="item.buildingId" :value="item.buildingId" :label="item.buildingName" />
          </ElSelect>
        </label>
        <label v-if="!alarms" class="filter-field">
          <span>{{ text('space') }}</span>
          <ElSelect v-model="space" :placeholder="text('all')" filterable :aria-label="text('space')">
            <ElOption value="" :label="text('all')" />
            <ElOption v-for="item in spaceFilterOptions" :key="item.value" :value="item.value" :label="item.label" />
          </ElSelect>
        </label>
        <label v-if="!alarms" class="filter-field">
          <span>{{ text('kind') }}</span>
          <ElSelect v-model="kind" :placeholder="text('all')" :aria-label="text('kind')">
            <ElOption value="" :label="text('all')" />
            <ElOption v-for="key in ['INDOOR', 'OUTDOOR']" :key="key" :value="key" :label="text(key)" />
          </ElSelect>
        </label>
        <label v-if="!alarms" class="filter-field">
          <span>{{ text('searchDevice') }}</span>
          <ElInput v-model="searchInput" clearable :placeholder="text('searchPlaceholder')" :aria-label="text('searchDevice')" @keyup.enter="search" @clear="search" />
        </label>
        <ElButton v-if="!alarms" :disabled="!building" @click="search">{{ text('query') }}</ElButton>
        <ElButton :disabled="!building" :loading="devices.loading.value || exceptions.loading.value" @click="refresh">{{ text('refresh') }}</ElButton>
      </div>

      <div v-if="building && !alarms" class="toolbar-row">
        <div class="status-pills" role="group" :aria-label="text('state')">
          <button
            v-for="pill in ([
              ['ALL', 'quickAll', statusCounts.ALL],
              ['RUNNING', 'quickRunning', statusCounts.RUNNING],
              ['STOPPED', 'quickStopped', statusCounts.STOPPED],
              ['UNKNOWN', 'quickUnknown', statusCounts.UNKNOWN],
              ['EXCEPTION', 'quickException', statusCounts.EXCEPTION],
              ['STALE', 'quickStale', statusCounts.STALE],
            ] as const)"
            :key="pill[0]"
            type="button"
            class="status-pill"
            :class="[
              quickStatus === pill[0] ? 'status-pill-active' : pillToneClass(pill[0]),
            ]"
            @click="selectQuickStatus(pill[0])"
          >
            <span v-if="pill[0] !== 'ALL'" class="status-dot" :class="`dot-${pill[0].toLowerCase()}`" />
            <span>{{ text(pill[1]) }}</span>
            <span class="pill-count">{{ '（' }}{{ pill[2] }}{{ '）' }}</span>
          </button>
        </div>

        <div class="view-switchers">
          <ElButton data-testid="batch-select-filtered" :loading="batchSelectionLoading" @click="selectAllFilteredDevices">
            {{ text(batchSelectionLoading ? 'batchControl.selectingFiltered' : 'batchControl.selectFiltered') }}
          </ElButton>
          <ElRadioGroup v-if="viewMode === 'CARD'" v-model="groupMode">
            <ElRadioButton value="SYSTEM">{{ text('groupBySystem') }}</ElRadioButton>
            <ElRadioButton value="SPACE">{{ text('groupBySpace') }}</ElRadioButton>
          </ElRadioGroup>
          <ElRadioGroup v-model="viewMode">
            <ElRadioButton value="CARD">{{ text('cardView') }}</ElRadioButton>
            <ElRadioButton value="TABLE">{{ text('tableView') }}</ElRadioButton>
          </ElRadioGroup>
        </div>
      </div>
    </header>

    <ElAlert v-if="spaces.error.value" :title="spaces.error.value" type="error" :closable="false" />
    <ElAlert v-if="buildings.error.value" :title="buildings.error.value" type="error" :closable="false" />
    <ElSkeleton v-if="buildings.loading.value" :rows="5" animated />
    <ElEmpty v-else-if="!building && !buildings.error.value" :description="t('dashboard.noBuilding')" />

    <template v-if="building && !alarms">
      <ElAlert v-if="controlAccess.testOpenControls.value" :title="text('deviceGrant.testOpenTitle')" :description="text('deviceGrant.testOpenNotice')" type="info" :closable="false" />
      <HvacDeviceGrantPanel
        v-else-if="controlAccess.loaded.value"
        :building-name="buildingName"
        :devices="filteredDevices"
        :space-options="spaceFilterOptions"
      />
      <ElAlert v-if="batchSelectionError" :title="batchSelectionError" type="error" :closable="false" />
      <HvacBatchControlPanel
        :equipment-ids="batchSelectedEquipmentIds"
        :devices="batchSelectedDevices"
        @clear-selection="clearBatchSelection"
        @completed="refreshAfterBatch"
      />

      <section v-if="spaceSummaryItems.length" class="space-summary-bar">
        <header class="space-summary-header">
          <div class="space-summary-title-row">
            <strong class="space-summary-title">{{ summaryText('title') }}</strong>
            <div v-if="showSpaceSummary" class="space-summary-pills">
              <button
                v-for="tab in ([
                  ['ALL', 'filterAllSpaces', spaceSummaryCounts.ALL],
                  ['RUNNING', 'filterRunningSpaces', spaceSummaryCounts.RUNNING],
                  ['STOPPED', 'filterStoppedSpaces', spaceSummaryCounts.STOPPED],
                  ['UNKNOWN', 'filterUnknownSpaces', spaceSummaryCounts.UNKNOWN],
                ] as const)"
                :key="tab[0]"
                type="button"
                class="space-filter-pill"
                :class="{ 'space-filter-pill-active': spaceSummaryFilter === tab[0] }"
                @click="selectSpaceSummaryFilter(tab[0])"
              >
                {{ summaryText(tab[1]) }}{{ ' (' }}{{ tab[2] }}{{ ')' }}
              </button>
            </div>
          </div>
          <div class="space-summary-actions">
            <ElPagination
              v-if="showSpaceSummary && filteredSpaceSummaryItems.length > SPACE_SUMMARY_PAGE_SIZE"
              v-model:current-page="spaceSummaryPage"
              :page-size="SPACE_SUMMARY_PAGE_SIZE"
              :total="filteredSpaceSummaryItems.length"
              layout="prev, pager, next"
            />
            <ElButton v-if="space" text type="primary" @click="space = ''">{{ summaryText('clearSpaceFilter') }}</ElButton>
            <ElButton text @click="showSpaceSummary = !showSpaceSummary">
              {{ summaryText(showSpaceSummary ? 'collapse' : 'expand') }}
            </ElButton>
          </div>
        </header>
        <template v-if="showSpaceSummary">
          <div v-if="pagedSpaceSummaryItems.length" class="space-summary-grid">
            <button
              v-for="item in pagedSpaceSummaryItems"
              :key="item.groupKey"
              type="button"
              class="space-summary-chip"
              :class="{
                'space-summary-chip-active': item.spaceOptionValue && space === item.spaceOptionValue,
              }"
              @click="toggleSpaceChip(item.spaceOptionValue)"
            >
              <div class="space-chip-top">
                <strong class="space-chip-name">{{ item.spaceName }}</strong>
                <ElTag v-if="item.runningCount > 0" type="success">{{ summaryText('runningBadge') }}</ElTag>
                <ElTag v-else-if="item.unknownCount > 0" type="info">{{ text('unknownLabel') }}</ElTag>
                <ElTag v-else type="info">{{ summaryText('stoppedBadge') }}</ElTag>
              </div>
              <div class="space-chip-metrics">
                <span class="tone-success">{{ summaryText('runningPrefix') }}{{ ' ' }}{{ item.runningCount }}{{ ' ' }}{{ text('unitCountSuffix') }}</span>
                <span>{{ ' · ' }}</span>
                <span class="tone-muted">{{ summaryText('stoppedPrefix') }}{{ ' ' }}{{ item.stoppedCount }}{{ ' ' }}{{ text('unitCountSuffix') }}</span>
                <span v-if="item.unknownCount > 0" class="tone-muted">{{ ' · ' }}{{ text('unknownLabel') }} {{ item.unknownCount }} {{ text('unitCountSuffix') }}</span>
                <template v-if="item.avgRoomTemp != null">
                  <span>{{ ' · ' }}</span>
                  <span>{{ summaryText('avgTempPrefix') }}{{ ' ' }}{{ item.avgRoomTemp }}{{ '°C' }}</span>
                </template>
                <template v-if="item.avgSetTemp != null">
                  <span>{{ ' / ' }}{{ summaryText('setTempPrefix') }}{{ ' ' }}{{ item.avgSetTemp }}{{ '°C' }}</span>
                </template>
              </div>
            </button>
          </div>
          <p v-else class="space-summary-empty">{{ summaryText('emptyFilteredSpaces') }}</p>
        </template>
      </section>

      <div class="device-list">
        <ElAlert v-if="devices.error.value" :title="devices.error.value" type="error" :closable="false" />
        <ElSkeleton v-if="devices.loading.value" :rows="5" animated />
        <ElEmpty v-else-if="!filteredDevices.length" :description="text('none')" />

        <template v-else-if="viewMode === 'CARD'">
          <div v-if="groupMode === 'SYSTEM'" class="group-stack">
            <section v-for="sysGroup in systemGroups" :key="sysGroup.groupKey" class="device-group">
              <header class="group-header">
                <div class="group-header-main">
                  <ElButton text @click="toggleGroup(sysGroup.groupKey)">
                    {{ text(collapsedGroups[sysGroup.groupKey] ? 'expandGroup' : 'collapseGroup') }}
                  </ElButton>
                  <ElTag type="primary">{{ text('outdoorSystemBadge') }}</ElTag>
                  <strong class="group-title">
                    {{ sysGroup.outdoorUnit ? (sysGroup.outdoorUnit.equipmentName || text('unnamed')) : text('unassignedSystem') }}
                  </strong>
                  <span v-if="sysGroup.outdoorUnit" class="group-subtitle">
                    {{ sysGroup.outdoorUnit.equipmentCode }}{{ ' · ' }}{{ text('unitStatusLabel') }}{{ '：' }}
                    <strong :class="`tone-${daikinOutdoorStatusTone(sysGroup.outdoorUnit, cardTelemetry[sysGroup.outdoorUnit.equipmentId]?.compressorOnOff)}`">
                      {{ daikinOutdoorStatusLabel(sysGroup.outdoorUnit, cardTelemetry[sysGroup.outdoorUnit.equipmentId]?.compressorOnOff) }}
                    </strong>
                  </span>
                </div>
                <div class="group-header-actions">
                  <span class="group-summary">
                    {{ text('linkedIndoorPrefix') }}{{ ' ' }}{{ sysGroup.indoorUnits.length }}{{ ' ' }}{{ text('unitCountSuffix') }}{{ ' · ' }}
                    <strong class="tone-success">{{ text('runningIndoorPrefix') }}{{ ' ' }}{{ sysGroup.runningCount }}{{ ' ' }}{{ text('unitCountSuffix') }}</strong>
                  </span>
                  <ElButton
                    v-if="sysGroup.outdoorUnit"
                    type="primary"
                    plain
                    @click="selected = sysGroup.outdoorUnit.equipmentId"
                  >
                    {{ text('viewOutdoorDetail') }}
                  </ElButton>
                </div>
              </header>

              <div v-if="!collapsedGroups[sysGroup.groupKey]" class="card-grid">
                <article
                  v-for="item in (sysGroup.indoorUnits.length ? sysGroup.indoorUnits : (sysGroup.outdoorUnit ? [sysGroup.outdoorUnit] : []))"
                  :key="item.equipmentId"
                  class="device-card"
                  :class="{
                    'device-card-running': isDaikinDeviceRunning(item, cardTelemetry[item.equipmentId]?.compressorOnOff),
                    'device-card-active': selected === item.equipmentId,
                    'device-card-warning': item.hasActiveException,
                  }"
                  @click="selected = item.equipmentId"
                >
                  <header class="device-card-header">
                    <div>
                      <div class="device-title-row">
                        <span class="status-dot" :class="`dot-${deviceRunState(item).toLowerCase()}`" />
                        <strong class="device-card-name">{{ item.equipmentName || text('unnamed') }}</strong>
                      </div>
                      <p class="device-card-sub">{{ item.equipmentCode }}{{ ' · ' }}{{ spaceNameOf(item.spaceId) }}</p>
                    </div>
                    <div class="device-card-tags">
                      <ElCheckbox
                        :model-value="isBatchSelected(item.equipmentId)"
                        :aria-label="batchSelectionLabel(item)"
                        :data-testid="`batch-select-${item.equipmentId}`"
                        @click.stop
                        @update:model-value="setBatchSelected(item, Boolean($event))"
                      />
                      <ElTag v-if="showHardExceptionTag(item)" type="danger">{{ text('exception') }}</ElTag>
                      <ElTag v-if="cardTelemetry[item.equipmentId]?.isFilterDirty" type="warning">{{ text('filterReminderTag') }}</ElTag>
                      <ElTag :type="item.stale || !item.active ? 'warning' : 'success'">{{ deviceHealthLabel(item) }}</ElTag>
                    </div>
                  </header>

                  <div class="device-card-metrics">
                    <template v-if="item.deviceKind === 'OUTDOOR'">
                      <div class="metric-cell">
                        <span class="metric-label">{{ text('unitStatusLabel') }}</span>
                        <strong class="metric-value" :class="`tone-${daikinOutdoorStatusTone(item, cardTelemetry[item.equipmentId]?.compressorOnOff)}`">
                          {{ daikinOutdoorStatusLabel(item, cardTelemetry[item.equipmentId]?.compressorOnOff) }}
                        </strong>
                      </div>
                      <div class="metric-cell">
                        <span class="metric-label">{{ text('compressorStateCard') }}</span>
                        <strong class="metric-value" :class="`tone-${daikinFieldTone('compressorOnOff', deviceRunState(item) === 'UNKNOWN' ? null : cardTelemetry[item.equipmentId]?.compressorOnOff?.value)}`">
                          {{ deviceRunState(item) === 'UNKNOWN' ? text('unknownLabel') : daikinCurrentValue('compressorOnOff', cardTelemetry[item.equipmentId]?.compressorOnOff?.value ?? null) }}
                        </strong>
                      </div>
                      <div class="metric-cell">
                        <span class="metric-label">{{ text('commStatusCardLabel') }}</span>
                        <strong class="metric-value" :class="cardTelemetry[item.equipmentId]?.inCommunicationError ? 'tone-danger' : 'tone-success'">
                          {{ text(cardTelemetry[item.equipmentId]?.inCommunicationError ? 'commErrorText' : 'commNormalText') }}
                        </strong>
                      </div>
                    </template>
                    <template v-else>
                      <div class="metric-cell">
                        <span class="metric-label">{{ text('runStateLabel') }}</span>
                        <strong class="metric-value" :class="isDaikinDeviceRunning(item) ? 'tone-success' : 'tone-muted'">
                          {{ daikinRunStateLabel(item) }}
                        </strong>
                      </div>
                      <div class="metric-cell">
                        <span class="metric-label">{{ text('modeFanLabel') }}</span>
                        <strong class="metric-value" :class="`tone-${daikinFieldTone('mode', item.mode?.value)}`">
                          {{ formatModeAndFan(item) }}
                        </strong>
                      </div>
                      <div class="metric-cell">
                        <span class="metric-label">{{ text('roomSetTempLabel') }}</span>
                        <span class="metric-temp-pair">
                          <strong class="metric-temp-room" :class="formatRoomTemp(item) === '—' ? 'tone-muted' : 'tone-default'">{{ formatRoomTemp(item) }}</strong>
                          <span class="metric-temp-sep">{{ ' / ' }}</span>
                          <span class="metric-temp-set">{{ formatSetTemp(item) }}</span>
                        </span>
                      </div>
                    </template>
                  </div>

                  <footer class="device-card-footer">
                    <span class="sync-time">{{ text('syncTimeLabel') }}{{ '：' }}{{ item.lastValidAt == null ? t('common.missing') : formatDateTime(item.lastValidAt) }}</span>
                    <ElButton text type="primary" @click.stop="selected = item.equipmentId">{{ text('detail') }}</ElButton>
                  </footer>
                </article>
              </div>
            </section>
          </div>

          <div v-else class="group-stack">
            <section v-for="spGroup in spaceGroups" :key="spGroup.groupKey" class="device-group">
              <header class="group-header">
                <div class="group-header-main">
                  <ElButton text @click="toggleGroup(spGroup.groupKey)">
                    {{ text(collapsedGroups[spGroup.groupKey] ? 'expandGroup' : 'collapseGroup') }}
                  </ElButton>
                  <strong class="group-title">{{ spGroup.spaceName }}</strong>
                </div>
                <div class="group-header-actions">
                  <span class="group-summary">
                    {{ spGroup.devices.length }}{{ ' ' }}{{ text('unitCountSuffix') }}{{ ' · ' }}
                    <strong class="tone-success">{{ text('runningIndoorPrefix') }}{{ ' ' }}{{ spGroup.runningCount }}{{ ' ' }}{{ text('unitCountSuffix') }}</strong>
                  </span>
                </div>
              </header>

              <div v-if="!collapsedGroups[spGroup.groupKey]" class="card-grid">
                <article
                  v-for="item in spGroup.devices"
                  :key="item.equipmentId"
                  class="device-card"
                  :class="{
                    'device-card-running': isDaikinDeviceRunning(item, cardTelemetry[item.equipmentId]?.compressorOnOff),
                    'device-card-active': selected === item.equipmentId,
                    'device-card-warning': item.hasActiveException,
                  }"
                  @click="selected = item.equipmentId"
                >
                  <header class="device-card-header">
                    <div>
                      <div class="device-title-row">
                        <span class="status-dot" :class="`dot-${deviceRunState(item).toLowerCase()}`" />
                        <strong class="device-card-name">{{ item.equipmentName || text('unnamed') }}</strong>
                      </div>
                      <p class="device-card-sub">{{ item.equipmentCode }}{{ ' · ' }}{{ text(item.deviceKind === 'OUTDOOR' ? 'outdoorBadge' : 'indoorBadge') }}</p>
                    </div>
                    <div class="device-card-tags">
                      <ElCheckbox
                        :model-value="isBatchSelected(item.equipmentId)"
                        :aria-label="batchSelectionLabel(item)"
                        :data-testid="`batch-select-${item.equipmentId}`"
                        @click.stop
                        @update:model-value="setBatchSelected(item, Boolean($event))"
                      />
                      <ElTag v-if="showHardExceptionTag(item)" type="danger">{{ text('exception') }}</ElTag>
                      <ElTag v-if="cardTelemetry[item.equipmentId]?.isFilterDirty" type="warning">{{ text('filterReminderTag') }}</ElTag>
                      <ElTag :type="item.stale || !item.active ? 'warning' : 'success'">{{ deviceHealthLabel(item) }}</ElTag>
                    </div>
                  </header>

                  <div class="device-card-metrics">
                    <template v-if="item.deviceKind === 'OUTDOOR'">
                      <div class="metric-cell">
                        <span class="metric-label">{{ text('unitStatusLabel') }}</span>
                        <strong class="metric-value" :class="`tone-${daikinOutdoorStatusTone(item, cardTelemetry[item.equipmentId]?.compressorOnOff)}`">
                          {{ daikinOutdoorStatusLabel(item, cardTelemetry[item.equipmentId]?.compressorOnOff) }}
                        </strong>
                      </div>
                      <div class="metric-cell">
                        <span class="metric-label">{{ text('compressorStateCard') }}</span>
                        <strong class="metric-value" :class="`tone-${daikinFieldTone('compressorOnOff', deviceRunState(item) === 'UNKNOWN' ? null : cardTelemetry[item.equipmentId]?.compressorOnOff?.value)}`">
                          {{ deviceRunState(item) === 'UNKNOWN' ? text('unknownLabel') : daikinCurrentValue('compressorOnOff', cardTelemetry[item.equipmentId]?.compressorOnOff?.value ?? null) }}
                        </strong>
                      </div>
                      <div class="metric-cell">
                        <span class="metric-label">{{ text('commStatusCardLabel') }}</span>
                        <strong class="metric-value" :class="cardTelemetry[item.equipmentId]?.inCommunicationError ? 'tone-danger' : 'tone-success'">
                          {{ text(cardTelemetry[item.equipmentId]?.inCommunicationError ? 'commErrorText' : 'commNormalText') }}
                        </strong>
                      </div>
                    </template>
                    <template v-else>
                      <div class="metric-cell">
                        <span class="metric-label">{{ text('runStateLabel') }}</span>
                        <strong class="metric-value" :class="isDaikinDeviceRunning(item) ? 'tone-success' : 'tone-muted'">
                          {{ daikinRunStateLabel(item) }}
                        </strong>
                      </div>
                      <div class="metric-cell">
                        <span class="metric-label">{{ text('modeFanLabel') }}</span>
                        <strong class="metric-value" :class="`tone-${daikinFieldTone('mode', item.mode?.value)}`">
                          {{ formatModeAndFan(item) }}
                        </strong>
                      </div>
                      <div class="metric-cell">
                        <span class="metric-label">{{ text('roomSetTempLabel') }}</span>
                        <span class="metric-temp-pair">
                          <strong class="metric-temp-room" :class="formatRoomTemp(item) === '—' ? 'tone-muted' : 'tone-default'">{{ formatRoomTemp(item) }}</strong>
                          <span class="metric-temp-sep">{{ ' / ' }}</span>
                          <span class="metric-temp-set">{{ formatSetTemp(item) }}</span>
                        </span>
                      </div>
                    </template>
                  </div>

                  <footer class="device-card-footer">
                    <span class="sync-time">{{ text('syncTimeLabel') }}{{ '：' }}{{ item.lastValidAt == null ? t('common.missing') : formatDateTime(item.lastValidAt) }}</span>
                    <ElButton text type="primary" @click.stop="selected = item.equipmentId">{{ text('detail') }}</ElButton>
                  </footer>
                </article>
              </div>
            </section>
          </div>
        </template>

        <ElTable
          v-else
          row-key="equipmentId"
          highlight-current-row
          :current-row-key="selected ?? undefined"
          :data="filteredDevices"
          :empty-text="text('none')"
        >
          <ElTableColumn :label="text('batchControl.select')" width="78">
            <template #default="{ row }">
              <ElCheckbox
                :model-value="isBatchSelected(batchDeviceFromRow(row).equipmentId)"
                :aria-label="batchSelectionLabel(batchDeviceFromRow(row))"
                :data-testid="`batch-select-${batchDeviceFromRow(row).equipmentId}`"
                @click.stop
                @update:model-value="setBatchSelected(batchDeviceFromRow(row), Boolean($event))"
              />
            </template>
          </ElTableColumn>
          <ElTableColumn :label="text('equipment')" min-width="180">
            <template #default="{ row }">
              <div class="device-title-row">
                <span class="status-dot" :class="`dot-${deviceRunState(row).toLowerCase()}`" />
                <strong>{{ row.equipmentName || text('unnamed') }}</strong>
              </div>
              <span class="table-sub">{{ row.equipmentCode || row.equipmentId }}</span>
            </template>
          </ElTableColumn>
          <ElTableColumn :label="text('kind')">
            <template #default="{ row }">{{ ['INDOOR', 'OUTDOOR'].includes(row.deviceKind) ? text(row.deviceKind) : row.deviceKind }}</template>
          </ElTableColumn>
          <ElTableColumn :label="text('space')" min-width="140">
            <template #default="{ row }">{{ spaceNameOf(row.spaceId) }}</template>
          </ElTableColumn>
          <ElTableColumn :label="text('runStateLabel')">
            <template #default="{ row }">
              <strong :class="isDaikinDeviceRunning(row, cardTelemetry[row.equipmentId]?.compressorOnOff) ? 'tone-success' : 'tone-muted'">
                {{ row.deviceKind === 'OUTDOOR' ? daikinOutdoorStatusLabel(row, cardTelemetry[row.equipmentId]?.compressorOnOff) : daikinRunStateLabel(row) }}
              </strong>
            </template>
          </ElTableColumn>
          <ElTableColumn :label="text('modeFanLabel')" min-width="130">
            <template #default="{ row }">
              <strong :class="row.deviceKind === 'OUTDOOR' ? 'tone-muted' : `tone-${daikinFieldTone('mode', row.mode?.value)}`">
                {{ row.deviceKind === 'OUTDOOR' ? text('outdoorNotApplicable') : formatModeAndFan(row) }}
              </strong>
            </template>
          </ElTableColumn>
          <ElTableColumn :label="text('roomSetTempLabel')" min-width="130">
            <template #default="{ row }">
              <span v-if="row.deviceKind === 'OUTDOOR'" class="tone-muted">{{ text('outdoorNotApplicable') }}</span>
              <span v-else class="metric-temp-pair">
                <strong class="metric-temp-room" :class="formatRoomTemp(row) === '—' ? 'tone-muted' : 'tone-default'">{{ formatRoomTemp(row) }}</strong>
                <span class="metric-temp-sep">{{ ' / ' }}</span>
                <span class="metric-temp-set">{{ formatSetTemp(row) }}</span>
              </span>
            </template>
          </ElTableColumn>
          <ElTableColumn :label="text('state')" min-width="140">
            <template #default="{ row }">
              <ElTag :type="row.stale ? 'warning' : 'success'">{{ text(!row.active ? 'inactive' : row.lastValidAt == null ? 'noObservation' : row.stale ? 'stale' : 'normal') }}</ElTag>
              <ElTag v-if="row.hasActiveException" type="danger">{{ text('exception') }}</ElTag>
              <ElTag v-if="cardTelemetry[row.equipmentId]?.isFilterDirty" type="warning">{{ text('filterReminderTag') }}</ElTag>
            </template>
          </ElTableColumn>
          <ElTableColumn :label="text('fresh')" min-width="175">
            <template #default="{ row }">{{ row.lastValidAt == null ? t('common.missing') : formatDateTime(row.lastValidAt) }}</template>
          </ElTableColumn>
          <ElTableColumn :label="text('detail')">
            <template #default="{ row }"><ElButton text type="primary" @click="selected = row.equipmentId">{{ text('detail') }}</ElButton></template>
          </ElTableColumn>
        </ElTable>

        <ElPagination
          v-if="devices.data.value"
          v-model:current-page="page"
          :page-size="20"
          :total="devices.data.value.total"
          layout="prev, pager, next, total"
        />
      </div>

      <DaikinDeviceDetail
        v-if="selected"
        :key="selected"
        :equipment-id="selected"
        :equipment-name="selectedDevice?.equipmentName"
        :equipment-code="selectedDevice?.equipmentCode"
        :device-kind="selectedDevice?.deviceKind"
        :space-name="spaceNameOf(selectedDevice?.spaceId)"
        :refresh-tick="refreshTick"
        :monitor-refresh-tick="monitorRefreshTick"
        :visible="Boolean(selected)"
        @close="selected = null"
        @control-completed="refreshAfterControl"
        @state-refreshed="detailStateRefreshed"
      />
    </template>

    <template v-if="building && alarms">
      <ElAlert v-if="exceptions.error.value" :title="exceptions.error.value" type="error" :closable="false" />
      <ElSkeleton v-if="exceptions.loading.value" :rows="5" animated />
      <ElTable v-else :data="exceptions.data.value?.items ?? []" :empty-text="text('empty')">
        <ElTableColumn :label="text('type')"><template #default="{ row }">{{ daikinLabel(row.type) }}</template></ElTableColumn>
        <ElTableColumn prop="sourceId" :label="text('source')" />
        <ElTableColumn :label="text('equipment')">
          <template #default="{ row }">{{ row.equipmentId ? (row.equipmentName || text('unnamed')) : text('sourceException') }}<br><span v-if="row.equipmentCode">{{ row.equipmentCode }}</span></template>
        </ElTableColumn>
        <ElTableColumn :label="text('detected')" min-width="175"><template #default="{ row }">{{ formatDateTime(row.firstDetectedAt) }}</template></ElTableColumn>
        <ElTableColumn v-if="history" :label="text('recovered')" min-width="175"><template #default="{ row }">{{ row.recoveredAt == null ? t('common.missing') : formatDateTime(row.recoveredAt) }}</template></ElTableColumn>
      </ElTable>
      <div class="exception-pagination">
        <ElButton @click="load()">{{ text('first') }}</ElButton>
        <ElButton :disabled="!exceptions.data.value?.nextCursor" @click="load(exceptions.data.value?.nextCursor ?? undefined)">{{ text('next') }}</ElButton>
      </div>
    </template>
  </section>
</template>

<style scoped>
  .daikin-panel { display: grid; gap: var(--bec-space-section); min-width: 0; }
  .daikin-panel-batch-control { padding-bottom: calc(var(--bec-control-height) * 3); }
.panel-header { display: grid; gap: var(--bec-space-group); }
.title-block h1 { margin: 0; font-size: var(--bec-font-size-system); }
.title-block p { margin: var(--bec-space-tight) 0 0; color: var(--bec-color-text-secondary); line-height: var(--bec-line-height); }
.filters { display: flex; gap: var(--bec-space-group); flex-wrap: wrap; align-items: center; }
.filter-field { display: flex; align-items: center; gap: var(--bec-space-tight); }
.filter-field > span { white-space: nowrap; }
.filter-field .el-input { width: calc(var(--bec-control-height) * 7); }
.el-select { width: calc(var(--bec-control-height) * 6); }
.toolbar-row { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: var(--bec-space-group); padding: var(--bec-space-group); background: var(--bec-color-surface); border: var(--bec-border-width) solid var(--bec-color-border); border-radius: var(--bec-radius-card); }
.status-pills { display: flex; flex-wrap: wrap; align-items: center; gap: var(--bec-space-tight); }
.status-pill { display: inline-flex; align-items: center; gap: var(--bec-space-tight); padding: var(--bec-space-tight) var(--bec-space-group); background: var(--bec-color-surface-secondary); color: var(--bec-color-text-primary); border: var(--bec-border-width) solid var(--bec-color-border); border-radius: var(--bec-radius-tag); cursor: pointer; font-size: var(--bec-font-size-body); font-weight: var(--bec-font-weight-heading); }
.status-pill-active { background: var(--bec-color-action-primary); color: var(--bec-color-on-action); border-color: var(--bec-color-action-primary); }
.status-pill-active .status-dot { background: var(--bec-color-on-action); }
.pill-count { opacity: 0.85; }
.status-dot { display: inline-block; width: var(--bec-space-tight); height: var(--bec-space-tight); border-radius: var(--bec-radius-tag); flex-shrink: 0; }
.dot-running { background: var(--bec-color-success); }
.dot-unknown { background: var(--bec-color-warning); }
.dot-stopped { background: var(--bec-color-text-disabled); }
.dot-exception { background: var(--bec-color-warning); }
.dot-stale { background: var(--bec-color-error); }
.tone-success { color: var(--bec-color-success); }
.tone-primary { color: var(--bec-color-brand-primary); }
.tone-warning { color: var(--bec-color-warning); }
.tone-danger { color: var(--bec-color-error); }
.tone-muted { color: var(--bec-color-text-secondary); }
.tone-default { color: var(--bec-color-text-primary); }
.view-switchers { display: flex; flex-wrap: wrap; align-items: center; gap: var(--bec-space-group); }
.device-list { min-width: 0; display: grid; gap: var(--bec-space-group); }
.group-stack { display: grid; gap: var(--bec-space-group); }
.device-group { background: var(--bec-color-surface); border: var(--bec-border-width) solid var(--bec-color-border); border-radius: var(--bec-radius-card); overflow: hidden; }
.group-header { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: var(--bec-space-group); padding: var(--bec-space-group); background: var(--bec-color-surface-secondary); border-bottom: var(--bec-border-width) solid var(--bec-color-border); }
.group-header-main { display: flex; flex-wrap: wrap; align-items: center; gap: var(--bec-space-tight); }
.group-title { font-size: var(--bec-font-size-title); font-weight: var(--bec-font-weight-heading); }
.group-subtitle { color: var(--bec-color-text-secondary); font-size: var(--bec-font-size-small); }
.group-header-actions { display: flex; flex-wrap: wrap; align-items: center; gap: var(--bec-space-group); }
.group-summary { color: var(--bec-color-text-secondary); font-size: var(--bec-font-size-small); }
.card-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(calc(var(--bec-control-height) * 9), 1fr)); gap: var(--bec-space-group); padding: var(--bec-panel-padding); }
.device-card { display: grid; gap: var(--bec-space-group); padding: var(--bec-panel-padding); background: var(--bec-color-surface); border: var(--bec-border-width) solid var(--bec-color-border); border-radius: var(--bec-radius-card); box-shadow: var(--bec-shadow-card); cursor: pointer; }
.device-card-running { border-color: color-mix(in srgb, var(--bec-color-success) 45%, var(--bec-color-border)); background: color-mix(in srgb, var(--bec-color-success) 3%, var(--bec-color-surface)); }
.device-card-active { border-color: var(--bec-color-brand-primary); box-shadow: var(--bec-shadow-focus); }
.device-card-warning { border-color: var(--bec-color-warning); }
.device-card-header { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--bec-space-tight); }
.device-title-row { display: flex; align-items: center; gap: var(--bec-space-tight); }
.device-card-name { font-size: var(--bec-font-size-title); font-weight: var(--bec-font-weight-heading); color: var(--bec-color-text-primary); }
.device-card-sub { margin: var(--bec-space-tight) 0 0; color: var(--bec-color-text-secondary); font-size: var(--bec-font-size-small); }
.table-sub { color: var(--bec-color-text-secondary); font-size: var(--bec-font-size-small); }
.device-card-tags { display: flex; flex-wrap: wrap; gap: var(--bec-space-tight); }
.device-card-metrics { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: var(--bec-space-tight); padding: var(--bec-space-tight) var(--bec-space-group); background: var(--bec-color-surface-secondary); border: var(--bec-border-width) solid var(--bec-color-border); border-radius: var(--bec-radius-card); }
.metric-cell { display: grid; gap: var(--bec-space-tight); }
.metric-label { color: var(--bec-color-text-secondary); font-size: var(--bec-font-size-small); }
.metric-value { font-weight: var(--bec-font-weight-heading); }
.metric-temp-pair { display: inline-flex; align-items: baseline; font-family: var(--bec-font-mono); }
.metric-temp-room { font-weight: var(--bec-font-weight-heading); }
.metric-temp-sep, .metric-temp-set { color: var(--bec-color-text-secondary); font-weight: var(--bec-font-weight-heading); }
.device-card-footer { display: flex; align-items: center; justify-content: space-between; gap: var(--bec-space-tight); }
.sync-time { color: var(--bec-color-text-secondary); font-size: var(--bec-font-size-small); }
.exception-pagination { display: flex; gap: var(--bec-space-tight); justify-content: flex-start; }
.el-pagination { max-width: 100%; overflow-x: auto; }
.space-summary-bar { display: grid; gap: var(--bec-space-group); padding: var(--bec-space-group) var(--bec-panel-padding); background: var(--bec-color-surface); border: var(--bec-border-width) solid var(--bec-color-border); border-radius: var(--bec-radius-card); }
.space-summary-header { display: flex; align-items: center; justify-content: space-between; gap: var(--bec-space-group); flex-wrap: wrap; }
.space-summary-title-row { display: flex; align-items: center; gap: var(--bec-space-group); flex-wrap: wrap; }
.space-summary-title { font-size: var(--bec-font-size-body); font-weight: var(--bec-font-weight-heading); color: var(--bec-color-text-primary); }
.space-summary-pills { display: flex; align-items: center; gap: var(--bec-space-tight); flex-wrap: wrap; }
.space-filter-pill { padding: var(--bec-ref-space-4) var(--bec-ref-space-12); background: var(--bec-color-surface-secondary); border: var(--bec-border-width) solid var(--bec-color-border); border-radius: var(--bec-radius-tag); cursor: pointer; font-family: inherit; font-size: var(--bec-font-size-small); font-weight: var(--bec-font-weight-heading); color: var(--bec-color-text-secondary); }
.space-filter-pill-active { background: var(--bec-color-action-primary); color: var(--bec-color-on-action); border-color: var(--bec-color-action-primary); }
.space-summary-actions { display: flex; align-items: center; gap: var(--bec-space-tight); flex-wrap: wrap; }
.space-summary-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(calc(var(--bec-control-height) * 5.2), 1fr)); gap: var(--bec-space-tight); }
.space-summary-chip { display: grid; gap: var(--bec-space-tight); text-align: left; padding: var(--bec-space-tight) var(--bec-space-group); background: var(--bec-color-surface-secondary); border: var(--bec-border-width) solid var(--bec-color-border); border-radius: var(--bec-radius-card); cursor: pointer; font-family: inherit; }
.space-summary-chip-active { border-color: var(--bec-color-brand-primary); box-shadow: var(--bec-shadow-focus); background: color-mix(in srgb, var(--bec-color-brand-primary) 5%, var(--bec-color-surface)); }
.space-summary-chip-warning { border-color: color-mix(in srgb, var(--bec-color-warning) 50%, var(--bec-color-border)); }
.space-chip-top { display: flex; align-items: center; justify-content: space-between; gap: var(--bec-space-tight); }
.space-chip-name { font-size: var(--bec-font-size-body); font-weight: var(--bec-font-weight-heading); color: var(--bec-color-text-primary); }
.space-chip-metrics { display: flex; flex-wrap: wrap; align-items: center; gap: var(--bec-ref-space-1); font-size: var(--bec-font-size-small); color: var(--bec-color-text-secondary); }
.space-summary-empty { margin: 0; font-size: var(--bec-font-size-small); color: var(--bec-color-text-secondary); }
  @media (max-width: 640px) { .daikin-panel-batch-control { padding-bottom: calc(var(--bec-control-height) * 4); } }
</style>
