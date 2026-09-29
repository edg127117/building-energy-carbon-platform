<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  ElAlert,
  ElButton,
  ElDrawer,
  ElEmpty,
  ElOption,
  ElSelect,
  ElSkeleton,
  ElTable,
  ElTableColumn,
  ElTag,
} from '@/shared/ui'
import { t } from '@/locales'
import { formatDateTime, formatNumber } from '@/shared/utils/format'
import {
  MeterRealtimeBoard,
  getMeterPhaseType,
  isMeterEquipment,
  useAssetManagement,
  type AssetEquipment,
} from '@/modules/asset-management/public'
import { listAccessibleBuildings } from '../api/hvac'
import { daikinApi } from '../api/daikin'
import {
  daikinCurrentValue,
  daikinFieldTone,
  daikinLabel,
  groupDaikinDevicesBySpace,
  isDaikinDeviceRunning,
  normalizeDaikinSpaceName,
} from '../models/daikin-display'
import type { DaikinDevice } from '../models/daikin'
import { useDaikinResource } from '../composables/use-daikin-resource'
import DaikinDeviceDetail from '../components/DaikinDeviceDetail.vue'

type SubsystemKey = 'HVAC' | 'POWER' | 'COLD_SOURCE' | 'LIGHTING' | 'RENEWABLE'
type HvacQuickFilter = 'ALL' | 'RUNNING' | 'DEVIATED' | 'EXCEPTION' | 'STOPPED'
type PowerQuickFilter = 'ALL' | '3P' | '1P'

interface OverviewTelemetry {
  fanSpeed: string | null
  roomTemp: number | null
  setTemp: number | null
  unit: string
  dayOnHours: number | null
}

const router = useRouter()
const roText = (key: string) => t(`dashboard.runningOverview.${key}`)
const daikinText = (key: string) => t(`dashboard.daikin.${key}`)

const buildings = useDaikinResource<Awaited<ReturnType<typeof listAccessibleBuildings>>>()
const daikinDevices = useDaikinResource<Awaited<ReturnType<typeof daikinApi.devices>>>()
const daikinSpaces = useDaikinResource<Awaited<ReturnType<typeof daikinApi.spaces>>>()
const assetManagement = useAssetManagement()

const buildingId = ref('')
const lastUpdatedAt = ref<string | null>(null)
const activeSubsystem = ref<SubsystemKey>('HVAC')
const selectedSpaceName = ref('')
const hvacQuickFilter = ref<HvacQuickFilter>('ALL')
const powerQuickFilter = ref<PowerQuickFilter>('ALL')
const telemetryMap = ref<Record<string, OverviewTelemetry>>({})

const selectedDaikinId = ref<string | null>(null)
const refreshTick = ref(0)
const activeMeterDrawer = ref<AssetEquipment | null>(null)
const meterDrawerOpen = ref(false)

let telemetrySeq = 0

function isColdSourceAsset(item: AssetEquipment): boolean {
  const code = String(item.typeCode ?? '').toUpperCase()
  return ['WCR', 'WCT', 'WCP', 'AHU', 'CHILLER', 'PUMP', 'TOWER'].some(token => code.includes(token))
}

const rawHvacDevices = computed(() => daikinDevices.data.value?.items ?? [])
const allBuildingAssets = computed(() => assetManagement.equipment.value.items)
const meterAssets = computed(() => allBuildingAssets.value.filter(item => isMeterEquipment(item)))
const coldSourceAssets = computed(() => allBuildingAssets.value.filter(item => !isMeterEquipment(item) && isColdSourceAsset(item)))
const otherBusinessAssets = computed(() =>
  allBuildingAssets.value.filter(item => !isMeterEquipment(item) && !isColdSourceAsset(item)),
)

const structureSegments = computed(() => {
  const hvacCount = rawHvacDevices.value.length || otherBusinessAssets.value.length
  const meterCount = meterAssets.value.length
  const coldCount = coldSourceAssets.value.length
  const otherCount = rawHvacDevices.value.length ? otherBusinessAssets.value.filter(item => !['IDU', 'ODU'].includes(String(item.typeCode ?? '').toUpperCase())).length : 0
  const total = Math.max(1, hvacCount + meterCount + coldCount + otherCount)
  const circumference = 207.3
  const rawList = [
    { key: 'hvac', label: roText('structureHvac'), count: hvacCount, color: 'var(--bec-color-brand-primary)' },
    { key: 'meter', label: roText('structureMeter'), count: meterCount, color: 'var(--bec-chart-series-3)' },
    { key: 'cold', label: roText('structureCold'), count: coldCount, color: 'var(--bec-color-success)' },
    { key: 'other', label: roText('structureOther'), count: otherCount, color: 'var(--bec-color-warning)' },
  ]
  let offset = 0
  return {
    total: hvacCount + meterCount + coldCount + otherCount,
    items: rawList.map(seg => {
      const pct = Math.round((seg.count / total) * 100)
      const dash = (seg.count / total) * circumference
      const currentOffset = -offset
      offset += dash
      return {
        ...seg,
        pct,
        dasharray: `${dash} ${circumference}`,
        dashoffset: currentOffset,
      }
    }),
  }
})

function isMeterActive(item: AssetEquipment | Record<string, unknown>): boolean {
  const s = String(item.status ?? '').toUpperCase()
  return s === 'ACTIVE' || s === 'ONLINE' || s === '1'
}

function isTempDeviated(raw: DaikinDevice | Record<string, unknown>): boolean {
  const item = raw as DaikinDevice
  if (!isDaikinDeviceRunning(item) || item.deviceKind === 'OUTDOOR') return false
  const tele = telemetryMap.value[item.equipmentId]
  if (!tele || tele.roomTemp == null || tele.setTemp == null) return false
  return Math.abs(tele.roomTemp - tele.setTemp) >= 2
}

function tempDiffValue(raw: DaikinDevice | Record<string, unknown>): number | null {
  const item = raw as DaikinDevice
  const tele = telemetryMap.value[item.equipmentId]
  if (!tele || tele.roomTemp == null || tele.setTemp == null) return null
  return Math.round((tele.roomTemp - tele.setTemp) * 10) / 10
}

const postureStats = computed(() => {
  const hvac = rawHvacDevices.value
  let runningOk = 0
  let deviated = 0
  let stopped = 0
  let faultOrStale = 0
  for (const dev of hvac) {
    if (!dev.active || dev.stale || dev.hasActiveException) {
      faultOrStale++
    } else if (isDaikinDeviceRunning(dev)) {
      if (isTempDeviated(dev)) deviated++
      else runningOk++
    } else {
      stopped++
    }
  }
  const activeMeters = meterAssets.value.filter(m => isMeterActive(m)).length
  const inactiveMeters = meterAssets.value.length - activeMeters
  runningOk += activeMeters
  faultOrStale += inactiveMeters
  const total = Math.max(1, runningOk + deviated + stopped + faultOrStale)
  const healthyRate = Math.round(((runningOk + deviated + stopped) / total) * 1000) / 10
  const circumference = 207.3
  const rawList = [
    { key: 'ok', label: roText('postureRunningOk'), count: runningOk, color: 'var(--bec-color-success)', tone: 'tone-success' },
    { key: 'dev', label: roText('postureTempDeviation'), count: deviated, color: 'var(--bec-color-warning)', tone: 'tone-warning' },
    { key: 'stop', label: roText('postureStopped'), count: stopped, color: 'var(--bec-color-text-disabled)', tone: 'tone-muted' },
    { key: 'fault', label: roText('postureOfflineOrFault'), count: faultOrStale, color: 'var(--bec-color-error)', tone: 'tone-danger' },
  ]
  let offset = 0
  return {
    healthyRate,
    deviatedCount: deviated,
    items: rawList.map(seg => {
      const dash = (seg.count / total) * circumference
      const currentOffset = -offset
      offset += dash
      return {
        ...seg,
        dasharray: `${dash} ${circumference}`,
        dashoffset: currentOffset,
      }
    }),
  }
})

