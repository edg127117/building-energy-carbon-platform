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

const PREPARATION_TIMEOUT_MS = 120_000
const PREPARATION_POLL_INTERVAL_MS = 2_000

/** 服务端准备期间只重试明确的准备状态；权限、完整性和来源错误立即交给页面。 */
export async function readDynamicElectricity(
  buildingId: string,
  periodType: DynamicElectricityPeriodType,
  period: string,
  signal?: AbortSignal,
  onPreparing?: () => void,
) {
  const deadline = Date.now() + PREPARATION_TIMEOUT_MS
  for (;;) {
    if (Date.now() >= deadline) throw new Error('DYNAMIC_DISPLAY_PREPARATION_TIMEOUT')
    if (signal?.aborted) throw new DOMException('Aborted', 'AbortError')
    try {
      return await requestApi<DynamicElectricityView>({
        method: 'get',
        url: `/v1/carbon-management/buildings/${encodeURIComponent(buildingId)}/dynamic-electricity`,
        params: { periodType, period },
        timeout: Math.max(1, deadline - Date.now()),
        signal,
      })
    } catch (error) {
      if (Date.now() >= deadline) throw new Error('DYNAMIC_DISPLAY_PREPARATION_TIMEOUT')
      if (!(error instanceof Error) || !('status' in error) || error.status !== 503
        || !('errorCode' in error) || error.errorCode !== 'PERIOD_DISPLAY_PREPARING') throw error
      onPreparing?.()
      const waitMs = Math.min(PREPARATION_POLL_INTERVAL_MS, deadline - Date.now())
      await new Promise<void>((resolve, reject) => {
        const abort = () => { clearTimeout(timer); signal?.removeEventListener('abort', abort); reject(new DOMException('Aborted', 'AbortError')) }
        const timer = setTimeout(() => { signal?.removeEventListener('abort', abort); resolve() }, waitMs)
        signal?.addEventListener('abort', abort, { once: true })
        if (signal?.aborted) abort()
      })
    }
  }
}
