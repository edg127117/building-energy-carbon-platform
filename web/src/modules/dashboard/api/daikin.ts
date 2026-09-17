import { requestApi } from '@/infrastructure/http/public'
import type { CursorPage, DaikinDevice, DaikinException, DeviceCurrent, RuntimePage, RuntimeRevision, StateEvent, TemperatureCurrent, TemperatureHistory } from '../models/daikin'

const root = '/v1/hvac-monitoring'
const device = (id: string) => `${root}/devices/${encodeURIComponent(id)}`
/** 只查询平台存储；刷新和翻页不触发厂家同步或控制。 */
export const daikinApi = {
  devices: (building: string, page: number, kind?: string, keyword?: string, spaceId?: string, state?: string, hasException?: boolean) => requestApi<{ items: DaikinDevice[]; total: number }>({ method: 'GET', url: `${root}/buildings/${encodeURIComponent(building)}/devices`, params: { page, size: 20, kind: kind || undefined, keyword: keyword || undefined, spaceId: spaceId || undefined, state: state || undefined, hasException } }),
  spaces: (building: string) => requestApi<Array<{ spaceId: string; spaceName: string }>>({ method: 'GET', url: `${root}/buildings/${encodeURIComponent(building)}/spaces` }),
  current: (id: string) => requestApi<DeviceCurrent>({ method: 'GET', url: `${device(id)}/current` }),
  events: (id: string, cursor?: string) => requestApi<CursorPage<StateEvent>>({ method: 'GET', url: `${device(id)}/state-events`, params: { cursor, limit: 50 } }),
  exceptions: (building: string, history: boolean, cursor?: string) => requestApi<CursorPage<DaikinException>>({ method: 'GET', url: `${root}/buildings/${encodeURIComponent(building)}/exceptions/${history ? 'history' : 'current'}`, params: { cursor, limit: 50 } }),
  temperature: (id: string, field: string) => requestApi<TemperatureCurrent>({ method: 'GET', url: `${device(id)}/temperatures/current`, params: { field } }),
  history: (id: string, field: string, from: number, to: number, after?: number) => requestApi<TemperatureHistory>({ method: 'GET', url: `${device(id)}/temperatures`, params: { field, from, to, after, limit: 1000 } }),
  runtime: (id: string, granularity: string, cursor?: string) => requestApi<RuntimePage>({ method: 'GET', url: `${device(id)}/runtime`, params: { granularity, cursor, limit: 50 } }),
  revisions: (id: string, valueId: string, after?: number) => requestApi<{ items: RuntimeRevision[]; nextCursor: number | null }>({ method: 'GET', url: `${device(id)}/runtime/${encodeURIComponent(valueId)}/revisions`, params: { after, limit: 50 } }),
}
