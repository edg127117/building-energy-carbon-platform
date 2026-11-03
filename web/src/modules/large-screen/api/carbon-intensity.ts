import { requestApi } from '@/infrastructure/http/public'
import { readPreparedQuery, type PreparedQueryEnvelope } from '@/shared/infrastructure/prepared-query'

export interface IntensityQuery {
  buildingId: string; mode: 'FORMAL_YEAR' | 'MONTHLY_YTD' | 'MONTH'; year: number; month?: number; timezoneId: string
}
export interface CarbonIntensityView {
  buildingId: string; mode: IntensityQuery['mode']; start: string; endExclusive: string; timezoneId: string
  actual: { value: string | null; unit: string; status: string; gasBasis: string; boundaryStatus: string; areaStatus: string
    batchId: string | null; evidenceHash: string | null; denominatorVersionId: string | null }
  matchStatus: string; comparisonStatus: string; reasons: string[]; percentage: string | null; evidenceHash: string
  percentageStatus: 'AVAILABLE' | 'UNAVAILABLE'; percentageBasis: string; percentageReasons: string[]
  reference: { versionId: string; evidenceHash: string; name: string; documentVersion: string; sourceReference: string; publishedOn: string | null; unit: string
    rule: { constraintValue: string; baselineValue: string; guideValue: string; climateLabel: string | null; gasBasis: string; timeStatus: string } } | null
}
/** 卡片仅查询已有强度与后端比较比例，不触发核算、标准维护或建筑配置。 */
export function readCarbonIntensity(query: IntensityQuery, signal?: AbortSignal) {
  const { buildingId, ...params } = query
  return readPreparedQuery<CarbonIntensityView>(
    signal => requestApi<PreparedQueryEnvelope<CarbonIntensityView>>({ method: 'get', url: `/v1/carbon-intensity/buildings/${encodeURIComponent(buildingId)}/summary`, params, signal }),
    { kind: 'CARBON_INTENSITY', buildingId }, signal,
  )
}