const subsystemTabs = computed(() => [
  { key: 'HVAC' as const, label: roText('subHvac'), count: rawHvacDevices.value.length, activeReady: true },
  { key: 'POWER' as const, label: roText('subPower'), count: meterAssets.value.length, activeReady: true },
  { key: 'COLD_SOURCE' as const, label: roText('subColdSource'), count: coldSourceAssets.value.length, activeReady: true },
  { key: 'LIGHTING' as const, label: roText('subLighting'), count: 0, activeReady: false },
  { key: 'RENEWABLE' as const, label: roText('subRenewable'), count: 0, activeReady: false },
])

function spaceNameOf(spaceId: string | null | undefined): string {
  if (!spaceId) return daikinText('unassignedSpace')
  const rawName = daikinSpaces.data.value?.find(item => item.spaceId === spaceId)?.spaceName
  return rawName ? normalizeDaikinSpaceName(rawName) : daikinText('unassignedSpace')
}

const hvacSpaceAnalysis = computed(() => {
  const groups = groupDaikinDevicesBySpace(rawHvacDevices.value, daikinSpaces.data.value ?? [])
  const rows = groups.map(group => {
    const roomTemps: number[] = []
    const setTemps: number[] = []
    let runtimeSum = 0
    let deviatedCount = 0
    for (const dev of group.devices) {
      const tele = telemetryMap.value[dev.equipmentId]
      if (tele?.dayOnHours != null) {
        runtimeSum += tele.dayOnHours
      }
      if (dev.deviceKind !== 'OUTDOOR') {
        if (tele?.roomTemp != null) roomTemps.push(tele.roomTemp)
        if (tele?.setTemp != null) setTemps.push(tele.setTemp)
        if (isTempDeviated(dev)) deviatedCount++
      }
    }
    const avgRoomTemp = roomTemps.length
      ? Math.round((roomTemps.reduce((a, b) => a + b, 0) / roomTemps.length) * 10) / 10
      : null
    const avgSetTemp = setTemps.length
      ? Math.round((setTemps.reduce((a, b) => a + b, 0) / setTemps.length) * 10) / 10
      : null
    return {
      spaceName: group.spaceName,
      totalCount: group.devices.length,
      runningCount: group.runningCount,
      runtimeHours: Math.round(runtimeSum * 10) / 10,
      avgRoomTemp,
      avgSetTemp,
      deviatedCount,
    }
  })
  const maxRuntime = Math.max(12, Math.ceil(Math.max(0, ...rows.map(r => r.runtimeHours)) / 4) * 4)
  const count = Math.max(1, rows.length)
  return rows.map((r, index) => {
    const ratio = r.runtimeHours > 0 ? r.runtimeHours / maxRuntime : (r.runningCount > 0 ? 0.15 : 0.04)
    const barHeightPct = Math.max(4, Math.min(92, Math.round(ratio * 88)))
    const tempY = r.avgRoomTemp != null
      ? Math.max(10, Math.min(88, Math.round(((30 - r.avgRoomTemp) / 10) * 80 + 10)))
      : null
    const xPct = Math.round(((index + 0.5) / count) * 1000) / 10
    return {
      ...r,
      maxRuntime,
      barHeightPct,
      tempY,
      xPct,
    }
  })
})

const hvacChartMaxHours = computed(() => hvacSpaceAnalysis.value[0]?.maxRuntime ?? 12)

const hvacTempPolylinePoints = computed(() => {
  return hvacSpaceAnalysis.value
    .filter(r => r.tempY != null)
    .map(r => `${r.xPct},${r.tempY}`)
    .join(' ')
})

const hvacSummaryMetrics = computed(() => {
  const rows = hvacSpaceAnalysis.value
  const totalRuntime = Math.round(rows.reduce((sum, r) => sum + r.runtimeHours, 0) * 10) / 10
  const deviceCount = Math.max(1, rawHvacDevices.value.length)
  const avgRuntime = Math.round((totalRuntime / deviceCount) * 10) / 10
  const validRoomRows = rows.filter(r => r.avgRoomTemp != null)
  const validSetRows = rows.filter(r => r.avgSetTemp != null)
  const avgRoom = validRoomRows.length
    ? Math.round((validRoomRows.reduce((s, r) => s + (r.avgRoomTemp ?? 0), 0) / validRoomRows.length) * 10) / 10
    : null
  const avgSet = validSetRows.length
    ? Math.round((validSetRows.reduce((s, r) => s + (r.avgSetTemp ?? 0), 0) / validSetRows.length) * 10) / 10
    : null
  return { totalRuntime, avgRuntime, avgRoom, avgSet }
})

function hvacDeviceSortRank(dev: DaikinDevice): number {
  if (dev.hasActiveException || dev.stale || !dev.active) return 0
  if (isTempDeviated(dev)) return 1
  if (isDaikinDeviceRunning(dev)) return 2
  if (dev.deviceKind !== 'OUTDOOR') return 3
  return 4
}

const spaceScopedHvacDevices = computed(() => {
  if (!selectedSpaceName.value) return rawHvacDevices.value
  return rawHvacDevices.value.filter(dev => spaceNameOf(dev.spaceId) === selectedSpaceName.value)
})

const hvacFilterCounts = computed(() => {
  const base = spaceScopedHvacDevices.value
  return {
    ALL: base.length,
    RUNNING: base.filter(dev => isDaikinDeviceRunning(dev)).length,
    DEVIATED: base.filter(dev => isTempDeviated(dev)).length,
    EXCEPTION: base.filter(dev => dev.hasActiveException || dev.stale || !dev.active).length,
    STOPPED: base.filter(dev => !isDaikinDeviceRunning(dev)).length,
  }
})

const filteredHvacInspectionList = computed(() => {
  let list = [...spaceScopedHvacDevices.value]
  if (hvacQuickFilter.value === 'RUNNING') {
    list = list.filter(dev => isDaikinDeviceRunning(dev))
  } else if (hvacQuickFilter.value === 'DEVIATED') {
    list = list.filter(dev => isTempDeviated(dev))
  } else if (hvacQuickFilter.value === 'EXCEPTION') {
    list = list.filter(dev => dev.hasActiveException || dev.stale || !dev.active)
  } else if (hvacQuickFilter.value === 'STOPPED') {
    list = list.filter(dev => !isDaikinDeviceRunning(dev))
  }
  return list.sort((a, b) => hvacDeviceSortRank(a) - hvacDeviceSortRank(b))
})

const powerSpaceAnalysis = computed(() => {
  const map = new Map<string, { spaceName: string; totalCount: number; threePhaseCount: number }>()
  for (const meter of meterAssets.value) {
    const spaceName = meter.spaceName || daikinText('unassignedSpace')
    let entry = map.get(spaceName)
    if (!entry) {
      entry = { spaceName, totalCount: 0, threePhaseCount: 0 }
      map.set(spaceName, entry)
    }
    entry.totalCount++
    if (getMeterPhaseType(meter) === '3P') entry.threePhaseCount++
  }
  const rows = Array.from(map.values())
  const maxCount = Math.max(1, ...rows.map(r => r.totalCount))
  return rows.map(r => ({
    ...r,
    threePhasePct: Math.round((r.threePhaseCount / Math.max(1, r.totalCount)) * 100),
    barHeightPct: Math.max(15, Math.round((r.totalCount / maxCount) * 100)),
  }))
})

const filteredPowerList = computed(() => {
  let list = meterAssets.value
  if (selectedSpaceName.value) {
    list = list.filter(m => (m.spaceName || daikinText('unassignedSpace')) === selectedSpaceName.value)
  }
  if (powerQuickFilter.value !== 'ALL') {
    list = list.filter(m => getMeterPhaseType(m) === powerQuickFilter.value)
  }
  return list
})

