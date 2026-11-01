import { requestApi } from '@/infrastructure/http/public'
import type { TemperaturePeriodType, TemperatureTrend } from '../models/temperature-trend'

/** 读取已发布历史温度；没有采集、重试入队或预报兜底副作用。 */
export function getTemperatureTrend(buildingId: string, periodType: TemperaturePeriodType, period: string, signal?: AbortSignal) {
  return requestApi<TemperatureTrend>({ url: `/v1/weather/buildings/${encodeURIComponent(buildingId)}/temperature-trend`, method: 'GET', params: { periodType, period }, signal })
}
