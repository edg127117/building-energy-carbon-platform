import { requestApi, TransportError } from '@/infrastructure/http/public'

export interface ComfortSpace {
  spaceId: string
  spaceName: string
  buildingId: string
  floorName: string | null
  doorOpen?: boolean | null
  doorSummary?: ContactSummary | null
  alarmSeverity?: 'URGENT' | 'IMPORTANT' | 'WARNING' | 'GENERAL' | 'INFO' | null
}

export interface TemperatureSource {
  equipmentId: string
  equipmentName: string
}

export interface TemperatureObservation {
  celsius: number | null
  observedAt: string
  stale: boolean
}

interface RoomTemperature {
  space: ComfortSpace
  selectedSource: TemperatureSource | null
  temperatureSourceStatus: 'SELECTED' | 'UNCONFIGURED' | 'ROOM_AVERAGE'
  pointId: string | null
}

export interface ComfortOverview extends RoomTemperature {
  temperatureSources: TemperatureSource[]
  fieldStatus: string | null
  indoorTemperature: TemperatureObservation | null
  lastValidIndoorTemperature?: TemperatureObservation | null
  pmv?: ComfortPmvMeasurement | null
}

export interface ComfortPmvMeasurement {
  status: 'AVAILABLE' | 'TEMPERATURE_MISSING' | 'TEMPERATURE_STALE' | 'PARAMETERS_MISSING'
    | 'PARAMETERS_UNAVAILABLE' | 'OUTSIDE_MODEL_RANGE'
  value: number | null
  sensation: 'COOL' | 'COMFORTABLE' | 'WARM' | null
  missingInputs: ComfortInputKey[]
}

export interface ComfortTrends extends RoomTemperature {
  date: string
  timeZone: string
  hours: { startsAt: string; indoorTemperature: TemperatureObservation | null }[]
  pmvHours?: PmvHour[]
}

export interface PmvHour {
  startsAt: string
  observation: { sampledAt: string; temperatureObservedAt: string; value: number; indoorCelsius: number;
    inputs: Partial<Record<ComfortInputKey, number>> } | null
}

export interface ComfortCarbonHour {
  startsAt: string
  observedAt: string
  cumulativeKg: number
}

export interface ComfortElectricity {
  date: string
  power: { watts: number; observedAt: string; stale: boolean } | null
  carbonRateKgPerHour: number | null
  carbonTodayKg: number | null
  carbonHours: ComfortCarbonHour[]
  carbonStatus: 'NO_METER' | 'NO_FACTOR' | 'INCOMPLETE' | 'AVAILABLE'
}

export type SensorStatus = 'DISABLED' | 'UNCONFIGURED' | 'AMBIGUOUS' | 'SELECTION_REQUIRED' | 'NO_OBSERVATION' | 'STALE' | 'AVAILABLE'

interface SensorSnapshot {
  date: string
  timeZone: string
  asOf: string
  status: SensorStatus
  communication: { state: string; reason: string; lastContactAt: string | null } | null
  lastObservedAt: string | null
}

export interface CountSlot {
  startsAt: string
  count: number | null
  observedAt: string | null
}

export interface AverageHour {
  startsAt: string
  averageCount: number | null
  /** 当小时参与平均的有效分钟数。 */
  sampleCount: number
}

export interface ComfortOccupancy extends SensorSnapshot {
  currentCount: number | null
  lastObservedCount: number | null
  series1m: CountSlot[]
  series15m: CountSlot[]
  hourlyAvg: AverageHour[]
}

export type DoorVisualState = 'open' | 'closed' | 'unknown'
export type DoorBucket = 'SHORT_LE_15S' | 'MEDIUM_15_60S' | 'LONG_GT_60S'

export interface ContactSummary { open: number; closed: number; unknown: number }

export interface ComfortContact {
  equipmentId: string
  name: string
  kind: 'DOOR' | 'WINDOW' | null
  open: boolean | null
  lastObservedOpen: boolean | null
  lastObservedAt: string | null
}

export interface ComfortDoorStats extends SensorSnapshot {
  doorOpen: boolean | null
  lastObservedOpen: boolean | null
  lastTransitionAt: string | null
  currentDurationSec: number | null
  recentEvents: { observedAt: string; open: boolean; durationSec: number | null }[]
  hourlyRows: { startsAt: string; openCount: number | null; durationSec: number | null; hasLongOpen: boolean | null }[]
  durationBuckets: { bucket: DoorBucket; count: number | null; durationSec: number | null }[]
  selectedDoorEquipmentId?: string | null
  contacts?: ComfortContact[]
  doors?: ContactSummary
  windows?: ContactSummary
}