const coldSummaryMetrics = computed(() => {
  const spaces = new Set(coldSourceAssets.value.map(item => item.spaceId).filter(Boolean))
  const totalPower = coldSourceAssets.value.reduce(
    (sum, item) => sum + Number((item as Record<string, unknown>).ratedPower ?? 0),
    0,
  )
  return {
    totalCount: coldSourceAssets.value.length,
    spaceCount: spaces.size,
    totalPower: Math.round(totalPower * 10) / 10,
  }
})

const selectedDaikinDevice = computed(() =>
  rawHvacDevices.value.find(item => item.equipmentId === selectedDaikinId.value),
)

function toggleSpaceSelection(spaceName: string) {
  selectedSpaceName.value = selectedSpaceName.value === spaceName ? '' : spaceName
}

function selectSubsystem(key: SubsystemKey) {
  activeSubsystem.value = key
  selectedSpaceName.value = ''
}

function formatModeAndFan(raw: DaikinDevice | Record<string, unknown>): string {
  const item = raw as DaikinDevice
  if (item.deviceKind === 'OUTDOOR') return daikinLabel(item.unitStatus?.value)
  const modeText = daikinLabel(item.mode?.value)
  const fanRaw = telemetryMap.value[item.equipmentId]?.fanSpeed ?? null
  if (!fanRaw) return modeText
  const fanText = daikinCurrentValue('fanSpeed', fanRaw)
  if (fanText === '—') return modeText
  return `${modeText} · ${fanText}`
}

function roomTempPercent(raw: DaikinDevice | Record<string, unknown>): number {
  const item = raw as DaikinDevice
  const room = telemetryMap.value[item.equipmentId]?.roomTemp
  if (room == null) return 0
  return Math.max(8, Math.min(95, Math.round(((room - 16) / 18) * 100)))
}

function setTempPercent(raw: DaikinDevice | Record<string, unknown>): number {
  const item = raw as DaikinDevice
  const set = telemetryMap.value[item.equipmentId]?.setTemp
  if (set == null) return 50
  return Math.max(8, Math.min(95, Math.round(((set - 16) / 18) * 100)))
}

function openMeterDrawer(row: AssetEquipment | Record<string, unknown>) {
  activeMeterDrawer.value = row as AssetEquipment
  meterDrawerOpen.value = true
}

async function hydrateOverviewTelemetry(items: DaikinDevice[]) {
  const seq = ++telemetrySeq
  await Promise.all(items.map(async item => {
    try {
      const isOutdoor = item.deviceKind === 'OUTDOOR'
      const [curRes, roomRes, setRes, runtimeRes] = await Promise.all([
        daikinApi.current(item.equipmentId).catch(() => null),
        isOutdoor ? Promise.resolve(null) : daikinApi.temperature(item.equipmentId, 'roomTemp').catch(() => null),
        isOutdoor ? Promise.resolve(null) : daikinApi.temperature(item.equipmentId, 'temperature').catch(() => null),
        daikinApi.observedRuntime(item.equipmentId, 'DAY').catch(() => null),
      ])
      if (seq !== telemetrySeq) return
      const findField = (name: string) => {
        const f = curRes?.fields?.find(row => row.fieldName === name)
        return f && f.status === 'PRESENT' && f.valueVisible ? f.normalizedValue : null
      }
      const latestOnMillis = runtimeRes?.items?.[0]?.onMillis
      const latestRuntime = latestOnMillis != null ? Math.round((latestOnMillis / 3600000) * 10) / 10 : null
      telemetryMap.value[item.equipmentId] = {
        fanSpeed: findField('fanSpeed'),
        roomTemp: roomRes?.reading?.value ?? null,
        setTemp: setRes?.reading?.value ?? null,
        unit: roomRes?.unit || setRes?.unit || '°C',
        dayOnHours: latestRuntime,
      }
    } catch {
      // 单设备补全失败不阻断运行总览主界面。
    }
  }))
}

async function loadBuildingOverview() {
  if (!buildingId.value) return
  selectedSpaceName.value = ''
  selectedDaikinId.value = null
  await Promise.all([
    daikinSpaces.run(() => daikinApi.spaces(buildingId.value)),
    daikinDevices.run(() => daikinApi.devices(buildingId.value, 1, '', '', '', '', undefined)),
    assetManagement.setEquipmentQuery({ buildingId: buildingId.value, page: 1, size: 100 }).catch(() => null),
  ])
  lastUpdatedAt.value = new Date().toISOString()
  if (daikinDevices.data.value?.items?.length) {
    void hydrateOverviewTelemetry(daikinDevices.data.value.items)
  }
}

function refreshOverview() {
  refreshTick.value++
  void loadBuildingOverview()
}

watch(buildingId, () => {
  void loadBuildingOverview()
})

onMounted(async () => {
  await buildings.run(listAccessibleBuildings)
  buildingId.value = buildings.data.value?.[0]?.buildingId ?? ''
})
</script>

