import { requestApi } from '@/infrastructure/http/public'

export interface AnnualQuery {
  buildingId: string
  year: number
  mode: 'FORMAL_YEAR' | 'MONTHLY_YTD'
  throughMonth?: number
  timezoneId: string
}
export interface AnnualMetric {
  code: string; unit: string; value: string | null; status: string; missingMonths: string[]
  sources: Array<{ month: string; batchId: string; evidenceHash: string | null; versionId: string | null }>
}
export interface AnnualPeriod {
  start: string; endExclusive: string; startInclusive: string; endInstantExclusive: string
  metrics: AnnualMetric[]; averageArea: string | null; areaStatus: string
  areaParts: Array<{ versionId: string; from: string; to: string; area: string; sourceReference: string; evidenceReference: string }>
}
export interface AnnualOverview {
  buildingId: string; year: number; mode: AnnualQuery['mode']; throughMonth: number; timezoneId: string
  algorithmVersion: string; evidenceHash: string; current: AnnualPeriod; samePeriodLastYear: AnnualPeriod
  comparisons: Array<{ code: string; yearOnYear: { currentValue: string | null; baseValue: string | null; absoluteChange: string | null; percentageChange: string | null; status: string } }>
}
/** 原样读取后端累计值与证据；浏览器不累计月量、不计算面积或强度。 */
export function readAnnualOverview(query: AnnualQuery, signal?: AbortSignal) {
  const { buildingId, ...params } = query
  return requestApi<AnnualOverview>({ method: 'get', url: `/v1/energy-reporting/buildings/${encodeURIComponent(buildingId)}/annual-overview`, params, signal })
}
export function validateAnnualOverview(result: AnnualOverview, query: AnnualQuery) {
  const cutoff = query.mode === 'FORMAL_YEAR' ? 12 : query.throughMonth
  const end = `${query.year + (cutoff === 12 ? 1 : 0)}-${String(cutoff === 12 ? 1 : (cutoff ?? 0) + 1).padStart(2, '0')}-01`
  if (result.buildingId !== query.buildingId || result.year !== query.year || result.mode !== query.mode
    || result.throughMonth !== cutoff || result.timezoneId !== query.timezoneId
    || result.current.start !== `${query.year}-01-01` || result.current.endExclusive !== end) throw new Error('ANNUAL_OVERVIEW_SCOPE_MISMATCH')
}
/** 仅供展示数值转换；必须匹配指标、单位和可用状态。 */
export function annualDisplayValue(result: AnnualOverview, code: string, unit: string): number | null {
  const metrics = result.current.metrics.filter(m => m.code === code)
  const metric = metrics.length === 1 ? metrics[0] : undefined
  if (!metric || metric.status !== 'AVAILABLE' || metric.unit !== unit || metric.value === null || !/^-?\d+(?:\.\d+)?$/.test(metric.value)) return null
  const number = Number(metric.value)
  return Number.isFinite(number) ? number : null
}