export type ComfortFanSpeed = 'low' | 'middleLow' | 'middle' | 'middleHigh' | 'high' | 'automatic'
export type ComfortAirflowSpeed = Exclude<ComfortFanSpeed, 'automatic'>

interface ComfortHvacSetting<T> {
  status: 'VALUE' | 'MIXED' | 'PARTIAL' | 'MISSING'
  value: T | null
}

export interface ComfortHvacStatus {
  total: number
  running: number | null
  unknown: number
  status: 'AVAILABLE' | 'PARTIAL' | 'STALE' | 'OFFLINE' | 'UNKNOWN' | 'UNCONFIGURED'
  lastObservedAt: number | null
  setpoint: ComfortHvacSetting<number>
  fanSpeed: ComfortHvacSetting<ComfortFanSpeed>
  airflowSpeed: ComfortAirflowSpeed | null
  controlEntryAvailable: boolean
}

const roomPath = (spaceId: string) => `/v1/comfort/spaces/${encodeURIComponent(spaceId)}`

export interface ComfortTemperatureBand { tLow: number; tHigh: number }
export type ComfortInputKey = 'metabolicRate' | 'clothingInsulation' | 'airVelocity' | 'relativeHumidity'
  | 'airTemperature' | 'radiantTemperature' | 'dynamicEmissionFactor'
export type ComfortManualInputs = Partial<Record<ComfortInputKey, number | null>>
export interface ComfortParameters {
  comfortBand: ComfortTemperatureBand | null
  manualInputs: ComfortManualInputs
  editable: boolean
}
/** 省略字段保留原值，显式 null 清空；温度上下限成对更新。 */
export interface ComfortParameterUpdate {
  tLow?: number | null
  tHigh?: number | null
  manualInputs?: ComfortManualInputs
}

export const comfortApi = {
  spaces: (buildingId: string, signal: AbortSignal) => requestApi<ComfortSpace[]>({
    method: 'GET', url: `/v1/comfort/buildings/${encodeURIComponent(buildingId)}/spaces`, signal,
  }),
  overview: (spaceId: string, signal: AbortSignal) => requestApi<ComfortOverview>({
    method: 'GET', url: `${roomPath(spaceId)}/overview`, signal,
  }),
  trends: (spaceId: string, signal: AbortSignal) => requestApi<ComfortTrends>({
    method: 'GET', url: `${roomPath(spaceId)}/trends`, signal,
  }),
  electricity: (spaceId: string, signal: AbortSignal) => requestApi<ComfortElectricity>({
    method: 'GET', url: `${roomPath(spaceId)}/electricity`, signal,
  }),
  parameters: (spaceId: string, signal: AbortSignal) => requestApi<ComfortParameters>({
    method: 'GET', url: `${roomPath(spaceId)}/parameters`, signal,
  }),
  saveParameters: (spaceId: string, update: ComfortParameterUpdate, key: string) => requestApi<ComfortParameters>({
    method: 'PUT', url: `${roomPath(spaceId)}/parameters`, data: update, headers: { 'Idempotency-Key': key },
  }),
  occupancy: (spaceId: string, signal: AbortSignal) => requestApi<ComfortOccupancy>({
    method: 'GET', url: `${roomPath(spaceId)}/occupancy`, signal,
  }),
  doorStats: (spaceId: string, signal: AbortSignal, equipmentId?: string) => requestApi<ComfortDoorStats>({
    method: 'GET', url: `${roomPath(spaceId)}/door-stats`, signal, ...(equipmentId ? { params: { equipmentId } } : {}),
  }),
  hvacStatus: (spaceId: string, signal: AbortSignal) => requestApi<ComfortHvacStatus>({
    method: 'GET', url: `${roomPath(spaceId)}/hvac-status`, signal,
  }),
}

export function comfortQueryFailure(failure: unknown) {
  if (!(failure instanceof TransportError)) return { denied: false, invalidTarget: false }
  return {
    denied: failure.kind === 'forbidden' || failure.kind === 'unauthorized',
    invalidTarget: failure.status === 404 || failure.status === 409,
  }
}