<template>
  <section class="running-overview">
    <header class="overview-header">
      <div class="header-titles">
        <h1>{{ roText('title') }}</h1>
        <p>{{ roText('subtitle') }}</p>
      </div>
      <div class="header-controls">
        <label class="building-selector">
          <span>{{ t('dashboard.building') }}</span>
          <ElSelect v-model="buildingId" filterable :placeholder="t('dashboard.building')" :aria-label="t('dashboard.building')">
            <ElOption
              v-for="item in buildings.data.value ?? []"
              :key="item.buildingId"
              :value="item.buildingId"
              :label="item.buildingName"
            />
          </ElSelect>
        </label>
        <span v-if="lastUpdatedAt" class="updated-time">
          {{ roText('lastRefresh') }}{{ '：' }}{{ formatDateTime(lastUpdatedAt) }}
        </span>
        <ElButton :disabled="!buildingId" :loading="daikinDevices.loading.value" @click="refreshOverview">
          {{ roText('refreshBtn') }}
        </ElButton>
      </div>
    </header>

    <ElAlert v-if="buildings.error.value" :title="buildings.error.value" type="error" :closable="false" />
    <ElSkeleton v-if="buildings.loading.value" :rows="6" animated />
    <ElEmpty v-else-if="!buildingId" :description="t('dashboard.noBuilding')" />

    <template v-else>
      <div class="kpi-band">
        <article class="kpi-card kpi-card-pending">
          <header class="kpi-card-header">
            <span class="kpi-card-title">{{ roText('cardEnergyTitle') }}</span>
            <ElTag type="warning" effect="plain">{{ roText('cardEnergyPendingTag') }}</ElTag>
          </header>
          <div class="kpi-card-body">
            <div class="kpi-metric-main">
              <div class="kpi-value-row">
                <strong class="kpi-primary-num tone-muted">{{ '—' }}</strong>
                <span class="kpi-unit">{{ roText('cardEnergyUnit') }}</span>
              </div>
              <p class="kpi-sub-note">{{ roText('cardEnergySub') }}</p>
            </div>
          </div>
          <footer class="kpi-card-footer">
            <ElButton text type="primary" @click="router.push('/configuration/space/equipmentSpaces')">
              {{ roText('cardEnergyLink') }}
            </ElButton>
          </footer>
        </article>

        <article class="kpi-card kpi-card-pending">
          <header class="kpi-card-header">
            <span class="kpi-card-title">{{ roText('cardCarbonTitle') }}</span>
            <ElTag type="warning" effect="plain">{{ roText('cardCarbonPendingTag') }}</ElTag>
          </header>
          <div class="kpi-card-body">
            <div class="kpi-metric-main">
              <div class="kpi-value-row">
                <strong class="kpi-primary-num tone-muted">{{ '—' }}</strong>
                <span class="kpi-unit">{{ roText('cardCarbonUnit') }}</span>
              </div>
              <p class="kpi-sub-note">{{ roText('cardCarbonSub') }}</p>
            </div>
          </div>
          <footer class="kpi-card-footer">
            <ElButton text type="primary" @click="router.push('/configuration/factors/emissionFactors')">
              {{ roText('cardCarbonLink') }}
            </ElButton>
          </footer>
        </article>

        <article class="kpi-card">
          <header class="kpi-card-header">
            <span class="kpi-card-title">{{ roText('cardStructureTitle') }}</span>
            <ElTag type="info" effect="plain">{{ roText('cardStructureCenter') }}{{ ' ' }}{{ structureSegments.total }}</ElTag>
          </header>
          <div class="donut-card-body">
            <div class="donut-wrap">
              <svg class="donut-svg" viewBox="0 0 84 84" aria-hidden="true">
                <circle
                  cx="42"
                  cy="42"
                  r="33"
                  fill="none"
                  stroke="var(--bec-color-surface-secondary)"
                  stroke-width="8"
                />
                <circle
                  v-for="seg in structureSegments.items"
                  :key="seg.key"
                  cx="42"
                  cy="42"
                  r="33"
                  fill="none"
                  :stroke="seg.color"
                  stroke-width="8"
                  :stroke-dasharray="seg.dasharray"
                  :stroke-dashoffset="seg.dashoffset"
                  transform="rotate(-90 42 42)"
                />
              </svg>
              <div class="donut-center">
                <strong>{{ structureSegments.total }}</strong>
              </div>
            </div>
            <div class="donut-legend-grid">
              <div v-for="seg in structureSegments.items" :key="seg.key" class="donut-legend-item">
                <div class="legend-label-row">
                  <span class="legend-dot" :style="{ background: seg.color }" />
                  <span>{{ seg.label }}</span>
                </div>
                <strong class="legend-val">{{ seg.pct }}{{ '%' }}</strong>
              </div>
            </div>
          </div>
          <footer class="kpi-card-footer">
            <ElButton text type="primary" @click="router.push('/operations/devices/businessDevices')">
              {{ roText('cardStructureCenter') }}{{ ' →' }}
            </ElButton>
          </footer>
        </article>

        <article class="kpi-card">
          <header class="kpi-card-header">
            <span class="kpi-card-title">{{ roText('cardPostureTitle') }}</span>
            <ElTag :type="postureStats.deviatedCount > 0 ? 'warning' : 'success'" effect="plain">
              {{ roText('cardPostureCenter') }}
            </ElTag>
          </header>
          <div class="donut-card-body">
            <div class="donut-wrap">
              <svg class="donut-svg" viewBox="0 0 84 84" aria-hidden="true">
                <circle
                  cx="42"
                  cy="42"
                  r="33"
                  fill="none"
                  stroke="var(--bec-color-surface-secondary)"
                  stroke-width="8"
                />
                <circle
                  v-for="seg in postureStats.items"
                  :key="seg.key"
                  cx="42"
                  cy="42"
                  r="33"
                  fill="none"
                  :stroke="seg.color"
                  stroke-width="8"
                  :stroke-dasharray="seg.dasharray"
                  :stroke-dashoffset="seg.dashoffset"
                  transform="rotate(-90 42 42)"
                />
              </svg>
              <div class="donut-center">
                <strong>{{ postureStats.healthyRate }}{{ '%' }}</strong>
              </div>
            </div>
            <div class="donut-legend-grid">
              <div v-for="seg in postureStats.items" :key="seg.key" class="donut-legend-item">
                <div class="legend-label-row">
                  <span class="legend-dot" :style="{ background: seg.color }" />
                  <span>{{ seg.label }}</span>
                </div>
                <strong class="legend-val" :class="seg.tone">{{ seg.count }}{{ ' ' }}{{ daikinText('unitCountSuffix') }}</strong>
              </div>
            </div>
          </div>
          <footer class="kpi-card-footer">
            <ElButton text type="primary" @click="router.push('/operations/realtime/hvac')">
              {{ roText('cardPostureLink') }}
            </ElButton>
          </footer>
        </article>
      </div>

      <nav class="subsystem-switcher" :aria-label="roText('title')">
        <button
          v-for="tab in subsystemTabs"
          :key="tab.key"
          type="button"
          class="subsystem-tab"
          :class="{
            'subsystem-tab-active': activeSubsystem === tab.key,
            'subsystem-tab-pending': !tab.activeReady,
          }"
          @click="selectSubsystem(tab.key)"
        >
          <span class="subsystem-tab-label">{{ tab.label }}</span>
          <span v-if="tab.activeReady" class="subsystem-tab-badge">{{ tab.count }}</span>
          <span v-else class="subsystem-tab-badge badge-pending">{{ roText('pendingAccessBadge') }}</span>
        </button>
      </nav>

      <div v-if="activeSubsystem === 'HVAC'" class="workbench-grid">
        <section class="workbench-left-card">
          <header class="workbench-panel-header">
            <div>
              <h2>{{ roText('leftHvacTitle') }}</h2>
              <p>{{ roText('leftHvacSub') }}</p>
            </div>
          </header>

          <div class="summary-strip-3">
            <div class="summary-mini-box">
              <span class="summary-mini-label">{{ roText('metricTotalRuntime') }}</span>
              <strong class="summary-mini-val">
                {{ hvacSummaryMetrics.totalRuntime }}{{ ' ' }}<small>{{ roText('metricTotalRuntimeUnit') }}</small>
              </strong>
            </div>
            <div class="summary-mini-box">
              <span class="summary-mini-label">{{ roText('metricAvgRuntime') }}</span>
              <strong class="summary-mini-val">
                {{ hvacSummaryMetrics.avgRuntime }}{{ ' ' }}<small>{{ roText('metricAvgRuntimeUnit') }}</small>
              </strong>
            </div>
            <div class="summary-mini-box">
              <span class="summary-mini-label">{{ roText('metricAvgRoomTemp') }}</span>
              <strong class="summary-mini-val">
                {{ hvacSummaryMetrics.avgRoom != null ? `${hvacSummaryMetrics.avgRoom}°C` : '—' }}
              </strong>
              <span v-if="hvacSummaryMetrics.avgSet != null" class="summary-mini-sub">
                {{ roText('metricAvgSetTempPrefix') }}{{ ' ' }}{{ hvacSummaryMetrics.avgSet }}{{ '°C' }}
              </span>
            </div>
          </div>

          <ElEmpty v-if="!hvacSpaceAnalysis.length" :description="daikinText('none')" />
          <div v-else class="combo-chart-box">
            <div class="combo-legend-top">
              <span class="legend-chip"><i class="dot-bar-primary" />{{ roText('legendBarHvac') }}</span>
              <span class="legend-chip"><i class="dot-line-success" />{{ roText('legendLineHvac') }}</span>
              <span class="legend-chip">{{ roText('legendBaselineHvac') }}</span>
            </div>
            <div class="dual-axis-stage">
              <div class="y-axis-ticks y-axis-left">
                <span>{{ hvacChartMaxHours }}{{ ' h' }}</span>
                <span>{{ Math.round(hvacChartMaxHours * 0.75 * 10) / 10 }}{{ ' h' }}</span>
                <span>{{ Math.round(hvacChartMaxHours * 0.5 * 10) / 10 }}{{ ' h' }}</span>
                <span>{{ Math.round(hvacChartMaxHours * 0.25 * 10) / 10 }}{{ ' h' }}</span>
                <span>{{ '0 h' }}</span>
              </div>
              <div class="plot-canvas">
                <div class="plot-grid-lines" aria-hidden="true">
                  <span /><span /><span /><span /><span />
                </div>
                <svg class="temp-polyline-svg" viewBox="0 0 100 100" preserveAspectRatio="none" aria-hidden="true">
                  <polyline
                    v-if="hvacTempPolylinePoints"
                    :points="hvacTempPolylinePoints"
                    fill="none"
                    stroke="var(--bec-color-success)"
                    stroke-width="1.6"
                    vector-effect="non-scaling-stroke"
                  />
                </svg>
                <div class="combo-columns">
                  <button
                    v-for="row in hvacSpaceAnalysis"
                    :key="row.spaceName"
                    type="button"
                    class="combo-col-btn"
                    :class="{ 'combo-col-active': selectedSpaceName === row.spaceName }"
                    @click="toggleSpaceSelection(row.spaceName)"
                  >
                    <div class="col-plot-area">
                      <div
                        v-if="row.tempY != null"
                        class="temp-node-marker"
                        :class="row.deviatedCount > 0 ? 'node-warning' : 'node-success'"
                        :style="{ top: `${row.tempY}%` }"
                      >
                        <span class="combo-temp-pill">{{ row.avgRoomTemp }}{{ '°C' }}</span>
                      </div>
                      <div class="bar-column-wrap">
                        <strong class="combo-col-val">{{ row.runtimeHours }}{{ 'h' }}</strong>
                        <div
                          class="combo-bar-fill"
                          :class="row.deviatedCount > 0 ? 'bar-fill-warning' : 'bar-fill-primary'"
                          :style="{ height: `${row.barHeightPct}%` }"
                        />
                      </div>
                    </div>
                    <span class="combo-col-name">{{ row.spaceName }}</span>
                  </button>
                </div>
              </div>
              <div class="y-axis-ticks y-axis-right">
                <span>{{ '30°C' }}</span>
                <span>{{ '27.5°C' }}</span>
                <span>{{ '25°C' }}</span>
                <span>{{ '22.5°C' }}</span>
                <span>{{ '20°C' }}</span>
              </div>
            </div>
          </div>
        </section>

        <section class="workbench-right-card">
          <header class="workbench-panel-header">
            <div class="right-header-row">
              <h2>{{ roText('rightHvacTitle') }}</h2>
              <ElButton v-if="selectedSpaceName" text type="primary" @click="selectedSpaceName = ''">
                {{ t('dashboard.spaceSummary.clearSpaceFilter') }}{{ '（' }}{{ selectedSpaceName }}{{ '）' }}
              </ElButton>
            </div>
            <div class="quick-filter-pills">
              <button
                v-for="f in ([
                  ['ALL', 'filterAll'],
                  ['RUNNING', 'filterRunning'],
                  ['DEVIATED', 'filterTempDeviated'],
                  ['EXCEPTION', 'filterException'],
                  ['STOPPED', 'filterStopped'],
                ] as const)"
                :key="f[0]"
                type="button"
                class="quick-pill"
                :class="{ 'quick-pill-active': hvacQuickFilter === f[0] }"
                @click="hvacQuickFilter = f[0]"
              >
                {{ roText(f[1]) }}{{ ' (' }}{{ hvacFilterCounts[f[0]] }}{{ ')' }}
              </button>
            </div>
          </header>

          <ElTable :data="filteredHvacInspectionList" row-key="equipmentId" :empty-text="daikinText('none')">
            <ElTableColumn :label="roText('colSpace')" width="96">
              <template #default="{ row }">{{ spaceNameOf(row.spaceId) }}</template>
            </ElTableColumn>
            <ElTableColumn :label="roText('colDeviceName')" min-width="140">
              <template #default="{ row }">
                <div class="table-device-cell">
                  <span class="status-dot" :class="isDaikinDeviceRunning(row) ? 'dot-running' : 'dot-stopped'" />
                  <div>
                    <strong>{{ row.equipmentName || daikinText('unnamed') }}</strong>
                    <div class="table-code-sub">{{ row.equipmentCode }}</div>
                  </div>
                </div>
              </template>
            </ElTableColumn>
            <ElTableColumn :label="roText('colModeFan')" width="105">
              <template #default="{ row }">
                <strong :class="`tone-${daikinFieldTone('mode', row.mode?.value)}`">{{ formatModeAndFan(row) }}</strong>
              </template>
            </ElTableColumn>
            <ElTableColumn :label="roText('colTempCompare')" min-width="155">
              <template #default="{ row }">
                <span v-if="row.deviceKind === 'OUTDOOR'" class="tone-muted">{{ daikinText('outdoorNotApplicable') }}</span>
                <div v-else class="temp-compare-cell">
                  <div class="temp-scale-track">
                    <div
                      class="temp-scale-fill"
                      :class="isTempDeviated(row) ? 'scale-warning' : 'scale-success'"
                      :style="{ width: `${roomTempPercent(row)}%` }"
                    />
                    <span class="temp-set-marker" :style="{ left: `${setTempPercent(row)}%` }" />
                  </div>
                  <div class="temp-pair-nums">
                    <strong>{{ telemetryMap[row.equipmentId]?.roomTemp != null ? `${telemetryMap[row.equipmentId]?.roomTemp}°C` : '—' }}</strong>
                    <span>{{ ' / ' }}</span>
                    <span class="tone-muted">{{ telemetryMap[row.equipmentId]?.setTemp != null ? `${telemetryMap[row.equipmentId]?.setTemp}°C` : '—' }}</span>
                  </div>
                </div>
              </template>
            </ElTableColumn>
            <ElTableColumn :label="roText('colEvaluation')" width="115">
              <template #default="{ row }">
                <ElTag v-if="row.hasActiveException" type="danger">{{ roText('evalException') }}</ElTag>
                <ElTag v-else-if="row.stale" type="warning">{{ roText('evalStale') }}</ElTag>
                <ElTag v-else-if="!isDaikinDeviceRunning(row)" type="info">{{ roText('evalStopped') }}</ElTag>
                <ElTag v-else-if="isTempDeviated(row)" type="warning">
                  {{ roText('evalDeviationPrefix') }}{{ tempDiffValue(row) != null ? ` (${(tempDiffValue(row) ?? 0) > 0 ? '+' : ''}${tempDiffValue(row)}°C)` : '' }}
                </ElTag>
                <ElTag v-else type="success">{{ roText('evalCompliant') }}</ElTag>
              </template>
            </ElTableColumn>
            <ElTableColumn :label="roText('colAction')" width="96">
              <template #default="{ row }">
                <ElButton text type="primary" @click="selectedDaikinId = row.equipmentId">
                  {{ roText('actionViewCurve') }}
                </ElButton>
              </template>
            </ElTableColumn>
          </ElTable>
        </section>
      </div>

      <div v-else-if="activeSubsystem === 'POWER'" class="workbench-grid">
        <section class="workbench-left-card">
          <header class="workbench-panel-header">
            <div>
              <h2>{{ roText('leftPowerTitle') }}</h2>
              <p>{{ roText('leftPowerSub') }}</p>
            </div>
          </header>

          <div class="summary-strip-3">
            <div class="summary-mini-box">
              <span class="summary-mini-label">{{ roText('metricTotalMeters') }}</span>
              <strong class="summary-mini-val">{{ meterAssets.length }}</strong>
            </div>
            <div class="summary-mini-box">
              <span class="summary-mini-label">{{ roText('metricThreePhaseMeters') }}</span>
              <strong class="summary-mini-val tone-primary">
                {{ meterAssets.filter(m => getMeterPhaseType(m) === '3P').length }}
              </strong>
            </div>
            <div class="summary-mini-box">
              <span class="summary-mini-label">{{ roText('metricSinglePhaseMeters') }}</span>
              <strong class="summary-mini-val">
                {{ meterAssets.filter(m => getMeterPhaseType(m) === '1P').length }}
              </strong>
            </div>
          </div>

          <ElEmpty v-if="!powerSpaceAnalysis.length" :description="t('assetManagement.powerMonitoring.emptyMeters')" />
          <div v-else class="combo-chart-box">
            <div class="combo-columns">
              <button
                v-for="row in powerSpaceAnalysis"
                :key="row.spaceName"
                type="button"
                class="combo-col-btn"
                :class="{ 'combo-col-active': selectedSpaceName === row.spaceName }"
                @click="toggleSpaceSelection(row.spaceName)"
              >
                <div class="col-plot-area">
                  <div class="bar-column-wrap">
                    <span class="tone-primary">{{ '3P ' }}{{ row.threePhasePct }}{{ '%' }}</span>
                    <strong class="combo-col-val">{{ row.totalCount }}</strong>
                    <div class="combo-bar-fill bar-fill-primary" :style="{ height: `${row.barHeightPct}%` }" />
                  </div>
                </div>
                <span class="combo-col-name">{{ row.spaceName }}</span>
              </button>
            </div>
            <footer class="combo-legend-footer">
              <span class="legend-chip"><i class="dot-bar-primary" />{{ roText('legendBarPower') }}</span>
              <span class="legend-chip"><i class="dot-line-warning" />{{ roText('legendLinePower') }}</span>
            </footer>
          </div>
        </section>

        <section class="workbench-right-card">
          <header class="workbench-panel-header">
            <div class="right-header-row">
              <h2>{{ roText('rightPowerTitle') }}</h2>
              <ElButton v-if="selectedSpaceName" text type="primary" @click="selectedSpaceName = ''">
                {{ t('dashboard.spaceSummary.clearSpaceFilter') }}{{ '（' }}{{ selectedSpaceName }}{{ '）' }}
              </ElButton>
            </div>
            <div class="quick-filter-pills">
              <button
                v-for="f in ([
                  ['ALL', 'filterMeterAll'],
                  ['3P', 'filterMeter3P'],
                  ['1P', 'filterMeter1P'],
                ] as const)"
                :key="f[0]"
                type="button"
                class="quick-pill"
                :class="{ 'quick-pill-active': powerQuickFilter === f[0] }"
                @click="powerQuickFilter = f[0]"
              >
                {{ roText(f[1]) }}
              </button>
            </div>
          </header>

          <ElTable :data="filteredPowerList" row-key="equipmentId" :empty-text="t('assetManagement.powerMonitoring.emptyMeters')">
            <ElTableColumn :label="roText('colSpace')" width="105">
              <template #default="{ row }">{{ row.spaceName || daikinText('unassignedSpace') }}</template>
            </ElTableColumn>
            <ElTableColumn :label="roText('colDeviceName')" min-width="145">
              <template #default="{ row }">
                <strong>{{ row.equipmentName }}</strong>
                <div class="table-code-sub">{{ row.equipmentCode }}</div>
              </template>
            </ElTableColumn>
            <ElTableColumn :label="roText('colMeterSpec')" width="105">
              <template #default="{ row }">
                <ElTag :type="getMeterPhaseType(row) === '3P' ? 'primary' : 'info'" effect="plain">
                  {{ getMeterPhaseType(row) === '3P' ? roText('filterMeter3P') : roText('filterMeter1P') }}
                </ElTag>
              </template>
            </ElTableColumn>
            <ElTableColumn :label="roText('colSystemGroup')" min-width="125">
              <template #default="{ row }">{{ row.systemGroupName || t('common.emptyValue') }}</template>
            </ElTableColumn>
            <ElTableColumn :label="roText('colEvaluation')" width="96">
              <template #default="{ row }">
                <ElTag :type="isMeterActive(row) ? 'success' : 'info'">
                  {{ isMeterActive(row) ? roText('evalMeterOnline') : roText('evalMeterDisabled') }}
                </ElTag>
              </template>
            </ElTableColumn>
            <ElTableColumn :label="roText('colAction')" width="92">
              <template #default="{ row }">
                <ElButton text type="primary" @click="openMeterDrawer(row)">
                  {{ roText('actionViewTrend') }}
                </ElButton>
              </template>
            </ElTableColumn>
          </ElTable>
        </section>
      </div>

      <div v-else-if="activeSubsystem === 'COLD_SOURCE'" class="workbench-grid">
        <section class="workbench-left-card">
          <header class="workbench-panel-header">
            <div>
              <h2>{{ roText('leftColdTitle') }}</h2>
              <p>{{ roText('leftColdSub') }}</p>
            </div>
          </header>
          <div class="summary-strip-3">
            <div class="summary-mini-box">
              <span class="summary-mini-label">{{ roText('metricColdTotal') }}</span>
              <strong class="summary-mini-val">{{ coldSummaryMetrics.totalCount }}</strong>
            </div>
            <div class="summary-mini-box">
              <span class="summary-mini-label">{{ roText('metricColdSpaces') }}</span>
              <strong class="summary-mini-val">{{ coldSummaryMetrics.spaceCount }}</strong>
            </div>
            <div class="summary-mini-box">
              <span class="summary-mini-label">{{ roText('metricColdRatedPower') }}</span>
              <strong class="summary-mini-val">
                {{ coldSummaryMetrics.totalPower }}{{ ' ' }}<small>{{ roText('kwUnit') }}</small>
              </strong>
            </div>
          </div>
          <ElEmpty v-if="!coldSourceAssets.length" :description="roText('unconfiguredTitle')" />
        </section>

        <section class="workbench-right-card">
          <header class="workbench-panel-header">
            <h2>{{ roText('rightColdTitle') }}</h2>
          </header>
          <ElTable :data="coldSourceAssets" row-key="equipmentId" :empty-text="roText('unconfiguredTitle')">
            <ElTableColumn :label="roText('colSpace')" min-width="120">
              <template #default="{ row }">{{ row.spaceName || daikinText('unassignedSpace') }}</template>
            </ElTableColumn>
            <ElTableColumn :label="roText('colDeviceName')" min-width="170">
              <template #default="{ row }">
                <strong>{{ row.equipmentName }}</strong>
                <div class="table-code-sub">{{ row.equipmentCode }}</div>
              </template>
            </ElTableColumn>
            <ElTableColumn :label="roText('colRatedCapacityPower')" min-width="150">
              <template #default="{ row }">
                {{ formatNumber(row.ratedCapacity) }}{{ ' / ' }}{{ formatNumber(row.ratedPower) }}{{ ' ' }}{{ roText('kwUnit') }}
              </template>
            </ElTableColumn>
            <ElTableColumn :label="roText('colAction')" width="130">
              <template #default>
                <ElButton text type="primary" @click="router.push('/operations/realtime/hvac')">
                  {{ roText('actionGoColdMonitor') }}
                </ElButton>
              </template>
            </ElTableColumn>
          </ElTable>
        </section>
      </div>

      <section v-else class="unconfigured-card">
        <ElEmpty :description="roText('unconfiguredTitle')">
          <p class="unconfigured-desc">{{ roText('unconfiguredDesc') }}</p>
          <ElButton type="primary" plain @click="router.push('/configuration/ingestion/pendingDevices')">
            {{ roText('goPendingDevices') }}
          </ElButton>
        </ElEmpty>
      </section>

      <DaikinDeviceDetail
        v-if="selectedDaikinId"
        :key="selectedDaikinId"
        :equipment-id="selectedDaikinId"
        :equipment-name="selectedDaikinDevice?.equipmentName"
        :equipment-code="selectedDaikinDevice?.equipmentCode"
        :device-kind="selectedDaikinDevice?.deviceKind"
        :space-name="spaceNameOf(selectedDaikinDevice?.spaceId)"
        :refresh-tick="refreshTick"
        :visible="Boolean(selectedDaikinId)"
        @close="selectedDaikinId = null"
      />

      <ElDrawer
        v-model="meterDrawerOpen"
        size="min(var(--bec-dialog-width), 100%)"
        destroy-on-close
        :title="activeMeterDrawer?.equipmentName || roText('meterDrawerTitle')"
      >
        <MeterRealtimeBoard
          v-if="activeMeterDrawer"
          :equipment-id="activeMeterDrawer.equipmentId"
          :equipment="activeMeterDrawer"
        />
      </ElDrawer>
    </template>
  </section>
