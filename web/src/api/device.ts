import { http } from '@/utils/request'
import type { ApiResult } from '@/types/api'

export type Device = {
  id: number
  deviceId: string
  deviceName: string
  deviceType: number
  location?: string
  status?: number
  ipAddress?: string
  createTime?: string
  updateTime?: string
}

export type PageResult<T> = {
  records: T[]
  total: number
  size: number
  current: number
  pages: number
}

export type StatusSummary = {
  online: number
  offline: number
  fault: number
}

export type AddDeviceReq = {
  deviceId: string
  deviceName: string
  deviceType: number
  location?: string
}

export async function listDevicesApi(params: {
  page: number
  pageSize: number
  deviceId?: string
  status?: number | null
}) {
  const resp = await http.get<ApiResult<PageResult<Device>>>('/device/list', { params })
  return resp.data
}

export async function getStatusSummaryApi() {
  const resp = await http.get<ApiResult<StatusSummary>>('/device/status-summary')
  return resp.data
}

export async function addDeviceApi(payload: AddDeviceReq) {
  const resp = await http.post<ApiResult<string>>('/device/add', payload)
  return resp.data
}

export async function deleteDeviceApi(id: number) {
  const resp = await http.delete<ApiResult<string>>(`/device/delete/${id}`)
  return resp.data
}

export type TelemetryPoint = {
  t: number
  voltage_a?: number | null
  current_a?: number | null
  active_power?: number | null
}

export async function getTelemetryHistoryApi(deviceId: string, hours: number) {
  const resp = await http.get<ApiResult<TelemetryPoint[]>>('/telemetry/history', {
    params: { deviceId, hours },
  })
  return resp.data
}

