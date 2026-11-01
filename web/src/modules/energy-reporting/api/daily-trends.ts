import { requestApi } from '@/infrastructure/http/public'

export interface DailyTrendDay {
  date: string; startInclusive: string; endExclusive: string
  electricityKwh: string | null; electricityStatus: string; electricityReasonCode: string | null
  classificationVersionId: string | null; relationVersionId: string | null
  electricityEvidenceIds: string[]; electricitySourceNature: string | null
  staticEmissionTco2e: string | null; staticCarbonStatus: string
  staticFactorKgCo2ePerKwh: string | null; factorStatus: string
  hourlyBatchId: string | null; hourlyRevision: number | null; hourlySourceNature: string | null
  factorVersionIds: string[]; hourlyEvidenceHash: string | null
}
export interface DailyTrends {
  buildingId: string; month: string; timezoneId: string; state: string
  electricityUnit: string; emissionUnit: string; factorUnit: string; resultNature: string
  contractVersion: string; days: DailyTrendDay[]; capturedAt: string; evidenceHash: string
}
/** 已完成日的独立监测结果；不在前端拆月量、合计小时量或计算排放因子。 */
export function readDailyTrends(buildingId: string, month: string, signal?: AbortSignal) {
  return requestApi<DailyTrends>({ method: 'get', url: `/v1/energy-reporting/buildings/${encodeURIComponent(buildingId)}/daily-trends`, params: { month }, signal })
}