</template>

<style scoped>
.running-overview { display: grid; gap: var(--bec-space-group); min-width: 0; }
.overview-header { display: flex; flex-wrap: wrap; align-items: flex-start; justify-content: space-between; gap: var(--bec-space-group); }
.header-titles h1 { margin: 0; font-size: var(--bec-font-size-system); }
.header-titles p { margin: var(--bec-ref-space-4) 0 0; color: var(--bec-color-text-secondary); line-height: var(--bec-line-height); }
.header-controls { display: flex; flex-wrap: wrap; align-items: center; gap: var(--bec-space-group); }
.building-selector { display: flex; align-items: center; gap: var(--bec-space-tight); }
.building-selector .el-select { width: calc(var(--bec-control-height) * 6); }
.updated-time { font-size: var(--bec-font-size-small); color: var(--bec-color-text-secondary); }

.kpi-band { display: grid; grid-template-columns: repeat(auto-fit, minmax(calc(var(--bec-control-height) * 7), 1fr)); gap: var(--bec-space-group); }
.kpi-card { display: grid; align-content: space-between; gap: var(--bec-space-tight); padding: var(--bec-panel-padding); background: var(--bec-color-surface); border: var(--bec-border-width) solid var(--bec-color-border); border-radius: var(--bec-management-radius); box-shadow: var(--bec-shadow-card); }
.kpi-card-header { display: flex; align-items: center; justify-content: space-between; gap: var(--bec-space-tight); }
.kpi-card-title { font-size: var(--bec-font-size-body); font-weight: var(--bec-font-weight-heading); color: var(--bec-color-text-secondary); }
.kpi-card-body { display: flex; align-items: flex-end; justify-content: space-between; gap: var(--bec-space-group); }
.kpi-metric-main { display: grid; gap: var(--bec-ref-space-4); }
.kpi-value-row { display: flex; align-items: baseline; gap: var(--bec-ref-space-4); }
.kpi-primary-num { font-size: var(--bec-font-size-system); font-weight: var(--bec-font-weight-heading); font-family: var(--bec-font-family-number); color: var(--bec-color-text-primary); }
.kpi-unit { font-size: var(--bec-font-size-small); color: var(--bec-color-text-secondary); }
.kpi-sub-note { margin: 0; font-size: var(--bec-ref-font-12); color: var(--bec-color-text-secondary); line-height: var(--bec-line-height); }
.kpi-card-footer { display: flex; justify-content: flex-end; border-top: var(--bec-border-width) solid var(--bec-color-divider); padding-top: var(--bec-ref-space-8); }

