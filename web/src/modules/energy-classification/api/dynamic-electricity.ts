import { requestApi } from '@/infrastructure/http/public'

export type DynamicElectricityPeriodType = 'YEAR' | 'MONTH'
export type DynamicElectricityPointStatus = 'COMPLETE' | 'ACCUMULATING' | 'MISSING' | 'PARTIAL' | 'NOT_ENDED' | 'STALE'

export interface DynamicElectricityPoint {
  period: string
  startInclusive: string
  endExclusive: string
  /** 累计前缀计算截止点；如有返回，必须位于当前自然槽位内。 */
  computedThrough?: string | null
  status: DynamicElectricityPointStatus
  reasonCode: string | null
  electricityKwh: string | null
  emissionTco2: string | null
  factorKgCo2PerKwh: string | null
  grains: string[]
  resultIds: string[]
  sourceNatures: string[]
}

export interface DynamicElectricityView {
  buildingId: string
  periodType: DynamicElectricityPeriodType
  period: string
  timezoneId: 'Asia/Shanghai'
  emissionUnit: 'tCO2'
  factorUnit: 'kgCO2/kWh'
  resultNature: 'MONITORING'
  points: DynamicElectricityPoint[]
}

/** 只读取已保存的动态电力监测结果；此 GET 不触发因子计算或任何写操作。 */
export function readDynamicElectricity(
  buildingId: string,
  periodType: DynamicElectricityPeriodType,
  period: string,
  signal?: AbortSignal,
) {
  return requestApi<DynamicElectricityView>({
    method: 'get',
    url: `/v1/carbon-management/buildings/${encodeURIComponent(buildingId)}/dynamic-electricity`,
    params: { periodType, period },
    signal,
  })
}
