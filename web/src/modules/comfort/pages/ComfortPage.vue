<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import ChartView from '@/shared/charts/ChartView.vue'
import {
  ElAlert, ElButton, ElDialog, ElDrawer, ElInput, ElOption, ElPopover, ElRadioButton, ElRadioGroup, ElSelect,
} from '@/shared/ui'
import { formatDateTime, formatNumber } from '@/shared/utils/format'
import { t } from '@/locales'
import { useSession, type GrantedMenu } from '@/modules/auth/public'
import { messages as alarmCopy } from '@/modules/alarms/public'
import { useHvacControlAccess } from '@/modules/dashboard/public'
import { chinaDay, weatherStatusMessage } from '@/modules/weather/public'
import {
  comfortQueryFailure, type ComfortDoorStats, type ComfortInputKey, type ComfortManualInputs,
  type ComfortOccupancy, type ComfortParameterUpdate, type DoorVisualState,
} from '../api/comfort'
import ComfortDoorPanels from '../components/ComfortDoorPanels.vue'
import ComfortPanel from '../components/ComfortPanel.vue'
import ComfortRoomAlarms from '../components/ComfortRoomAlarms.vue'
import ComfortRoomScene from '../components/ComfortRoomScene.vue'
import { useComfort } from '../composables/useComfort'
import copy from '../locales/zh-CN'
import { contactState, contactSummaryText, contactTotal, peopleAxisMax, peopleHourlyOption, peopleTrendOption } from '../models/sensor-charts'
import { carbonChartOption, pmvChartOption, temperatureChartOption, temperaturesByHour } from '../models/temperature-chart'

const props = defineProps<{ title?: string }>()
const {
  buildings, buildingId, rooms, roomId, overview, trends, electricity, weather, occupancy, doorStats, hvac, parameters,
  alarms, alarmDetails, alarmPage, alarmPageSize,
  day, now, pageVisible, refreshing, refresh, selectBuilding, selectRoom, selectDoor, saveParameters,
} = useComfort()
const session = useSession()
const { testOpenControls } = useHvacControlAccess()
const hvacPath = '/operations/realtime/hvac'
const alarmPath = '/operations/alarms/liveAlarms'
function hasMenuPath(menus: GrantedMenu[], path: string): boolean {
  return menus.some(menu => menu.status === 1 && menu.visible === 1
    && ((menu.menuType === 'C' && menu.path === path) || hasMenuPath(menu.children ?? [], path)))
}
const canOpenHvac = computed(() => !hvac.error.value && hvac.data.value?.controlEntryAvailable === true
  && (testOpenControls.value || hasMenuPath(session.menus, hvacPath)))
const alarmLocation = computed(() => buildingId.value && hasMenuPath(session.menus, alarmPath)
  ? { path: alarmPath, query: { buildingId: buildingId.value } } : null)
const missing = t('common.missing')
const trendTab = ref('temperature')
const peopleInterval = ref('1m')
const paramsOpen = ref(false)
const parameterSaving = ref(false)
const parameterError = ref('')
const parameterAdjustment = ref('')
const tLow = ref('')
const tHigh = ref('')
const manualDraft = ref<Partial<Record<ComfortInputKey, string>>>({})
const configuredBand = computed(() => parameters.data.value?.comfortBand ?? null)
const configuredInputs = computed(() => parameters.data.value?.manualInputs ?? {})
const canEditParameters = computed(() => !!parameters.data.value?.editable && !parameters.error.value)
const parametersBusy = computed(() => parameters.loading.value || parameterSaving.value)
const sceneOpen = ref(false)
const currentRoom = computed(() => rooms.data.value?.find(room => room.spaceId === roomId.value))
const currentRoomSubtitle = computed(() => [currentRoom.value?.spaceName, currentRoom.value?.floorName].filter(Boolean).join(copy.dotSeparator))
const reading = computed(() => overview.data.value?.indoorTemperature)
const measuredTa = computed(() => {
  const sample = reading.value
  return !overview.error.value && sample && !sample.stale && sample.celsius != null
    ? sample.celsius.toFixed(1) : missing
})
const pmv = computed(() => overview.error.value ? null : overview.data.value?.pmv)
const pmvAlert = computed(() => pmv.value?.sensation === 'WARM' || pmv.value?.sensation === 'COOL')
const pmvComfortable = computed(() => pmv.value?.sensation === 'COMFORTABLE')
const pmvPosition = computed(() => {
  const value = pmv.value?.value
  if (value == null) return ''
  const pct = Math.max(6, Math.min(94, ((value + 1) / 2) * 100))
  return `${pct.toFixed(1)}%`
})
function shortStatusBadge(text: string) {
  if (text.includes(copy.refreshFailedShort)) return copy.refreshFailedBadge
  if (text.includes(copy.offline)) return copy.offlineBadge
  return copy.staleBadge
}
function paramDisplayValue(field: typeof copy.parameterFields[number]) {
  return field.key === 'airTemperature' ? measuredTa.value : configuredInputs.value[field.key]?.toFixed(field.digits) ?? missing
}
const pmvMissingLabels = computed(() => copy.parameterFields
  .filter(field => pmv.value?.missingInputs.includes(field.key)).map(field => field.label))
const pmvNotice = computed(() => {
  if (overview.error.value) return overview.error.value
  if (!roomId.value) return copy.selectRoom
  switch (pmv.value?.status) {
    case 'AVAILABLE': return pmv.value.sensation ? copy.pmvSensation[pmv.value.sensation] : ''
    case 'TEMPERATURE_MISSING': return copy.pmvMissingTemperature
    case 'TEMPERATURE_STALE': return copy.pmvStaleTemperature
    case 'PARAMETERS_MISSING':
      return pmvMissingLabels.value.length === 1
        ? copy.pmvMissingParameter(pmvMissingLabels.value[0]!) : copy.pmvMissingParameters(pmvMissingLabels.value.length)
    case 'PARAMETERS_UNAVAILABLE': return copy.pmvParametersUnavailable
    case 'OUTSIDE_MODEL_RANGE': return copy.pmvOutsideRange
    default: return overview.loading.value ? t('common.loading') : copy.pmvNoResult
  }
})
const displayedReading = computed(() => reading.value ?? overview.data.value?.lastValidIndoorTemperature)
const temperature = computed(() => formatNumber(displayedReading.value?.celsius, { minimumFractionDigits: 1, maximumFractionDigits: 1 }))
const indoorDelayed = computed(() => displayedReading.value?.celsius != null
  && (!!overview.error.value || !reading.value || displayedReading.value.stale))
const observedTime = computed(() => displayedReading.value ? formatDateTime(displayedReading.value.observedAt, 'Asia/Shanghai') : missing)
const currentWeather = computed(() => weather.data.value?.current)
const outdoorSample = computed(() => currentWeather.value?.samples.at(-1))
const outdoorTemperature = computed(() => {
  const value = outdoorSample.value?.values.temperature_2m
  return value != null && Number.isFinite(value)
    ? `${formatNumber(value, { minimumFractionDigits: 1, maximumFractionDigits: 1 })}${copy.celsius}`
    : missing
})
const outdoorWeatherTitle = computed(() => {
  const series = currentWeather.value
  const time = outdoorSample.value?.time
  const status = weather.error.value
    || (series ? weatherStatusMessage(series, outdoorTemperature.value === missing ? copy.noOutdoorReading : undefined)
      : weather.loading.value ? t('common.loading') : copy.noOutdoorReading)
  return [copy.outdoors, time && formatDateTime(time, 'Asia/Shanghai'), status].filter(Boolean).join(' · ')
})
const outdoorWeatherDelayed = computed(() => outdoorTemperature.value !== missing
  && (!!weather.error.value || ['OVERDUE', 'FAILED'].includes(currentWeather.value?.updateStatus ?? '')))
const updateClock = new Intl.DateTimeFormat('zh-CN', {
  timeZone: 'Asia/Shanghai', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false,
})
function observationNotice(reason: string, observedAt?: string | null, value?: string) {
  if (!reason || !observedAt) return reason
  const time = new Date(observedAt)
  const label = chinaDay(time) === day.value ? updateClock.format(time) : formatDateTime(observedAt, 'Asia/Shanghai')
  return [reason, copy.previousObservation(value ? `${value} · ${label}` : label)].join(' · ')
}
const outdoorNotice = computed(() => {
  if (!outdoorWeatherDelayed.value) return ''
  const reason = weather.error.value ? copy.refreshFailedShort
    : currentWeather.value?.updateStatus === 'FAILED' ? copy.weatherUpdateFailed : copy.staleObservation
  return observationNotice(`${copy.outdoor} ${reason}`, outdoorSample.value?.time)
})
const sceneLabel = computed(() => [currentRoom.value?.spaceName,
  indoorDelayed.value || reading.value?.celsius == null ? undefined : `${temperature.value} ${copy.celsius}`].filter(Boolean).join(' · '))
const currentCount = computed(() => occupancy.error.value ? null : occupancy.data.value?.currentCount ?? null)
const doorState = computed<DoorVisualState>(() => contactState(doorStats.error.value ? null : doorStats.data.value?.doorOpen))
const sceneDoor = computed<DoorVisualState>(() => contactTotal(doorStats.data.value?.doors) > 1 ? 'unknown' : doorState.value)
const airConditioning = computed(() => hvac.data.value)
const acRunning = computed(() => !hvac.error.value && (airConditioning.value?.running ?? 0) > 0)
const acTime = computed(() => airConditioning.value?.lastObservedAt == null
  ? null : new Date(airConditioning.value.lastObservedAt).toISOString())