.kpi-card-pending { background: color-mix(in srgb, var(--bec-color-surface-secondary) 45%, var(--bec-color-surface)); }

.donut-card-body { display: flex; align-items: center; gap: var(--bec-ref-space-12); }
.donut-wrap { position: relative; width: var(--bec-ref-space-64); height: var(--bec-ref-space-64); flex-shrink: 0; }
.donut-svg { width: 100%; height: 100%; }
.donut-center { position: absolute; inset: 0; display: flex; align-items: center; justify-content: center; text-align: center; pointer-events: none; }
.donut-center strong { font-size: var(--bec-ref-font-12); font-family: var(--bec-font-family-number); font-weight: var(--bec-font-weight-heading); color: var(--bec-color-text-primary); }
.donut-legend-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: var(--bec-ref-space-4) var(--bec-ref-space-8); flex: 1; min-width: 0; }
.donut-legend-item { display: flex; align-items: center; justify-content: space-between; gap: var(--bec-ref-space-4); min-width: 0; }
.legend-label-row { display: inline-flex; align-items: center; gap: var(--bec-ref-space-4); font-size: var(--bec-ref-font-12); color: var(--bec-color-text-secondary); white-space: nowrap; }
.legend-dot { width: var(--bec-ref-space-8); height: var(--bec-ref-space-8); border-radius: var(--bec-radius-tag); flex-shrink: 0; }
.legend-val { font-size: var(--bec-ref-font-12); font-family: var(--bec-font-family-number); font-weight: var(--bec-font-weight-heading); white-space: nowrap; }

