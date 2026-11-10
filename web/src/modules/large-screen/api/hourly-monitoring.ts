import { requestApi } from '@/infrastructure/http/public'

export interface HourlyMonitoringPoint {
  hour: number
  startInclusive: string
  endExclusive: string
  actualStart: string | null
  actualEnd: string | null
  electricityKwh: string | null
  staticEmissionTco2e: string | null
  staticFactorKgCo2ePerKwh: string | null
  dynamicEmissionTco2: string | null
  dynamicFactorKgCo2PerKwh: string | null
  electricityStatus: string
  staticStatus: string
  dynamicStatus: string
  approximate: boolean
  staticGasCoverage: string | null
  sourceNatures: string[]
  evidenceId: string | null
  reasonCode: string | null
}

export interface HourlyMonitoringView {
  buildingId: string
  date: string
  timezoneId: string
  asOf: string
  points: HourlyMonitoringPoint[]
}

export function readHourlyMonitoring(buildingId: string, date: string, signal: AbortSignal): Promise<HourlyMonitoringView> {
  return requestApi<HourlyMonitoringView>({
    url: `/v1/carbon-management/buildings/${encodeURIComponent(buildingId)}/hourly-monitoring`,
    method: 'GET', params: { date }, signal, timeout: 5_000,
  })
}