const acConnection = computed(() => {
  if (hvac.error.value) return copy.unknown
  switch (airConditioning.value?.status) {
    case 'AVAILABLE': return copy.online
    case 'PARTIAL': return copy.acPartial
    case 'STALE': return copy.staleObservation
    case 'OFFLINE': return copy.offline
    case 'UNKNOWN': return copy.unknown
    default: return copy.pending
  }
})
const acPrimaryLabel = computed(() => {
  const data = airConditioning.value
  if (!data?.total) return missing
  if (data.running == null) return copy.acUnknown
  return data.running > 0 ? copy.acOn : copy.acOff
})
const acUnitMeta = computed(() => {
  const data = airConditioning.value
  if (!data?.total) return ''
  if (data.running == null) return copy.acTotalUnits(data.total)
  return copy.acUnits(data.running, data.total)
})
const acWindLevel = computed(() => {
  if (!acRunning.value) return 0
  const speed = airConditioning.value?.airflowSpeed ?? airConditioning.value?.fanSpeed?.value
  switch (speed) {
    case 'high':
    case 'middleHigh':
      return 3
    case 'middle':
    case 'automatic':
      return 2
    case 'low':
    case 'middleLow':
      return 1
    default:
      return 1
  }
})
function settingText(status: string | undefined, value: string | undefined) {
  if (status === 'MIXED') return copy.acSettingMixed
  if (status === 'PARTIAL') return copy.acSettingPartial
  return value ?? missing
}
const acSetpoint = computed(() => {
  const setting = airConditioning.value?.setpoint
  return settingText(setting?.status, setting?.value == null ? undefined : `${formatNumber(setting.value, {
    minimumFractionDigits: 1, maximumFractionDigits: 1,
  })}${copy.celsius}`)
})
const acFan = computed(() => {
  const setting = airConditioning.value?.fanSpeed
  return settingText(setting?.status, setting?.value == null ? undefined : copy.acFanSpeed[setting.value])
})
const acNotice = computed(() => {
  if (hvac.error.value) {
    return hvac.error.value === copy.refreshFailed ? observationNotice(copy.refreshFailedShort, acTime.value) : hvac.error.value
  }
  const data = airConditioning.value
  if (data?.status === 'STALE' || data?.status === 'OFFLINE') return observationNotice(acConnection.value, acTime.value)
  return data?.unknown ? copy.acUnknownCount(data.unknown) : ''
})
const sceneContacts = computed(() => {
  const data = doorStats.error.value ? null : doorStats.data.value
  if (data?.doors && contactTotal(data.doors) > 1) {
    return [`${copy.contactDoors} ${contactSummaryText(data.doors)}`,
      data.windows && contactTotal(data.windows) ? `${copy.contactWindows} ${contactSummaryText(data.windows)}` : '',
    ].filter(Boolean)
  }
  return [isUnconfigured(data) ? copy.sceneDoorPending : copy.sceneDoor[sceneDoor.value],
    data?.windows && contactTotal(data.windows) ? `${copy.contactWindows} ${contactSummaryText(data.windows)}` : ''].filter(Boolean)
})
const sceneStatus = computed(() => [
  currentCount.value != null ? copy.personCount(currentCount.value)
    : isUnconfigured(occupancy.data.value) ? copy.scenePeoplePending : copy.peopleUnknown,
  ...sceneContacts.value,
  acRunning.value && airConditioning.value?.airflowSpeed
    ? copy.sceneAirflow(copy.acFanSpeed[airConditioning.value.airflowSpeed]) : '',
].filter(Boolean).join(' · '))

function isUnconfigured(data: ComfortOccupancy | ComfortDoorStats | null) {
  return data?.status === 'UNCONFIGURED' || data?.status === 'DISABLED'
}

function connectivity(data: ComfortOccupancy | ComfortDoorStats | null, error: string) {
  if (error) return copy.unknown
  if (isUnconfigured(data)) return copy.pending
  if (data?.communication?.state === 'OFFLINE') return copy.offline
  if (data?.status === 'STALE') return copy.staleObservation
  return data?.status === 'AVAILABLE' ? copy.online : copy.unknown
}

const connections = computed(() => [
  [copy.radar, connectivity(occupancy.data.value, occupancy.error.value)],
  [copy.contact, connectivity(doorStats.data.value, doorStats.error.value)],
  [copy.hvac, acConnection.value],
])

function sensorMessage(query: typeof occupancy | typeof doorStats, pending: string) {
  if (query.error.value) return query.error.value === copy.refreshFailed ? '' : query.error.value
  if (!roomId.value) return copy.selectRoom
  if (!query.data.value && query.loading.value) return t('common.loading')
  if (isUnconfigured(query.data.value)) return pending
  if (query.data.value?.status === 'AMBIGUOUS') return copy.sensorConflict
  return ''
}

function sensorNotice(data: ComfortOccupancy | ComfortDoorStats | null, error: string, value?: string) {
  if (!data) return ''
  const reason = [
    error === copy.refreshFailed ? copy.refreshFailedShort : '',
    data.communication?.state === 'OFFLINE' ? copy.offline : '',
    data.status === 'STALE' ? copy.staleObservation : '',
  ].filter(Boolean).join(' · ')
  return observationNotice(reason, data.lastObservedAt, value)
}
const peopleNotice = computed(() => {
  const data = occupancy.data.value
  return sensorNotice(data, occupancy.error.value, data?.lastObservedCount == null ? undefined : copy.personCount(data.lastObservedCount))
})
const doorNotice = computed(() => {
  const data = doorStats.data.value
  return sensorNotice(data, doorStats.error.value,
    data?.lastObservedOpen == null ? undefined : data.lastObservedOpen ? copy.sceneDoor.open : copy.sceneDoor.closed)
})
const peopleMessage = computed(() => sensorMessage(occupancy, copy.peoplePending))
const contactMessage = computed(() => sensorMessage(doorStats, copy.contactPending)
  || (doorStats.data.value?.status === 'SELECTION_REQUIRED' ? copy.selectDoorStats : '')
  || (doorStats.data.value?.status === 'NO_OBSERVATION' ? copy.noDoorSamples : ''))
const peopleSamples = computed(() => (peopleInterval.value === '1m'
  ? occupancy.data.value?.series1m : occupancy.data.value?.series15m) ?? [])
const peopleHours = computed(() => occupancy.data.value?.hourlyAvg ?? [])
const peopleScale = computed(() => peopleAxisMax(occupancy.data.value))
const peopleTrend = computed(() => peopleTrendOption(peopleSamples.value, peopleInterval.value === '15m', peopleScale.value))
const peopleHourlyTrend = computed(() => peopleHourlyOption(peopleHours.value, peopleScale.value))
const roomError = computed(() => buildings.error.value || rooms.error.value)
const refreshFailed = computed(() => [overview, trends, electricity, weather, occupancy, doorStats, hvac, parameters]
  .some(query => query.error.value === copy.refreshFailed))
const noBuildings = computed(() => !buildings.loading.value && !buildings.data.value?.length)
const noRooms = computed(() => !!buildingId.value && !rooms.loading.value && !rooms.data.value?.length)
const pageStatus = computed(() => {
  if (roomError.value) return roomError.value
  if (noBuildings.value) return copy.noBuildings
  if (noRooms.value) return copy.noRooms
  if (refreshFailed.value) return copy.refreshFailed
  return ''
})
const temperatureStatus = computed(() => {
  if (indoorDelayed.value) {
    const reason = [
      overview.error.value ? copy.refreshFailedShort : '',
      displayedReading.value?.stale ? copy.staleObservation : '',
    ].filter(Boolean).join(' · ') || copy.noObservation
    return observationNotice(reason, displayedReading.value?.observedAt)
  }
  if (overview.error.value) return overview.error.value
  if (!roomId.value) return copy.selectRoom
  if (!overview.data.value && overview.loading.value) return t('common.loading')
  if (overview.data.value?.temperatureSourceStatus === 'UNCONFIGURED') return copy.sourceUnavailable
  return reading.value?.celsius == null ? copy.noObservation : ''
})
const hours = computed(() => temperaturesByHour(trends.data.value?.date ?? day.value, now.value,
  trends.data.value, roomId.value ? weather.data.value : null))
const chart = computed(() => temperatureChartOption(hours.value, configuredBand.value))
const pmvSamples = computed(() => trends.data.value?.date === day.value ? trends.data.value.pmvHours ?? [] : [])
const pmvChart = computed(() => pmvChartOption(day.value, now.value, pmvSamples.value))
const pmvHasData = computed(() => pmvSamples.value.some(hour => hour.observation
  && Date.parse(hour.observation.sampledAt) <= now.value))
const roomElectricity = computed(() => electricity.data.value?.date === day.value ? electricity.data.value : null)
const power = computed(() => roomElectricity.value?.power)
const carbonRate = computed(() => electricity.error.value || power.value?.stale ? null : roomElectricity.value?.carbonRateKgPerHour)
const carbonToday = computed(() => roomElectricity.value?.carbonTodayKg)
const carbonSamples = computed(() => roomElectricity.value?.carbonHours ?? [])
const latestUpdatedAt = computed(() => [
  displayedReading.value?.celsius != null ? displayedReading.value.observedAt : null,
  outdoorTemperature.value !== missing ? outdoorSample.value?.time : null,
  occupancy.data.value?.lastObservedAt,
  doorStats.data.value?.lastObservedAt,
  ...(doorStats.data.value?.contacts?.map(contact => contact.lastObservedAt) ?? []),
  acTime.value,
  power.value?.observedAt,
  carbonSamples.value.at(-1)?.observedAt,
].filter((time): time is string => !!time && Number.isFinite(Date.parse(time)))
  .sort((left, right) => Date.parse(right) - Date.parse(left))[0])