.subsystem-switcher { display: flex; flex-wrap: wrap; align-items: center; gap: var(--bec-space-tight); padding: var(--bec-space-tight) var(--bec-space-group); background: var(--bec-color-surface); border: var(--bec-border-width) solid var(--bec-color-border); border-radius: var(--bec-management-radius); box-shadow: var(--bec-shadow-card); }
.subsystem-tab { display: inline-flex; align-items: center; gap: var(--bec-space-tight); padding: var(--bec-ref-space-8) var(--bec-space-group); background: var(--bec-color-surface-secondary); border: var(--bec-border-width) solid var(--bec-color-border); border-radius: var(--bec-radius-tag); cursor: pointer; font-family: inherit; font-size: var(--bec-font-size-body); font-weight: var(--bec-font-weight-heading); color: var(--bec-color-text-secondary); }
.subsystem-tab-active { background: var(--bec-color-action-soft); border-color: var(--bec-color-brand-primary); color: var(--bec-color-brand-primary); }
.subsystem-tab-pending { opacity: 0.75; border-style: dashed; }
.subsystem-tab-badge { padding: 0 var(--bec-ref-space-8); border-radius: var(--bec-radius-tag); background: var(--bec-color-surface); color: var(--bec-color-text-primary); font-size: var(--bec-ref-font-12); font-family: var(--bec-font-family-number); }
.subsystem-tab-active .subsystem-tab-badge { background: var(--bec-color-action-primary); color: var(--bec-color-on-action); }

.workbench-grid { display: grid; grid-template-columns: 5fr 7fr; gap: var(--bec-space-group); align-items: stretch; }
.workbench-left-card, .workbench-right-card { display: grid; align-content: start; gap: var(--bec-space-group); padding: var(--bec-panel-padding); background: var(--bec-color-surface); border: var(--bec-border-width) solid var(--bec-color-border); border-radius: var(--bec-management-radius); box-shadow: var(--bec-shadow-card); min-width: 0; }
.workbench-panel-header { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: var(--bec-space-tight); }
.workbench-panel-header h2 { margin: 0; font-size: var(--bec-font-size-title); font-weight: var(--bec-font-weight-heading); }
.workbench-panel-header p { margin: var(--bec-ref-space-4) 0 0; font-size: var(--bec-ref-font-12); color: var(--bec-color-text-secondary); }
.right-header-row { display: flex; align-items: center; justify-content: space-between; gap: var(--bec-space-tight); flex-wrap: wrap; }

.summary-strip-3 { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: var(--bec-space-tight); }
.summary-mini-box { display: grid; gap: var(--bec-ref-space-4); padding: var(--bec-ref-space-8) var(--bec-ref-space-12); background: var(--bec-color-surface-secondary); border: var(--bec-border-width) solid var(--bec-color-border); border-radius: var(--bec-radius-card); }
.summary-mini-label { font-size: var(--bec-ref-font-12); color: var(--bec-color-text-secondary); }
.summary-mini-val { font-size: var(--bec-font-size-title); font-family: var(--bec-font-family-number); font-weight: var(--bec-font-weight-heading); }
.summary-mini-sub { font-size: var(--bec-ref-font-12); color: var(--bec-color-text-secondary); }

