import { requestApi, TransportError } from '@/infrastructure/http/public'
import type { Binding, BindingRequest, Building, Comparison, DailyWeather, EnergyRequest, FetchRequest, Job, Management, Overview, Page, Target, WeatherSeries } from '../models/weather'

const root = '/v1/weather'
const buildingPath = (building: string) => `${root}/buildings/${encodeURIComponent(building)}`
const get = <T>(url: string, signal?: AbortSignal, params?: Record<string, unknown>) => requestApi<T>({ url, method: 'GET', signal, params })
export const weatherApi = {
  async buildings(signal?: AbortSignal): Promise<Building[]> {
    const result: Building[] = []
    for (let page = 1; page <= 200; page++) {
      const data = await get<{ records: Building[]; total: number }>('/building/list', signal, { page, size: 100 })
      result.push(...data.records)
      if (result.length >= data.total) return result
      if (!data.records.length) throw new Error('BUILDING_PAGE_CHANGED')
    }
    throw new Error('BUILDING_LIST_LIMIT')
  },
  overview: (building: string, signal?: AbortSignal) => get<Overview>(`${buildingPath(building)}/overview`, signal),
  management: (building: string, signal?: AbortSignal) => get<Management>(`${buildingPath(building)}/management`, signal),
  targets: (building: string, signal?: AbortSignal) => get<Target[]>(`${buildingPath(building)}/energy-comparison/targets`, signal),
  hourly: (building: string, product: string, from: string, to: string, signal?: AbortSignal) => get<WeatherSeries[]>(`${buildingPath(building)}/hourly`, signal, { product, from, to }),
  daily: (building: string, startDate: string, endDate: string, signal?: AbortSignal) => get<DailyWeather[]>(`${buildingPath(building)}/daily`, signal, { startDate, endDate }),
  comparison: (building: string, systemId: string, pointId: string, startDate: string, endDate: string, metric: string, signal?: AbortSignal) => get<Comparison>(`${buildingPath(building)}/energy-comparison/daily`, signal, { systemId, pointId, startDate, endDate, metric }),
  jobs: (buildingId: string, page: number, signal?: AbortSignal) => get<Page<Job>>(`${root}/jobs`, signal, { buildingId, page, size: 10 }),
  job: (id: string, signal?: AbortSignal) => get<Job>(`${root}/jobs/${encodeURIComponent(id)}`, signal),
  bind: (building: string, data: BindingRequest, key: string) => requestApi<Binding>({ url: `${buildingPath(building)}/binding-versions`, method: 'POST', data, headers: { 'Idempotency-Key': key } }),
  fetch: (data: FetchRequest, key: string) => requestApi<Job[]>({ url: `${root}/jobs`, method: 'POST', data, headers: { 'Idempotency-Key': key } }),
  calculate: (building: string, data: EnergyRequest, key: string) => requestApi<Job[]>({ url: `${buildingPath(building)}/energy-comparison/jobs`, method: 'POST', data, headers: { 'Idempotency-Key': key } }),
}
export function errorCode(error: unknown): string | undefined { return error instanceof TransportError ? error.errorCode : undefined }
export function accessDenied(error: unknown): boolean { return error instanceof TransportError && (error.kind === 'forbidden' || error.kind === 'unauthorized' || error.businessCode === 403) }
