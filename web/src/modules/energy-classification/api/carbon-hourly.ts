import { requestApi } from '@/infrastructure/http/public'

export type HourlyCarbonState = 'MISSING' | 'COMPLETE' | 'PARTIAL'
/** MISSING 表示尚未生成小时结果，因此没有来源、因子或来源性质声明。 */
export type HourlyCarbonPointStatus = 'MISSING' | 'COMPLETE' | 'INCOMPLETE' | 'BLOCKED' | 'NOT_DUE'
export type HourlyCarbonDeclaredSourceNature = 'SIMULATED' | 'SOURCE_LABELLED'

export interface HourlyCarbonPoint {
  hour: number
  startInclusive: string
  endExclusive: string
  status: HourlyCarbonPointStatus
  reasonCode: string | null
  reason: string | null
  electricityKwh: string | null
  emissionKgCo2e: string | null
  sourceSnapshotIds: string[]
  factorVersionIds: string[]
  formulaVersionIds: string[]
  gwpVersionIds: string[]
  roundingPolicyVersionIds: string[]
  qualityPolicyVersions: string[]
  projectVersionIds: string[]
  relationVersionIds: string[]
  gasCoverages: string[]
  evidenceHash: string | null
}

/** 后端已裁决的北京时间小时监测视图；它不代表月度正式封账结果。 */
export interface HourlyCarbonView {
  buildingId: string
  date: string
  timezoneId: string
  unit: 'KG_CO2E'
  scope: 'PURCHASED_ELECTRICITY_SCOPE_2'
  sourceKind: string | null
  declaredSourceNature: HourlyCarbonDeclaredSourceNature | null
  factorUsageNature: string | null
  resultNature: 'MONITORING'
  state: HourlyCarbonState
  calculationBatchId: string | null
  revision: number | null
  calculationAsOf: string | null
  activityWatermark: string | null
  activityWatermarkMeaning: string | null
  dueHours: number
  availableHours: number
  dailyTotal: string | null
  points: HourlyCarbonPoint[]
  capturedAt: string | null
  evidenceHash: string | null
}

const encoded = (value: string) => encodeURIComponent(value)

/** 仅声明小时监测读取，不向大屏公开小时计算或参数编辑命令。 */
export const carbonHourlyApi = {
  getHourly(buildingId: string, date: string, signal?: AbortSignal) {
    return requestApi<HourlyCarbonView>({
      method: 'get',
      url: `/v1/carbon-management/buildings/${encoded(buildingId)}/hourly`,
      params: { date },
      signal,
    })
  },
}