.combo-chart-box { display: grid; gap: var(--bec-ref-space-12); padding: var(--bec-ref-space-12); background: var(--bec-color-surface); border: var(--bec-border-width) solid var(--bec-color-divider); border-radius: var(--bec-radius-card); }
.combo-legend-top, .combo-legend-footer { display: flex; flex-wrap: wrap; align-items: center; gap: var(--bec-space-group); font-size: var(--bec-ref-font-12); color: var(--bec-color-text-secondary); }
.legend-chip { display: inline-flex; align-items: center; gap: var(--bec-ref-space-4); }
.dot-bar-primary, .dot-line-warning, .dot-line-success { display: inline-block; width: var(--bec-ref-space-8); height: var(--bec-ref-space-8); border-radius: var(--bec-radius-tag); }
.dot-bar-primary { background: var(--bec-color-brand-primary); }
.dot-line-warning { background: var(--bec-color-warning); }
.dot-line-success { background: var(--bec-color-success); }

.dual-axis-stage { display: grid; grid-template-columns: auto 1fr auto; gap: var(--bec-ref-space-8); align-items: stretch; min-height: calc(var(--bec-ref-space-64) * 3.8); }
.y-axis-ticks { display: flex; flex-direction: column; justify-content: space-between; padding-bottom: var(--bec-ref-space-24); font-size: var(--bec-ref-font-12); font-family: var(--bec-font-family-number); color: var(--bec-color-text-secondary); }
.y-axis-left { text-align: right; }
.y-axis-right { text-align: left; color: var(--bec-color-success); }
.plot-canvas { position: relative; display: flex; flex-direction: column; min-width: 0; }
.plot-grid-lines { position: absolute; inset: 0 0 var(--bec-ref-space-24) 0; display: flex; flex-direction: column; justify-content: space-between; pointer-events: none; }
.plot-grid-lines span { border-bottom: var(--bec-border-width) dashed var(--bec-color-divider); }
.plot-grid-lines span:last-child { border-bottom-style: solid; border-bottom-color: var(--bec-color-border); }
.temp-polyline-svg { position: absolute; inset: 0 0 var(--bec-ref-space-24) 0; width: 100%; height: calc(100% - var(--bec-ref-space-24)); pointer-events: none; z-index: var(--bec-layer-panels); }

.combo-columns { position: relative; display: grid; grid-template-columns: repeat(auto-fit, minmax(var(--bec-ref-space-40), 1fr)); gap: var(--bec-ref-space-4); flex: 1; align-items: stretch; z-index: var(--bec-layer-navigation); }
.combo-col-btn { display: flex; flex-direction: column; align-items: center; justify-content: flex-end; gap: var(--bec-ref-space-4); padding: var(--bec-ref-space-4) var(--bec-ref-space-4) 0; background: transparent; border: var(--bec-border-width) solid transparent; border-radius: var(--bec-radius-card); cursor: pointer; font-family: inherit; }
.combo-col-btn:hover, .combo-col-active { background: var(--bec-color-action-soft); border-color: var(--bec-color-brand-primary); }
.col-plot-area { position: relative; flex: 1; width: 100%; display: flex; flex-direction: column; align-items: center; justify-content: flex-end; min-height: calc(var(--bec-ref-space-64) * 3); }
.bar-column-wrap { width: 100%; height: 100%; display: flex; flex-direction: column; align-items: center; justify-content: flex-end; gap: var(--bec-ref-space-4); }
.combo-bar-track { width: var(--bec-ref-space-24); height: calc(var(--bec-ref-space-64) * 2.5); background: var(--bec-color-surface-secondary); border-radius: var(--bec-radius-card); display: flex; align-items: flex-end; overflow: hidden; }
.combo-bar-fill { width: var(--bec-ref-space-24); border-radius: var(--bec-radius-card) var(--bec-radius-card) 0 0; }
.bar-fill-primary { background: var(--bec-color-brand-primary); }
.bar-fill-warning { background: var(--bec-color-warning); }
.temp-node-marker { position: absolute; left: 50%; transform: translate(-50%, -50%); width: var(--bec-ref-space-8); height: var(--bec-ref-space-8); border-radius: var(--bec-radius-tag); background: var(--bec-color-surface); border: var(--bec-focus-width) solid var(--bec-color-success); z-index: var(--bec-layer-notices); }
.node-warning { border-color: var(--bec-color-warning); }
.combo-temp-pill { position: absolute; bottom: var(--bec-ref-space-12); left: 50%; transform: translateX(-50%); white-space: nowrap; font-size: var(--bec-ref-font-12); font-family: var(--bec-font-family-number); font-weight: var(--bec-font-weight-heading); color: var(--bec-color-success); background: var(--bec-color-surface); padding: 0 var(--bec-ref-space-4); border-radius: var(--bec-radius-control); }
.node-warning .combo-temp-pill { color: var(--bec-color-warning); }
.combo-col-val { font-size: var(--bec-ref-font-12); font-family: var(--bec-font-family-number); color: var(--bec-color-text-secondary); }
.combo-col-name { height: var(--bec-ref-space-20); font-size: var(--bec-ref-font-12); color: var(--bec-color-text-primary); font-weight: var(--bec-font-weight-heading); text-align: center; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: 100%; }

.quick-filter-pills { display: flex; flex-wrap: wrap; gap: var(--bec-ref-space-8); }
.quick-pill { padding: var(--bec-ref-space-4) var(--bec-ref-space-12); background: var(--bec-color-surface-secondary); border: var(--bec-border-width) solid var(--bec-color-border); border-radius: var(--bec-radius-tag); cursor: pointer; font-family: inherit; font-size: var(--bec-ref-font-12); font-weight: var(--bec-font-weight-heading); color: var(--bec-color-text-secondary); }
.quick-pill-active { background: var(--bec-color-action-primary); color: var(--bec-color-on-action); border-color: var(--bec-color-action-primary); }

.table-device-cell { display: flex; align-items: center; gap: var(--bec-space-tight); }
.table-code-sub { font-size: var(--bec-ref-font-12); color: var(--bec-color-text-secondary); font-family: var(--bec-font-family-number); }
.status-dot { display: inline-block; width: var(--bec-ref-space-8); height: var(--bec-ref-space-8); border-radius: var(--bec-radius-tag); flex-shrink: 0; }
.dot-running { background: var(--bec-color-success); }
.dot-stopped { background: var(--bec-color-text-disabled); }

.temp-compare-cell { display: grid; gap: var(--bec-ref-space-4); }
.temp-scale-track { position: relative; height: var(--bec-ref-space-8); background: var(--bec-color-surface-secondary); border-radius: var(--bec-radius-tag); overflow: hidden; }
.temp-scale-fill { height: 100%; border-radius: var(--bec-radius-tag); }
.scale-success { background: var(--bec-color-success); }
.scale-warning { background: var(--bec-color-warning); }
.temp-set-marker { position: absolute; top: 0; bottom: 0; width: var(--bec-focus-width); background: var(--bec-color-text-primary); }
.temp-pair-nums { display: inline-flex; align-items: baseline; font-family: var(--bec-font-family-number); font-size: var(--bec-ref-font-12); }

.unconfigured-card { padding: var(--bec-panel-padding); background: var(--bec-color-surface); border: var(--bec-border-width) solid var(--bec-color-border); border-radius: var(--bec-radius-card); }
.unconfigured-desc { margin: 0 0 var(--bec-space-group); color: var(--bec-color-text-secondary); font-size: var(--bec-font-size-small); }

.tone-success { color: var(--bec-color-success); }
.tone-primary { color: var(--bec-color-brand-primary); }
.tone-warning { color: var(--bec-color-warning); }
.tone-danger { color: var(--bec-color-error); }
.tone-muted { color: var(--bec-color-text-secondary); }
.tone-default { color: var(--bec-color-text-primary); }
</style>
