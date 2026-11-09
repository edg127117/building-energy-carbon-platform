import { requestApi } from '@/infrastructure/http/public'

export interface DailyStaticSource {
  grain: 'DAY' | 'HOUR'; calculationBatchId: string; revision: number | null
  evidenceHash: string | null; declaredSourceNature: string | null; sourceVersion: string | null
}
export interface DailyTrendDay {
  date: string; startInclusive: string; endExclusive: string
  electricityKwh: string | null; electricityStatus: string; electricityReasonCode: string | null
  classificationVersionId: string | null; relationVersionId: string | null
  electricityEvidenceIds?: string[]; electricitySourceNature: string | null
  staticEmissionTco2e: string | null; staticCarbonStatus: string
  staticFactorKgCo2ePerKwh: string | null; factorStatus: string
  staticSource?: DailyStaticSource | null
  /** 旧摘要可缺失；缺失按 UNKNOWN 处理，不能与动态 CO2 同轴混标。 */
  gasBasis?: 'CO2_ONLY' | 'GHG_CO2E' | 'UNKNOWN'
  hourlyBatchId: string | null; hourlyRevision: number | null; hourlySourceNature: string | null
  factorVersionIds: string[]; hourlyEvidenceHash: string | null
}
export interface DailyTrends {
  buildingId: string; month: string; timezoneId: string; state: string
  electricityUnit: string; emissionUnit: string; factorUnit: string; resultNature: string
  contractVersion: string; days: DailyTrendDay[]; capturedAt: string; evidenceHash: string | null
  sourceVersion?: string; detailPath?: string; retryAfterSeconds?: number
}
/** 精简摘要由后台准备；按服务端提示有界轮询，不在浏览器拆月量、合计小时或计算因子。 */
export async function readDailyTrends(buildingId: string, month: string, signal?: AbortSignal, onPreparing?: () => void) {
  const deadline = Date.now() + 90000
  for (;;) {
    const view = await requestApi<DailyTrends>({ method: 'get', url: `/v1/energy-reporting/buildings/${encodeURIComponent(buildingId)}/daily-trends/summary`, params: { month }, signal })
    if (view?.state !== 'PREPARING') return view
    if (view.buildingId !== buildingId || view.month !== month || view.contractVersion !== 'DAILY_ELECTRICITY_STATIC_CARBON_SUMMARY_V1') throw new Error('DAILY_SUMMARY_SCOPE_MISMATCH')
    onPreparing?.()
    const delay = Math.min(5, Math.max(1, view.retryAfterSeconds ?? 2)) * 1000
    if (Date.now() + delay >= deadline) throw new Error('DAILY_SUMMARY_PREPARATION_TIMEOUT')
    await new Promise<void>((resolve, reject) => {
      const abort = () => { clearTimeout(timer); signal?.removeEventListener('abort', abort); reject(new DOMException('Aborted', 'AbortError')) }
      const timer = setTimeout(() => { signal?.removeEventListener('abort', abort); resolve() }, delay)
      signal?.addEventListener('abort', abort, { once: true })
      if (signal?.aborted) abort()
    })
  }
}