const updatedTime = computed(() => latestUpdatedAt.value ? updateClock.format(new Date(latestUpdatedAt.value)) : missing)
const carbonChart = computed(() => carbonChartOption(day.value, now.value, carbonSamples.value))
const carbonHasData = computed(() => carbonSamples.value.some(point => Date.parse(point.observedAt) <= now.value))
const powerNotice = computed(() => {
  if (electricity.error.value) return observationNotice(electricity.error.value, power.value?.observedAt)
  if (power.value?.stale) return observationNotice(copy.staleObservation, power.value.observedAt)
  if (power.value) return ''
  if (electricity.loading.value) return t('common.loading')
  return roomElectricity.value?.carbonStatus === 'NO_METER' ? copy.noRoomMeter : copy.noPowerObservation
})
const carbonEmpty = computed(() => {
  if (roomElectricity.value?.carbonStatus === 'NO_FACTOR') return copy.noCarbonFactor
  if (roomElectricity.value?.carbonStatus === 'NO_METER') return copy.noRoomMeter
  return copy.incompleteCarbon
})
const chartHasData = computed(() => hours.value.some(hour => hour.indoor != null || hour.outdoor != null))
const outdoorHasData = computed(() => hours.value.some(hour => hour.outdoor != null))
const chartLoading = computed(() => !chartHasData.value && (trends.loading.value || weather.loading.value))
const chartDescription = computed(() => [
  copy.temperatureTab,
  trends.data.value?.date ?? day.value,
  copy.beijingTime,
  configuredBand.value && copy.temperatureBandLabel(configuredBand.value.tLow, configuredBand.value.tHigh),
  weather.error.value ? copy.outdoorsLoadFailed : !weather.loading.value && !outdoorHasData.value ? copy.outdoorsMissing : '',
].filter(Boolean).join(' · '))

function resetParameters() {
  tLow.value = configuredBand.value?.tLow?.toString() ?? ''
  tHigh.value = configuredBand.value?.tHigh?.toString() ?? ''
  manualDraft.value = Object.fromEntries(copy.parameterFields.map(({ key, digits }) => [
    key, configuredInputs.value[key]?.toFixed(digits) ?? '',
  ]))
  parameterError.value = ''
  parameterAdjustment.value = ''
}

watch([paramsOpen, () => parameters.data.value], ([open]) => { if (open) resetParameters() })

function clampInput(field: typeof copy.parameterFields[number]) {
  const raw = manualDraft.value[field.key]?.trim() ?? ''
  parameterAdjustment.value = ''
  if (!/^[+-]?(?:\d+(?:\.\d*)?|\.\d+)$/.test(raw)) return
  const value = Number(raw)
  if (!Number.isFinite(value)) return
  const clamped = Math.min(field.max, Math.max(field.min, value))
  if (clamped === value) return
  const formatted = clamped.toFixed(field.digits)
  manualDraft.value[field.key] = formatted
  parameterError.value = ''
  parameterAdjustment.value = copy.parameterAdjusted(field.full, formatted, field.unit)
}

function changedManualInputs(): ComfortManualInputs | undefined {
  const changes: ComfortManualInputs = {}
  for (const field of copy.parameterFields) {
    const raw = manualDraft.value[field.key]?.trim() ?? ''
    const value = raw ? Number(raw) : null
    if (value !== null) {
      if (!/^(?:\d+(?:\.\d*)?|\.\d+)$/.test(raw) || value < field.min || value > field.max) {
        parameterError.value = copy.parameterRangeError(field.full, field.range, field.unit)
        return
      }
      if (Number(value.toFixed(field.digits)) !== value) {
        parameterError.value = copy.parameterPrecisionError(field.full, field.digits)
        return
      }
    }
    if (value !== (configuredInputs.value[field.key] ?? null)) changes[field.key] = value
  }
  return changes
}

async function applyParameters() {
  const spaceId = roomId.value
  if (!spaceId || !canEditParameters.value || parametersBusy.value) return
  const lower = tLow.value.trim()
  const upper = tHigh.value.trim()
  const band = lower || upper ? { tLow: Number(lower), tHigh: Number(upper) } : null
  if (band && (!lower || !upper || !Number.isFinite(band.tLow) || !Number.isFinite(band.tHigh) || band.tLow >= band.tHigh)) {
    parameterError.value = copy.temperatureBandInvalid
    return
  }
  if (band && [band.tLow, band.tHigh].some(value => Math.abs(value) >= 1000 || !/^-?\d+(?:\.\d{1,2})?$/.test(String(value)))) {
    parameterError.value = copy.temperatureBandPrecision
    return
  }
  const inputs = changedManualInputs()
  if (!inputs) return
  const update: ComfortParameterUpdate = {}
  if (Object.keys(inputs).length) update.manualInputs = inputs
  const previousBand = configuredBand.value
  if (band?.tLow !== previousBand?.tLow || band?.tHigh !== previousBand?.tHigh) {
    update.tLow = band?.tLow ?? null
    update.tHigh = band?.tHigh ?? null
  }
  if (!Object.keys(update).length) {
    paramsOpen.value = false
    return
  }
  parameterError.value = ''
  parameterSaving.value = true
  try {
    await saveParameters(spaceId, update)
    if (roomId.value === spaceId) paramsOpen.value = false
  } catch (failure) {
    if (roomId.value === spaceId) parameterError.value = comfortQueryFailure(failure).denied ? copy.forbidden : copy.parameterSaveFailed
  } finally {
    parameterSaving.value = false
  }
}

watch(roomId, () => {
  paramsOpen.value = false
  sceneOpen.value = false
})
</script>

