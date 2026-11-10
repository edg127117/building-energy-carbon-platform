import { requestApi } from '@/infrastructure/http/public'

export interface MonitoringCategories {
  counts: Record<string, number>
  observed: number
  unknown: number
  unsupported: number
  reasons: Record<string, number>
}

export interface BuildingDeviceSummary {
  buildingId: string
  scope: 'ACTIVE_BOUND_DAIKIN_INDOOR'
  asOf: number
  expiresAt: number
  total: number
  on: number
  off: number
  unknown: number
  unknownReasons: Record<string, number>
  modes: MonitoringCategories
  speeds: MonitoringCategories
}

export interface BuildingRuntimeRank {
  rank: number
  equipmentId: string
  identityId: string
  mappingVersion: number
  equipmentCode: string
  equipmentName: string
  spaceName: string | null
  floorName: string | null
  onMillis: number
  coveredMillis: number
  hours: number
  coveredHours: number
  coveragePercentage: number
  percentage: number
  coverageStatus: 'PARTIAL' | 'COMPLETE'
}

export interface BuildingRuntimeRanking {
  buildingId: string
  scope: 'ACTIVE_BOUND_DAIKIN_INDOOR'
  source: 'PLATFORM_OBSERVED'
  month: string
  statisticsZone: 'Asia/Shanghai'
  asOf: number
  periodStart: number
  periodEnd: number
  elapsedMillis: number
  totalDevices: number
  rankedDevices: number
  excludedReasons: Record<string, number>
  items: BuildingRuntimeRank[]
}

const root = (buildingId: string) => `/v1/hvac-monitoring/buildings/${encodeURIComponent(buildingId)}`
const READ_TIMEOUT_MS = 20_000

/** Reads the backend's short-lived, building-scoped status snapshot; expiry is enforced by the consumer. */
export function readBuildingDeviceSummary(buildingId: string, signal?: AbortSignal) {
  return requestApi<BuildingDeviceSummary>({ method: 'get', url: `${root(buildingId)}/device-summary`, timeout: READ_TIMEOUT_MS, signal })
}

/** Ranking is a device list for the requested Shanghai calendar month, limited by the server to five rows. */
export function readBuildingRuntimeRanking(buildingId: string, month: string, signal?: AbortSignal) {
  return requestApi<BuildingRuntimeRanking>({ method: 'get', url: `${root(buildingId)}/runtime-ranking`, params: { month, limit: 5 }, timeout: READ_TIMEOUT_MS, signal })
}
