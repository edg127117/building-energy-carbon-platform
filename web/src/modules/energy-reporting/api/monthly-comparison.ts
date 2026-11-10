import { requestApi } from '@/infrastructure/http/public'

export type MonthlyMetricCode = 'ELECTRICITY_KWH' | 'ELECTRICITY_TCE' | 'TOTAL_EMISSION' | 'MONTH_AREA_INTENSITY'
export interface MonthlyMetric {
  metricCode: MonthlyMetricCode
  unit: string
  value: string | null
  status: string
  sourceBatchId: string | null
  evidenceHash: string | null
  referenceVersionId: string | null
}
export interface MonthlyPeriodResult {
  month: string
  startInclusive: string
  endExclusive: string
  metrics: MonthlyMetric[]
}
export interface MonthlyComparisonValue {
  currentValue: string | null
  baseValue: string | null
  absoluteChange: string | null
  percentageChange: string | null
  status: string
}
export interface MonthlyComparisonResult {
  buildingId: string
  timezoneId: string
  current: MonthlyPeriodResult
  previousMonth: MonthlyPeriodResult
  sameMonthLastYear: MonthlyPeriodResult
  comparisons: Array<{ metricCode: MonthlyMetricCode; unit: string; monthOnMonth: MonthlyComparisonValue; yearOnYear: MonthlyComparisonValue }>
}

/** 读取封账及核算结果；百分比和冻结版本直接使用后端输出，不在浏览器重算。 */
export function queryMonthlyComparison(buildingId: string, month: string, signal?: AbortSignal) {
  if (!buildingId || !/^(20\d{2}|21\d{2}|2200)-(0[1-9]|1[0-2])$/.test(month)) throw new Error('INVALID_MONTHLY_QUERY')
  return requestApi<MonthlyComparisonResult>({
    method: 'get',
    url: `/v1/energy-reporting/buildings/${encodeURIComponent(buildingId)}/monthly-comparison`,
    params: { month: `${month}-01`, timezoneId: 'Asia/Shanghai' },
    signal,
  })
}