<template>
  <article class="comfort-page">
    <header class="comfort-topbar">
      <div class="comfort-toolbar">
        <div class="comfort-navigation">
          <h1>{{ props.title || t('workspaces.comfort') }}</h1>
          <div class="comfort-filter comfort-filter-building">
            <span>{{ copy.building }}</span>
            <ElSelect
              :model-value="buildingId" :aria-label="copy.building" :placeholder="copy.selectBuilding"
              :loading="buildings.loading.value" :disabled="!buildings.data.value?.length" :teleported="false"
              size="small" @update:model-value="selectBuilding"
            >
              <ElOption
                v-for="building in buildings.data.value ?? []" :key="building.buildingId"
                :value="building.buildingId" :label="building.buildingName"
              />
            </ElSelect>
          </div>
          <div class="comfort-filter comfort-filter-room">
            <span>{{ copy.room }}</span>
            <ElSelect
              :model-value="roomId" :aria-label="copy.room" :placeholder="copy.selectRoom"
              :loading="rooms.loading.value" :disabled="!rooms.data.value?.length" :teleported="false"
              size="small" @update:model-value="selectRoom"
            >
              <template #prefix>
                <span
                  v-if="currentRoom" class="comfort-select-dot"
                  :data-state="contactState(currentRoom.doorOpen)" aria-hidden="true"
                />
              </template>
              <ElOption
                v-for="room in rooms.data.value ?? []" :key="room.spaceId"
                :value="room.spaceId" :label="[room.spaceName, room.floorName].filter(Boolean).join(copy.dotSeparator)"
              >
                <div class="comfort-room-option">
                  <span>{{ [room.spaceName, room.floorName].filter(Boolean).join(copy.dotSeparator) }}</span>
                  <span class="comfort-room-badges">
                    <span
                      class="comfort-room-door"
                      :data-state="contactState(room.doorOpen)"
                      :title="room.doorSummary && contactTotal(room.doorSummary) > 1 ? contactSummaryText(room.doorSummary) : undefined"
                    >
                      {{ room.doorSummary && contactTotal(room.doorSummary) > 1
                        ? `${copy.contactDoors} ${contactSummaryText(room.doorSummary)}` : copy.sceneDoor[contactState(room.doorOpen)] }}
                    </span>
                    <span v-if="room.alarmSeverity" class="comfort-badge comfort-room-alarm" :data-severity="room.alarmSeverity">
                      {{ alarmCopy[room.alarmSeverity] }}
                    </span>
                  </span>
                </div>
              </ElOption>
            </ElSelect>
          </div>
        </div>

        <div class="comfort-toolbar-actions">
          <div class="comfort-connectivity">
            <span
              v-for="[label, state] in connections" :key="label"
              :class="{
                'sensor-online': state === copy.online,
                'sensor-offline': state === copy.offline,
                'sensor-delayed': state === copy.staleObservation || state === copy.acPartial,
              }"
            >
              {{ [label, state].join(copy.dotSeparator) }}
            </span>
          </div>
          <span class="comfort-meta comfort-updated-at">{{ copy.updated }}
            <time :datetime="latestUpdatedAt" :title="latestUpdatedAt ? formatDateTime(latestUpdatedAt, 'Asia/Shanghai') : missing">
              {{ updatedTime }}
            </time>
          </span>
          <ElButton size="small" :loading="refreshing" @click="refresh">{{ copy.refresh }}</ElButton>
          <ElButton size="small" type="primary" :disabled="!roomId" @click="paramsOpen = true">
            {{ canEditParameters ? copy.params : copy.viewParams }}
          </ElButton>
        </div>
      </div>
      <div class="comfort-alarm-strip">
        <div class="comfort-alarm-content">
          <span class="comfort-alarm-title">{{ copy.alarmSection }}</span>
          <ElPopover v-if="refreshFailed && !roomError" trigger="hover" placement="bottom-start" :teleported="false" width="auto">
            <template #reference>
              <span class="comfort-status-chip" role="status" tabindex="0">{{ copy.refreshFailedBadge }}</span>
            </template>
            <span class="comfort-observation-note">{{ pageStatus }}</span>
          </ElPopover>
          <span
            v-else-if="pageStatus" :role="roomError ? 'alert' : undefined"
            class="comfort-meta"
          >{{ pageStatus }}</span>
          <ComfortRoomAlarms
            v-if="roomId && !roomError" :key="roomId" v-model:page="alarmPage"
            :room="currentRoom?.spaceName ?? ''" :summary="alarms.data.value" :detail="alarmDetails.data.value"
            :loading="alarms.loading.value" :error="alarms.error.value"
            :detail-loading="alarmDetails.loading.value" :detail-error="alarmDetails.error.value"
            :page-size="alarmPageSize" :full-page="alarmLocation"
          />
        </div>
        <RouterLink v-if="alarmLocation" class="comfort-alarm-link" :to="alarmLocation">
          {{ copy.allAlarms }} <span aria-hidden="true">{{ copy.alarmLinkArrow }}</span>
        </RouterLink>
        <ElButton v-else link size="small" disabled>{{ copy.allAlarms }}</ElButton>
      </div>
    </header>

    <div class="comfort-grid">
      <div class="comfort-side comfort-people-column">
        <ComfortPanel :heading="copy.people">
          <template #status>
            <span class="comfort-meta comfort-people-now" :title="peopleMessage">
              {{ currentCount == null ? copy.peopleUnknown : copy.peopleNow(currentCount) }}
            </span>
            <ElPopover v-if="peopleNotice" trigger="hover" placement="top" :teleported="false" width="auto">
              <template #reference>
                <span class="comfort-status-chip" tabindex="0">{{ shortStatusBadge(peopleNotice) }}</span>
              </template>
              <span class="comfort-observation-note comfort-radar-note">{{ peopleNotice }}</span>
            </ElPopover>
          </template>
          <template #actions>
            <ElRadioGroup v-model="peopleInterval" size="small" :aria-label="copy.peopleInterval">
              <ElRadioButton value="1m">{{ copy.minute1 }}</ElRadioButton>
              <ElRadioButton value="15m">{{ copy.minute15 }}</ElRadioButton>
            </ElRadioGroup>
          </template>
          <div
            v-if="peopleSamples.some(item => item.count != null)" class="comfort-chart comfort-radar-trend"
            :title="peopleMessage"
          >
            <ChartView :option="peopleTrend" :accessible-label="copy.people" />
          </div>
          <div v-else class="comfort-empty">
            <span class="comfort-empty-badge">{{ peopleMessage || copy.noPeopleSamples }}</span>
          </div>
        </ComfortPanel>
        <ComfortPanel :heading="copy.peopleHourly">
          <template #status><span class="comfort-meta">{{ copy.peopleUnit }}</span></template>
          <div
            v-if="peopleHours.some(item => item.averageCount != null)" class="comfort-chart comfort-radar-hourly"
            :title="peopleMessage"
          >
            <ChartView :option="peopleHourlyTrend" :accessible-label="copy.peopleHourly" />
          </div>
          <div v-else class="comfort-empty">
            <span class="comfort-empty-badge">{{ peopleMessage || copy.noPeopleSamples }}</span>
          </div>
        </ComfortPanel>
      </div>

      <div class="comfort-center">
        <section class="comfort-core">
          <div class="comfort-kpis">
            <div class="comfort-kpi comfort-pmv">
              <div class="comfort-kpi-heading">
                <h2>{{ copy.pmv }}</h2>
              </div>
              <div class="comfort-kpi-body">
                <div class="comfort-kpi-val-wrap">
                  <strong
                    class="comfort-value comfort-pmv-value"
                    :class="{
                      'comfort-value-muted': pmv?.value == null,
                      'comfort-pmv-alert': pmvAlert,
                    }"
                  >{{ pmv?.value?.toFixed(2) ?? missing }}</strong>
                  <small class="comfort-kpi-unit">{{ copy.pmvUnit }}</small>
                </div>
                <div v-if="pmv?.value != null" class="comfort-kpi-anchor">
                  <div class="comfort-pmv-track" aria-hidden="true">
                    <div class="comfort-pmv-band" />
                    <div class="comfort-pmv-dot" :class="{ 'comfort-pmv-dot-alert': pmvAlert }" :style="{ left: pmvPosition }" />
                  </div>
                  <span class="comfort-pmv-range">{{ copy.pmvComfortRange }}</span>
                </div>
                <span v-else class="comfort-mini-pill">{{ copy.pmvComfortRange }}</span>
              </div>
              <span
                class="comfort-kpi-sub comfort-pmv-note"
                :class="{ 'comfort-sub-ok': pmvComfortable, 'comfort-sub-danger': pmvAlert }"
                :title="pmvMissingLabels.join('、') || pmvNotice"
              >{{ pmvNotice }}</span>
            </div>

            <div class="comfort-kpi">
              <div class="comfort-kpi-heading comfort-environment-heading">
                <h2>{{ copy.overview }}</h2>
                <span
                  v-if="configuredBand" class="comfort-badge comfort-band-badge"
                  :title="copy.temperatureBandLabel(configuredBand.tLow, configuredBand.tHigh)"
                >
                  {{ copy.temperatureBandRange(configuredBand.tLow, configuredBand.tHigh) }}
                </span>
              </div>
              <div class="comfort-kpi-body comfort-environment">
                <strong
                  class="comfort-value comfort-temperature"
                  :class="{
                    'comfort-value-muted': displayedReading?.celsius == null,
                    'comfort-indoor-delayed': indoorDelayed,
                  }"
                  :title="copy.indoors"
                >
                  {{ temperature }}<small v-if="displayedReading?.celsius != null">{{ copy.celsius }}</small>
                </strong>
                <span
                  class="comfort-mini-pill comfort-outdoor" :class="{ 'comfort-outdoor-delayed': outdoorWeatherDelayed }"
                  :title="outdoorWeatherTitle"
                >
                  {{ [copy.outdoor, outdoorTemperature].join(' ') }}
                </span>
              </div>
              <div class="comfort-kpi-sub-row">
                <span class="comfort-kpi-sub comfort-humidity">{{ [copy.humidity, configuredInputs.relativeHumidity == null
                  ? missing : copy.humidityPercent(configuredInputs.relativeHumidity)].join(' ') }}</span>
                <ElPopover v-if="temperatureStatus && indoorDelayed" trigger="hover" placement="top" :teleported="false" width="auto">
                  <template #reference>
                    <span class="comfort-status-chip" tabindex="0">{{ shortStatusBadge(temperatureStatus) }}</span>
                  </template>
                  <span class="comfort-indoor-note comfort-observation-note" :title="observedTime">
                    {{ temperatureStatus }}
                  </span>
                </ElPopover>
                <span v-else-if="temperatureStatus" class="comfort-status-chip comfort-status-chip-muted comfort-indoor-note" :title="observedTime">
                  {{ temperatureStatus }}
                </span>
                <ElPopover v-else-if="outdoorNotice" trigger="hover" placement="top" :teleported="false" width="auto">
                  <template #reference>
                    <span class="comfort-status-chip" tabindex="0">{{ shortStatusBadge(outdoorNotice) }}</span>
                  </template>
                  <span class="comfort-observation-note comfort-outdoor-note">{{ outdoorNotice }}</span>
                </ElPopover>
              </div>
            </div>

            <div class="comfort-kpi">
              <div class="comfort-kpi-heading">
                <h2>{{ copy.acStatus }}</h2>
                <RouterLink
                  v-if="canOpenHvac" :to="{ path: hvacPath, query: { buildingId } }"
                  class="comfort-ac-link"
                >
                  {{ copy.acControl }} <span class="comfort-ac-link-arrow" aria-hidden="true">{{ copy.acLinkArrow }}</span>
                </RouterLink>
              </div>
              <div class="comfort-kpi-body">
                <div class="comfort-kpi-val-wrap comfort-ac-summary">
                  <strong
                    class="comfort-value"
                    :class="{
                      'comfort-ac-running': acRunning,
                      'comfort-value-muted': !airConditioning?.total || airConditioning?.running == null,
                    }"
                  >{{ acPrimaryLabel }}</strong>
                  <small v-if="acUnitMeta" class="comfort-kpi-unit">{{ copy.dotSeparator }}{{ acUnitMeta }}</small>
                </div>
                <span class="comfort-wind-bars" :title="`${copy.fan} ${acFan}`" aria-hidden="true">
                  <i class="wind-bar-1" :class="{ on: acWindLevel >= 1 }" />
                  <i class="wind-bar-2" :class="{ on: acWindLevel >= 2 }" />
                  <i class="wind-bar-3" :class="{ on: acWindLevel >= 3 }" />
                </span>
              </div>
              <div class="comfort-kpi-sub-row">
                <span class="comfort-kpi-sub comfort-ac-settings">
                  <span>{{ copy.setpoint }} {{ acSetpoint }}</span>
                  <span>{{ copy.fan }} {{ acFan }}</span>
                </span>
                <ElPopover v-if="acNotice" trigger="hover" placement="top" :teleported="false" width="auto">
                  <template #reference>
                    <span class="comfort-status-chip" tabindex="0">{{ shortStatusBadge(acNotice) }}</span>
                  </template>
                  <span class="comfort-observation-note comfort-ac-note">{{ acNotice }}</span>
                </ElPopover>
              </div>
            </div>

            <div class="comfort-kpi comfort-carbon">
              <div class="comfort-kpi-heading">
                <h2>{{ copy.carbon }}</h2>
                <ElPopover v-if="powerNotice && (power?.stale || electricity.error.value)" trigger="hover" placement="top" :teleported="false" width="auto">
                  <template #reference>
                    <span class="comfort-status-chip" tabindex="0">{{ shortStatusBadge(powerNotice) }}</span>
                  </template>
                  <span class="comfort-observation-note">{{ powerNotice }}</span>
                </ElPopover>
              </div>
              <div class="comfort-kpi-body">
                <strong
                  class="comfort-value" :class="{ 'comfort-value-muted': !power }"
                  :title="copy.indoorUnitPower" data-test="comfort-power"
                >
                  {{ power ? formatNumber(power.watts, { maximumFractionDigits: 1 }) : missing }}<small v-if="power">{{ ` ${copy.powerUnit}` }}</small>
                </strong>
                <span
                  v-if="carbonToday != null" class="comfort-mini-pill comfort-mini-pill-ok"
                  :title="copy.carbonTodayFull" data-test="comfort-carbon-today"
                >
                  <span class="comfort-pill-label">{{ copy.carbonToday }}</span>
                  {{ formatNumber(carbonToday, { minimumFractionDigits: 2, maximumFractionDigits: 2 }) }} {{ copy.carbonUnitShort }}
                </span>
                <span v-else-if="roomElectricity?.carbonStatus === 'NO_FACTOR'" class="comfort-mini-pill">{{ copy.factorPendingBadge }}</span>
                <span v-else-if="!power" class="comfort-mini-pill">{{ copy.powerPendingBadge }}</span>
              </div>
              <span
                v-if="carbonRate != null" class="comfort-kpi-sub comfort-sub-ok"
                :title="copy.carbonRate" data-test="comfort-carbon-rate"
              >
                {{ formatNumber(carbonRate, { minimumFractionDigits: 3, maximumFractionDigits: 3 }) }} {{ copy.carbonRateUnit }}
              </span>
              <span v-else class="comfort-kpi-sub" :title="powerNotice || carbonEmpty">
                {{ powerNotice || carbonEmpty }}
              </span>
            </div>
          </div>

          <div class="comfort-parameters">
            <div
              v-for="field in copy.parameterFields" :key="field.key" class="comfort-param"
              :title="field.key === 'airTemperature' ? copy.pmvMeasuredTaTitle : `${field.full} (${field.range} ${field.unit})`"
            >
              <span class="comfort-param-label">{{ field.label }}</span>
              <span class="comfort-param-val">
                <strong>{{ paramDisplayValue(field) }}</strong>
                <span v-if="paramDisplayValue(field) !== missing" class="comfort-param-unit">{{ field.shortUnit }}</span>
              </span>
            </div>
            <button type="button" class="comfort-param-action" :disabled="!roomId" @click="paramsOpen = true">
              <span>{{ canEditParameters ? copy.editParams : copy.viewParams }}</span>
              <span aria-hidden="true">{{ copy.paramActionArrow }}</span>
            </button>
          </div>
        </section>

        <div class="comfort-middle">
          <ComfortPanel :heading="copy.trend" class="comfort-analysis">
            <template #actions>
              <ElRadioGroup v-model="trendTab" class="comfort-trend-tabs" size="small" :aria-label="copy.trend">
                <ElRadioButton value="temperature">{{ copy.temperatureTab }}</ElRadioButton>
                <ElRadioButton value="pmv">{{ copy.pmvTab }}</ElRadioButton>
                <ElRadioButton value="carbon">{{ copy.carbonTab }}</ElRadioButton>
              </ElRadioGroup>
            </template>
            <template v-if="trendTab === 'temperature'">
              <ElAlert v-if="trends.error.value" :title="trends.error.value" :closable="false" type="warning" />
              <div v-if="chartHasData || configuredBand || chartLoading" class="comfort-chart" :title="chartDescription">
                <ChartView :option="chart" :loading="chartLoading" :accessible-label="chartDescription" />
              </div>
              <div v-else class="comfort-empty" :title="chartDescription">
                <span class="comfort-empty-badge">{{ copy.noTrend }}</span>
              </div>
            </template>
            <template v-else-if="trendTab === 'pmv'">
              <ElAlert v-if="trends.error.value" :title="trends.error.value" :closable="false" type="warning" />
              <div v-if="pmvHasData || trends.loading.value" class="comfort-chart">
                <ChartView :option="pmvChart" :loading="trends.loading.value" :accessible-label="copy.pmvChartLabel" />
              </div>
              <div v-else class="comfort-empty">
                <span class="comfort-empty-badge">{{ copy.noPmvHistory }}</span>
              </div>
            </template>
            <template v-else>
              <ElAlert v-if="electricity.error.value" :title="electricity.error.value" :closable="false" type="warning" />
              <div v-if="carbonHasData || electricity.loading.value" class="comfort-chart">
                <ChartView :option="carbonChart" :loading="electricity.loading.value" :accessible-label="copy.actualCarbon" />
              </div>
              <div v-else class="comfort-empty">
                <span class="comfort-empty-badge">{{ carbonEmpty }}</span>
              </div>
            </template>
          </ComfortPanel>
          <ComfortPanel :heading="copy.roomScene" class="comfort-room-panel">
            <template #status>
              <span class="comfort-meta comfort-scene-status" :title="sceneStatus">{{ sceneStatus }}</span>
            </template>
            <template #actions>
              <ElButton link size="small" class="comfort-zoom-btn" @click="sceneOpen = true">{{ copy.zoom }}</ElButton>
            </template>
            <div class="comfort-scene">
              <div v-if="sceneLabel" class="comfort-scene-badge" :title="sceneLabel">{{ sceneLabel }}</div>
              <ComfortRoomScene
                :label="sceneLabel" :status="sceneStatus" :occupancy="currentCount" :door="sceneDoor"
                :ac-running="acRunning" :airflow-speed="airConditioning?.airflowSpeed ?? null" :page-visible="pageVisible"
              />
              <ul class="comfort-legend room-legend">
                <li v-for="label in copy.roomLegend" :key="label">{{ label }}</li>
              </ul>
            </div>
          </ComfortPanel>
        </div>

        <ComfortPanel :heading="copy.mpc">
          <template #status>
            <span class="comfort-meta">{{ copy.mpcRolling }}</span>
          </template>
          <template #actions>
            <ul class="comfort-legend mpc-legend">
              <li v-for="label in copy.mpcStates" :key="label">{{ label }}</li>
            </ul>
          </template>
          <div class="comfort-empty mpc-empty">
            <span class="comfort-empty-badge">{{ copy.mpcBadge }}</span>
            <strong>{{ copy.mpcPending }}</strong>
          </div>
        </ComfortPanel>
      </div>

      <ComfortDoorPanels
        class="comfort-side comfort-door-column" :stats="doorStats.data.value" :space-id="roomId"
        :state="doorState" :notice="doorNotice" :message="contactMessage"
        @select-door="selectDoor"
      />
    </div>

    <ElDrawer
      v-model="paramsOpen" :title="copy.paramsTitle"
      :size="'min(94%, var(--bec-ref-comfort-drawer-width))'" destroy-on-close
    >
      <template #header>
        <div class="comfort-drawer-header">
          <span class="comfort-drawer-title">{{ copy.paramsTitle }}</span>
          <span v-if="currentRoomSubtitle" class="comfort-drawer-subtitle">{{ currentRoomSubtitle }}</span>
        </div>
      </template>
      <div class="comfort-drawer-body">
        <ElAlert
          v-if="parameterError || parameters.error.value" :title="parameterError || parameters.error.value"
          type="warning" :closable="false"
        />
        <ElAlert v-else-if="parameterAdjustment" :title="parameterAdjustment" type="info" :closable="false" />
        <section class="comfort-drawer-section" :aria-label="copy.temperatureBand">
          <h3>{{ copy.temperatureBand }}</h3>
          <div class="comfort-drawer-fields comfort-band-fields">
            <div class="comfort-drawer-field">
              <label for="comfort-t-low">{{ copy.temperatureLow }}</label>
              <ElInput
                id="comfort-t-low" v-model="tLow" type="number" step="any" :placeholder="missing"
                :disabled="!canEditParameters || parametersBusy"
              >
                <template #suffix>{{ copy.celsius }}</template>
              </ElInput>
            </div>
            <div class="comfort-drawer-field">
              <label for="comfort-t-high">{{ copy.temperatureHigh }}</label>
              <ElInput
                id="comfort-t-high" v-model="tHigh" type="number" step="any" :placeholder="missing"
                :disabled="!canEditParameters || parametersBusy"
              >
                <template #suffix>{{ copy.celsius }}</template>
              </ElInput>
            </div>
          </div>
        </section>
        <section class="comfort-drawer-section" :aria-label="copy.manualParameters">
          <h3>{{ copy.manualParameters }}</h3>
          <div class="comfort-drawer-fields">
            <div v-for="field in copy.parameterFields" :key="field.key" class="comfort-drawer-field">
              <label :for="'comfort-param-' + field.key">
                <span>{{ field.full }}</span>
                <small>{{ field.range }}</small>
              </label>
              <ElInput
                :id="'comfort-param-' + field.key" v-model="manualDraft[field.key]" :placeholder="missing"
                :inputmode="field.digits ? 'decimal' : 'numeric'" :disabled="!canEditParameters || parametersBusy"
                @blur="clampInput(field)"
              >
                <template #suffix>{{ field.unit }}</template>
              </ElInput>
            </div>
          </div>
        </section>
      </div>
      <template #footer>
        <div class="comfort-drawer-footer">
          <ElButton v-if="canEditParameters" :disabled="parameterSaving" @click="resetParameters">{{ copy.revertParams }}</ElButton>
          <div class="comfort-drawer-footer-actions">
            <ElButton @click="paramsOpen = false">{{ copy.cancel }}</ElButton>
            <ElButton
              v-if="canEditParameters" type="primary" :loading="parameterSaving" :disabled="parameters.loading.value"
              @click="applyParameters"
            >
              {{ copy.saveParams }}
            </ElButton>
          </div>
        </div>
      </template>
    </ElDrawer>

    <ElDialog
      v-model="sceneOpen" :title="currentRoomSubtitle ? `${currentRoomSubtitle}${copy.dotSeparator}${copy.roomSceneExpanded}` : copy.roomSceneExpanded"
      :width="'min(92%, var(--bec-ref-comfort-dialog-width))'" destroy-on-close
    >
      <div class="comfort-scene comfort-expanded-scene">
        <div v-if="sceneLabel" class="comfort-scene-badge" :title="sceneLabel">{{ sceneLabel }}</div>
        <ComfortRoomScene
          :label="sceneLabel" :status="sceneStatus" :occupancy="currentCount" :door="sceneDoor"
          :ac-running="acRunning" :airflow-speed="airConditioning?.airflowSpeed ?? null" :page-visible="pageVisible"
        />
        <ul class="comfort-legend room-legend">
          <li v-for="label in copy.roomLegend" :key="label">{{ label }}</li>
        </ul>
      </div>
    </ElDialog>
  </article>
</template>

<style scoped>
.comfort-page {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  gap: var(--bec-comfort-gap);
  height: 100%;
  min-height: 0;
  min-width: 0;
  --bec-font-family-body: Arial, 'Microsoft YaHei', 'PingFang SC', sans-serif;
  --bec-font-family-number: DINPro, 'Bahnschrift', Arial, 'Microsoft YaHei', sans-serif;
  --bec-comfort-small: var(--bec-ref-comfort-font-small);
  --bec-comfort-detail: var(--bec-ref-comfort-font-sub);
  --bec-comfort-native-leading: normal;
  --bec-line-height: var(--bec-ref-comfort-line-body);
  --bec-comfort-gap: var(--bec-ref-comfort-gap);
  --bec-comfort-card-padding: var(--bec-ref-comfort-card-padding);
  font-family: var(--bec-font-family-body);
  font-size: var(--bec-ref-comfort-font-body);
  font-weight: var(--bec-font-weight-normal);
  line-height: var(--bec-line-height);
  color: var(--bec-color-text-primary);
}
.comfort-topbar, .comfort-core {
  min-width: 0;
  background: var(--bec-color-surface);
  border: var(--bec-border-width) solid var(--bec-color-border);
  border-radius: var(--bec-management-radius);
  box-shadow: var(--bec-shadow-card);
}
.comfort-topbar { position: relative; z-index: var(--bec-layer-navigation); }
.comfort-toolbar, .comfort-alarm-strip, .comfort-navigation, .comfort-toolbar-actions, .comfort-connectivity, .comfort-filter {
  display: flex;
  align-items: center;
  gap: var(--bec-space-tight);
}
.comfort-navigation { gap: var(--bec-ref-space-10); min-width: 0; }
.comfort-toolbar-actions { gap: var(--bec-ref-space-8); flex-shrink: 0; }
.comfort-toolbar {
  justify-content: space-between;
  padding: var(--bec-ref-comfort-toolbar-padding) var(--bec-comfort-card-padding);
  gap: var(--bec-ref-space-10);
  min-width: 0;
}
.comfort-toolbar h1 {
  margin: 0;
  color: var(--bec-color-text-primary);
  font-size: var(--bec-ref-comfort-font-title);
  font-weight: var(--bec-font-weight-heading);
  white-space: nowrap;
}
.comfort-alarm-strip {
  justify-content: space-between;
  gap: var(--bec-ref-space-10);
  padding: var(--bec-ref-comfort-alarm-padding) var(--bec-comfort-card-padding);
  background: var(--bec-color-surface-secondary);
  border-top: var(--bec-border-width) solid var(--bec-color-divider);
  border-bottom-left-radius: var(--bec-management-radius);
  border-bottom-right-radius: var(--bec-management-radius);
  min-width: 0;
}
.comfort-alarm-title {
  flex: none;
  padding-right: var(--bec-ref-space-8);
  border-right: var(--bec-border-width) solid var(--bec-color-divider);
  color: var(--bec-color-text-secondary);
  font-size: var(--bec-comfort-small);
  font-weight: var(--bec-font-weight-heading);
  white-space: nowrap;
}
.comfort-filter { gap: var(--bec-ref-space-6); min-width: 0; }
.comfort-filter-building :deep(.el-select) { width: var(--bec-ref-comfort-building-select-width); }
.comfort-filter-room :deep(.el-select) { width: var(--bec-ref-comfort-room-select-width); }
.comfort-filter > span { color: var(--bec-color-text-secondary); font-size: var(--bec-comfort-small); white-space: nowrap; }
.comfort-filter :deep(.el-select__wrapper) { min-height: var(--bec-ref-comfort-control-height); }
.comfort-filter :deep(.el-select__placeholder), .comfort-filter :deep(.el-select__input) {
  font-family: var(--bec-font-family-body);
  font-size: var(--bec-ref-comfort-font-body);
  font-weight: var(--bec-font-weight-normal);
  line-height: var(--bec-comfort-native-leading);
}
.comfort-filter-room :deep(.el-select__placeholder) {
  color: var(--bec-color-text-primary);
  font-family: var(--bec-font-family-number);
  font-weight: var(--bec-font-weight-heading);
}
.comfort-select-dot {
  display: inline-block;
  width: var(--bec-ref-space-7);
  height: var(--bec-ref-space-7);
  border-radius: var(--bec-radius-tag);
  background: var(--bec-color-text-disabled);
}
.comfort-select-dot[data-state="open"] { background: var(--bec-color-warning); }
.comfort-select-dot[data-state="closed"] { background: var(--bec-color-success); }
.comfort-room-option { display: flex; align-items: center; justify-content: space-between; gap: var(--bec-space-tight); }
.comfort-room-option > span:first-child { font-size: calc((var(--bec-ref-font-12) + var(--bec-ref-font-14)) / 2); }
.comfort-room-badges { display: inline-flex; gap: var(--bec-space-tight); align-items: center; }
.comfort-room-door { color: var(--bec-color-text-secondary); }
.comfort-room-door::before {
  display: inline-block;
  width: var(--bec-ref-space-6);
  height: var(--bec-ref-space-6);
  margin-inline-end: var(--bec-ref-space-3);
  border-radius: var(--bec-radius-tag);
  background: currentColor;
  content: '';
}
.comfort-room-door[data-state="open"],
.comfort-room-alarm[data-severity="IMPORTANT"],
.comfort-room-alarm[data-severity="WARNING"] { color: var(--bec-color-warning); }
.comfort-room-door[data-state="closed"] { color: var(--bec-color-success); }
.comfort-room-alarm[data-severity="URGENT"] { color: var(--bec-color-error); }
.comfort-updated-at {
  font-family: var(--bec-font-family-number);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}
.comfort-toolbar-actions :deep(.el-button) {
  margin-left: 0;
  height: var(--bec-ref-comfort-control-height);
  padding-inline: var(--bec-ref-space-10);
}
.comfort-page :deep(.el-button) {
  font-family: var(--bec-font-family-body);
  font-size: var(--bec-comfort-small);
  font-weight: var(--bec-font-weight-heading);
  line-height: var(--bec-comfort-native-leading);
}
.comfort-page :deep(.el-button.is-link) { font-size: var(--bec-ref-font-11); }
.comfort-alarm-content {
  display: flex;
  flex: 1;
  align-items: center;
  gap: var(--bec-ref-space-8);
  min-width: 0;
  overflow: hidden;
}
.comfort-alarm-link {
  flex: none;
  color: var(--bec-color-brand-primary);
  font-size: var(--bec-ref-font-11);
  font-weight: var(--bec-font-weight-heading);
  text-decoration: none;
  white-space: nowrap;
}
.comfort-alarm-link:hover { text-decoration: underline; }
.comfort-connectivity {
  gap: var(--bec-ref-space-10);
  padding: var(--bec-ref-space-3) var(--bec-ref-space-10);
  color: var(--bec-color-text-secondary);
  background: var(--bec-color-surface-secondary);
  border: var(--bec-border-width) solid var(--bec-color-divider);
  border-radius: var(--bec-radius-control);
  font-size: var(--bec-comfort-small);
}
.comfort-connectivity span { display: inline-flex; align-items: center; gap: var(--bec-ref-space-4); white-space: nowrap; }
.comfort-connectivity span::before {
  content: '';
  flex: none;
  width: var(--bec-ref-space-7);
  height: var(--bec-ref-space-7);
  border-radius: var(--bec-radius-tag);
  background: var(--bec-color-text-disabled);
}
.comfort-connectivity .sensor-online { color: var(--bec-color-text-primary); font-weight: var(--bec-font-weight-heading); }
.comfort-connectivity .sensor-online::before { background: var(--bec-color-success); }
.comfort-connectivity .sensor-offline { color: var(--bec-color-error); font-weight: var(--bec-font-weight-heading); }
.comfort-connectivity .sensor-offline::before { background: var(--bec-color-error); }
.comfort-connectivity .sensor-delayed { color: var(--bec-color-warning); font-weight: var(--bec-font-weight-heading); }
.comfort-connectivity .sensor-delayed::before { background: var(--bec-color-warning); }
.comfort-status-chip {
  flex: none;
  display: inline-flex;
  align-items: center;
  padding: 0 var(--bec-ref-space-5);
  border: var(--bec-border-width) solid color-mix(in srgb, var(--bec-color-warning) 35%, transparent);
  border-radius: var(--bec-radius-control);
  background: var(--bec-ref-comfort-alarm-warn-bg);
  color: var(--bec-color-warning);
  font-size: var(--bec-ref-comfort-font-micro);
  font-weight: var(--bec-font-weight-heading);
  line-height: var(--bec-ref-comfort-line-body);
  cursor: help;
  white-space: nowrap;
}
.comfort-status-chip-muted {
  border-color: var(--bec-color-divider);
  background: var(--bec-color-surface);
  color: var(--bec-color-text-secondary);
}
.comfort-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1.66fr) minmax(0, 1.06fr);
  gap: var(--bec-comfort-gap);
  min-height: 0;
}
.comfort-side, .comfort-center, .comfort-middle {
  display: grid;
  gap: var(--bec-comfort-gap);
  min-height: 0;
  min-width: 0;
}
.comfort-side { grid-template-rows: repeat(2, minmax(0, 1fr)); }
.comfort-center { grid-template-rows: auto minmax(0, .92fr) minmax(0, 1.08fr); }
.comfort-middle { grid-template-columns: minmax(0, 1.18fr) minmax(0, 1fr); }
.comfort-core {
  display: grid;
  gap: var(--bec-ref-comfort-core-gap);
  padding: var(--bec-ref-comfort-core-padding) var(--bec-comfort-card-padding);
}
.comfort-kpis, .comfort-parameters { display: grid; }
.comfort-kpis { grid-template-columns: repeat(4, minmax(0, 1fr)); gap: var(--bec-ref-comfort-kpi-gap); }
.comfort-parameters {
  grid-template-columns: repeat(7, minmax(0, 1fr)) auto;
  gap: var(--bec-ref-comfort-parameters-gap);
  align-items: stretch;
  padding-top: var(--bec-ref-space-5);
  border-top: var(--bec-border-width) solid var(--bec-color-divider);
}
.comfort-kpi {
  display: grid;
  align-content: space-between;
  gap: var(--bec-ref-space-3);
  min-width: 0;
  padding: var(--bec-ref-comfort-kpi-padding);
  background: var(--bec-color-surface-secondary);
  border: var(--bec-border-width) solid var(--bec-color-divider);
  border-radius: var(--bec-ref-radius-6);
}
.comfort-kpi h2 {
  margin: 0;
  color: var(--bec-color-text-secondary);
  font-size: var(--bec-comfort-small);
  font-weight: var(--bec-font-weight-normal);
  white-space: nowrap;
}
.comfort-kpi-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--bec-ref-space-4);
  min-width: 0;
}
.comfort-kpi-body {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--bec-ref-space-6);
  min-width: 0;
}
.comfort-kpi-val-wrap {
  display: flex;
  align-items: baseline;
  gap: var(--bec-ref-space-3);
  min-width: 0;
  white-space: nowrap;
}
.comfort-value {
  color: var(--bec-color-text-primary);
  font-family: var(--bec-font-family-number);
  font-size: var(--bec-ref-comfort-font-kpi);
  font-weight: var(--bec-font-weight-heading);
  line-height: var(--bec-ref-comfort-line-snug);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}
.comfort-value small, .comfort-kpi-unit {
  color: var(--bec-color-text-secondary);
  font-family: var(--bec-font-family-body);
  font-size: var(--bec-comfort-small);
  font-weight: var(--bec-font-weight-normal);
  white-space: nowrap;
}
.comfort-value-muted { color: var(--bec-color-text-disabled); }
.comfort-kpi-anchor {
  flex: none;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: var(--bec-ref-space-2);
}
.comfort-pmv-track {
  position: relative;
  width: var(--bec-ref-comfort-pmv-track-width);
  height: var(--bec-ref-comfort-pmv-track-height);
  border-radius: var(--bec-radius-tag);
  background: var(--bec-ref-comfort-track-bg);
  overflow: hidden;
}
.comfort-pmv-band {
  position: absolute;
  left: 25%;
  width: 50%;
  top: 0;
  bottom: 0;
  background: color-mix(in srgb, var(--bec-color-success) 32%, transparent);
}
.comfort-pmv-dot {
  position: absolute;
  top: 0;
  width: var(--bec-ref-comfort-pmv-track-height);
  height: var(--bec-ref-comfort-pmv-track-height);
  border-radius: var(--bec-radius-tag);
  background: var(--bec-color-brand-primary);
  transform: translateX(-50%);
}
.comfort-pmv-dot-alert { background: var(--bec-color-error); }
.comfort-pmv-range {
  color: var(--bec-color-text-secondary);
  font-family: var(--bec-font-family-number);
  font-size: var(--bec-ref-comfort-font-micro);
  line-height: var(--bec-ref-comfort-line-tight);
}
.comfort-wind-bars {
  flex: none;
  display: inline-flex;
  align-items: flex-end;
  gap: var(--bec-ref-space-2);
  height: var(--bec-ref-comfort-wind-bar-3);
}
.comfort-wind-bars i {
  display: inline-block;
  width: var(--bec-ref-comfort-wind-bar-width);
  border-radius: var(--bec-ref-radius-2);
  background: var(--bec-ref-comfort-wind-off);
}
.comfort-wind-bars .wind-bar-1 { height: var(--bec-ref-comfort-wind-bar-1); }
.comfort-wind-bars .wind-bar-2 { height: var(--bec-ref-comfort-wind-bar-2); }
.comfort-wind-bars .wind-bar-3 { height: var(--bec-ref-comfort-wind-bar-3); }
.comfort-wind-bars i.on { background: var(--bec-color-brand-primary); }
.comfort-mini-pill {
  flex-shrink: 1;
  min-width: 0;
  padding: var(--bec-border-width) var(--bec-ref-space-6);
  border: var(--bec-border-width) solid var(--bec-color-divider);
  border-radius: var(--bec-radius-control);
  background: var(--bec-color-surface);
  color: var(--bec-color-text-secondary);
  font-family: var(--bec-font-family-number);
  font-size: var(--bec-ref-font-10);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.comfort-mini-pill-ok { color: var(--bec-color-success); }
.comfort-band-badge {
  min-width: 0;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--bec-color-text-secondary);
  font-family: var(--bec-font-family-number);
  font-size: var(--bec-comfort-small);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.comfort-kpi-sub-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--bec-ref-space-4);
  min-width: 0;
}
.comfort-kpi-sub {
  min-width: 0;
  color: var(--bec-color-text-secondary);
  font-size: var(--bec-comfort-detail);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.comfort-sub-ok { color: var(--bec-color-success); }
.comfort-sub-danger { color: var(--bec-color-error); }
.comfort-meta, .comfort-legend, .comfort-badge {
  color: var(--bec-color-text-secondary);
  font-size: var(--bec-comfort-small);
}
.comfort-badge {
  padding-inline: var(--bec-ref-space-4);
  background: var(--bec-color-surface-secondary);
  border: var(--bec-border-width) solid var(--bec-color-divider);
  border-radius: var(--bec-radius-tag);
  white-space: nowrap;
}
.comfort-environment .comfort-outdoor-delayed,
.comfort-environment .comfort-indoor-delayed,
.comfort-observation-note { color: var(--bec-color-warning); font-size: var(--bec-comfort-small); }
.comfort-ac-running { color: var(--bec-color-success); }
.comfort-pmv-alert { color: var(--bec-color-error); }
.comfort-ac-settings { display: inline-flex; align-items: center; gap: var(--bec-ref-space-6); }
.comfort-ac-settings > span { white-space: nowrap; }
.comfort-ac-link {
  flex: none;
  color: var(--bec-color-brand-primary);
  font-size: var(--bec-ref-font-11);
  font-weight: var(--bec-font-weight-heading);
  text-decoration: none;
  white-space: nowrap;
}
.comfort-ac-link:hover { text-decoration: underline; }
.comfort-people-now { white-space: nowrap; }
.comfort-param {
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: var(--bec-border-width);
  min-width: 0;
  overflow: hidden;
  padding: var(--bec-ref-space-3) var(--bec-ref-space-6);
  background: var(--bec-color-surface-secondary);
  border: var(--bec-border-width) solid var(--bec-color-divider);
  border-radius: var(--bec-radius-control);
}
.comfort-param-label {
  color: var(--bec-color-text-secondary);
  font-size: var(--bec-ref-comfort-font-micro);
  line-height: var(--bec-ref-comfort-line-tight);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.comfort-param-val {
  display: inline-flex;
  align-items: baseline;
  gap: var(--bec-ref-space-2);
  min-width: 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.comfort-param strong {
  color: var(--bec-color-text-primary);
  font-family: var(--bec-font-family-number);
  font-size: var(--bec-ref-font-11);
  font-weight: var(--bec-font-weight-heading);
  line-height: var(--bec-ref-comfort-line-snug);
  font-variant-numeric: tabular-nums;
}
.comfort-param-unit {
  color: var(--bec-color-text-secondary);
  font-family: var(--bec-font-family-body);
  font-size: var(--bec-ref-comfort-font-micro);
  font-weight: var(--bec-font-weight-normal);
}
.comfort-param-action {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: var(--bec-ref-space-3);
  padding: var(--bec-ref-space-3) var(--bec-ref-space-8);
  border: var(--bec-border-width) solid color-mix(in srgb, var(--bec-color-brand-primary) 26%, transparent);
  border-radius: var(--bec-radius-control);
  background: var(--bec-color-action-soft);
  color: var(--bec-color-brand-primary);
  font-family: var(--bec-font-family-body);
  font-size: var(--bec-ref-font-11);
  font-weight: var(--bec-font-weight-heading);
  white-space: nowrap;
  cursor: pointer;
}
.comfort-param-action:hover:not(:disabled) {
  background: var(--bec-color-action-primary);
  border-color: var(--bec-color-action-primary);
  color: var(--bec-color-on-action);
}
.comfort-param-action:disabled { opacity: .5; cursor: not-allowed; }
.comfort-empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--bec-ref-space-6);
  min-height: 0;
  padding: var(--bec-ref-space-8) var(--bec-ref-space-12);
  border: var(--bec-border-width) dashed var(--bec-color-border);
  border-radius: var(--bec-ref-radius-6);
  background: var(--bec-color-surface-secondary);
  color: var(--bec-color-text-secondary);
  text-align: center;
}
.comfort-empty-badge {
  display: inline-flex;
  align-items: center;
  padding: var(--bec-ref-space-2) var(--bec-ref-space-8);
  border: var(--bec-border-width) solid var(--bec-color-border);
  border-radius: var(--bec-radius-tag);
  background: var(--bec-color-surface);
  color: var(--bec-color-text-secondary);
  font-size: var(--bec-ref-comfort-font-sub);
  font-weight: var(--bec-font-weight-heading);
}
.comfort-empty strong {
  color: var(--bec-color-text-primary);
  font-size: var(--bec-ref-comfort-font-body);
  font-weight: var(--bec-font-weight-heading);
}
.comfort-analysis {
  --bec-chart-series-1: var(--bec-color-brand-primary);
  --bec-chart-series-2: var(--bec-color-warning);
  --bec-chart-font-size: var(--bec-ref-font-10);
}
.comfort-chart, .comfort-scene { flex: 1; min-height: 0; }
.comfort-chart { --bec-font-family-body: Arial, 'Microsoft YaHei', 'PingFang SC', sans-serif; }
.comfort-people-column .comfort-chart { --bec-chart-font-size: var(--bec-ref-font-10); }
.comfort-scene {
  position: relative;
  display: grid;
  place-items: center;
  overflow: hidden;
  background: var(--bec-ref-comfort-scene-background);
  border: var(--bec-border-width) solid var(--bec-color-divider);
  border-radius: var(--bec-ref-radius-6);
}
.comfort-scene-badge {
  position: absolute;
  top: var(--bec-ref-space-8);
  left: var(--bec-ref-space-8);
  max-width: calc(100% - var(--bec-ref-space-16));
  padding: var(--bec-ref-space-2) var(--bec-ref-space-8);
  border: var(--bec-border-width) solid var(--bec-color-border);
  border-radius: var(--bec-radius-control);
  background: var(--bec-color-surface);
  color: var(--bec-color-text-primary);
  font-family: var(--bec-font-family-number);
  font-size: var(--bec-ref-font-11);
  font-weight: var(--bec-font-weight-heading);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  pointer-events: none;
}
.comfort-scene-status { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.comfort-room-panel :deep(.comfort-panel-title h2) { flex-shrink: 0; }
.comfort-zoom-btn {
  height: auto;
  flex: none;
  padding: 0;
  color: var(--bec-color-brand-primary);
  font-weight: var(--bec-font-weight-heading);
  white-space: nowrap;
}
.comfort-grid :deep(.el-radio-group) {
  flex: none;
  display: inline-flex;
  padding: var(--bec-ref-space-2);
  border: var(--bec-border-width) solid var(--bec-color-divider);
  border-radius: var(--bec-ref-radius-6);
  background-color: var(--bec-color-surface-secondary);
}
.comfort-grid :deep(.el-radio-button__inner) {
  padding: var(--bec-ref-space-2) var(--bec-ref-space-8);
  border: 0;
  border-radius: var(--bec-radius-control);
  font-family: var(--bec-font-family-body);
  font-size: var(--bec-comfort-small);
  font-weight: var(--bec-font-weight-normal);
  line-height: var(--bec-comfort-native-leading);
  background-color: transparent;
  color: var(--bec-color-text-secondary);
}
.comfort-grid :deep(.el-radio-button.is-active .el-radio-button__inner) { font-weight: var(--bec-font-weight-heading); }
.comfort-grid :deep(.el-radio-button.is-active .el-radio-button__original-radio:not(:disabled) + .el-radio-button__inner) {
  background-color: var(--bec-color-surface);
  border-color: transparent;
  /* 首项默认强制取消阴影，所有分段切换胶囊的选中浮起样式统一由这里控制。 */
  box-shadow: var(--bec-ref-comfort-tab-shadow) !important;
  color: var(--bec-color-brand-primary);
}
.comfort-legend {
  display: flex;
  flex-wrap: nowrap;
  align-items: center;
  gap: var(--bec-ref-space-10);
  margin: 0;
  padding: 0;
  list-style: none;
  white-space: nowrap;
}
.comfort-legend.room-legend {
  position: absolute;
  bottom: var(--bec-ref-space-6);
  left: var(--bec-ref-space-8);
  gap: var(--bec-ref-space-8);
  padding: var(--bec-ref-space-3) var(--bec-ref-space-8);
  border: var(--bec-border-width) solid var(--bec-color-divider);
  border-radius: var(--bec-radius-tag);
  background: var(--bec-ref-comfort-legend-background);
  font-size: var(--bec-comfort-detail);
  pointer-events: none;
}
.mpc-legend { font-size: var(--bec-ref-font-11); }
.comfort-legend li { display: inline-flex; align-items: center; gap: var(--bec-ref-space-4); }
.comfort-legend li::before {
  content: '';
  flex: none;
  width: var(--bec-ref-space-7);
  height: var(--bec-ref-space-7);
  border-radius: var(--bec-radius-tag);
  background: var(--bec-color-brand-primary);
}
.room-legend li { gap: var(--bec-ref-space-3); }
.comfort-legend li:nth-child(2)::before { background: var(--bec-color-warning); }
.room-legend li:nth-child(3)::before { background: var(--bec-color-success); }
.mpc-legend li:nth-child(3)::before { background: var(--bec-ref-comfort-wind-off); }
.comfort-page :deep(.el-drawer__title), .comfort-page :deep(.el-dialog__title) {
  font-family: var(--bec-font-family-body);
  font-size: var(--bec-ref-font-16);
  font-weight: var(--bec-font-weight-heading);
  line-height: var(--bec-line-height);
}
.comfort-drawer-header { display: grid; gap: var(--bec-ref-space-2); }
.comfort-drawer-title {
  color: var(--bec-color-text-primary);
  font-size: var(--bec-ref-font-16);
  font-weight: var(--bec-font-weight-heading);
}
.comfort-drawer-subtitle {
  color: var(--bec-color-text-secondary);
  font-family: var(--bec-font-family-number);
  font-size: var(--bec-ref-font-12);
}
.comfort-drawer-body { display: grid; gap: var(--bec-ref-space-16); align-content: start; }
.comfort-drawer-section { display: grid; gap: var(--bec-ref-space-10); }
.comfort-drawer-section + .comfort-drawer-section {
  padding-top: var(--bec-ref-space-14);
  border-top: var(--bec-border-width) solid var(--bec-color-divider);
}
.comfort-drawer-section h3 {
  margin: 0;
  color: var(--bec-color-text-primary);
  font-size: var(--bec-ref-font-14);
  font-weight: var(--bec-font-weight-heading);
}
.comfort-drawer-fields { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: var(--bec-ref-space-12); }
.comfort-drawer-field { display: grid; gap: var(--bec-ref-space-4); }
.comfort-drawer-field:last-child { grid-column: 1 / -1; }
.comfort-band-fields .comfort-drawer-field:last-child { grid-column: auto; }
.comfort-drawer-field label {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--bec-ref-space-4);
  color: var(--bec-color-text-primary);
  font-size: var(--bec-ref-font-12);
  font-weight: var(--bec-font-weight-heading);
}
.comfort-drawer-field small {
  color: var(--bec-color-text-secondary);
  font-family: var(--bec-font-family-number);
  font-size: var(--bec-ref-font-11);
  font-weight: var(--bec-font-weight-normal);
}
.comfort-drawer-field :deep(.el-input__inner) {
  font-family: var(--bec-font-family-number);
  font-size: calc((var(--bec-ref-font-12) + var(--bec-ref-font-14)) / 2);
  line-height: var(--bec-comfort-native-leading);
}
.comfort-drawer-field :deep(.el-input__suffix) { font-size: var(--bec-ref-font-12); line-height: var(--bec-line-height); }
.comfort-drawer-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--bec-ref-space-8);
  width: 100%;
}
.comfort-drawer-footer-actions {
  display: inline-flex;
  align-items: center;
  gap: var(--bec-ref-space-8);
  margin-left: auto;
}
.comfort-expanded-scene { height: var(--bec-ref-comfort-dialog-stage-height); }
@media (width <= 1560px) {
  .comfort-grid :deep(.el-radio-button__inner) {
    padding-inline: var(--bec-ref-space-6);
    font-size: var(--bec-ref-font-10);
  }
  .comfort-parameters { grid-template-columns: repeat(4, minmax(0, 1fr)); }
  .comfort-param { flex-direction: row; align-items: baseline; justify-content: space-between; gap: var(--bec-ref-space-4); }
}
@media (height <= 790px) {
  .comfort-page {
    --bec-comfort-gap: var(--bec-ref-space-7);
    --bec-comfort-card-padding: var(--bec-ref-space-7);
  }
  .comfort-kpi {
    gap: var(--bec-border-width);
    padding: var(--bec-ref-space-4) var(--bec-ref-space-7);
  }
}
/* 笔记本宽度下按内容量重新分配四格 KPI 宽度；被隐藏的风速文字与“今日”标签仍可通过风速条和悬停提示获取。 */
@media (width <= 1440px) {
  .comfort-kpis {
    grid-template-columns: minmax(0, 1fr) minmax(0, 1.06fr) minmax(0, .9fr) minmax(0, 1.04fr);
    gap: var(--bec-ref-space-6);
  }
  .comfort-kpi { padding-inline: var(--bec-ref-space-5); }
  .comfort-kpi-body { gap: var(--bec-ref-space-4); }
  .comfort-mini-pill { padding-inline: var(--bec-ref-space-4); font-size: var(--bec-ref-comfort-font-micro); }
  .comfort-ac-settings > span + span, .comfort-ac-link-arrow, .comfort-pill-label { display: none; }
}
@media (width < 1280px), (height < 740px) {
  .comfort-filter-building :deep(.el-select),
  .comfort-filter-room :deep(.el-select) { width: calc(var(--bec-ref-space-64) + var(--bec-ref-space-40)); }
  .comfort-center { grid-template-rows: auto minmax(calc(var(--bec-ref-space-64) * 3), .92fr) minmax(0, 1.08fr); }
}
@media (width < 1200px), (height < 680px) {
  .comfort-page { height: auto; grid-template-rows: auto auto; }
  .comfort-toolbar, .comfort-navigation, .comfort-toolbar-actions { flex-wrap: wrap; }
  .comfort-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .comfort-people-column { grid-row: 1; }
  .comfort-door-column { grid-row: 1; }
  .comfort-center { grid-column: 1 / -1; grid-row: 2; grid-template-rows: auto auto auto; }
  .comfort-side :deep(.comfort-panel), .comfort-center > :deep(.comfort-panel) { min-height: calc(var(--bec-ref-space-64) * 2); }
  .comfort-chart, .comfort-scene { min-height: calc(var(--bec-ref-space-64) * 3); }
}
@media (width < 640px) {
  .comfort-grid, .comfort-middle { grid-template-columns: minmax(0, 1fr); }
  .comfort-people-column, .comfort-center, .comfort-door-column { grid-row: auto; }
  .comfort-navigation { width: 100%; }
  .comfort-toolbar h1 { flex-basis: 100%; }
  .comfort-filter { flex: 1 1 calc(var(--bec-ref-space-64) * 2); }
  .comfort-filter-building :deep(.el-select),
  .comfort-filter-room :deep(.el-select) { flex: 1; width: auto; min-width: 0; }
  .comfort-kpis { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
</style>
