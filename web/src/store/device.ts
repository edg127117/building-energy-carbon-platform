import { defineStore } from 'pinia'
import { listDevicesApi, getStatusSummaryApi, type Device, type StatusSummary } from '@/api/device'
import type { WsDeviceMessage } from '@/types/ws'

type TelemetryPoint = {
  t: number
  voltage_a?: number | null
  current_a?: number | null
  active_power?: number | null
}

type AlarmItem = {
  id: string
  deviceId: string
  text: string
  level: 'info' | 'warn' | 'danger'
  t: number
}

type DeviceState = {
  devices: Device[]
  totalCount: number
  statusSummary: StatusSummary | null
  telemetryByDevice: Record<string, TelemetryPoint[]>
  alarms: AlarmItem[]
  pendingCommandIds: Record<string, { deviceId: string; t: number }>
}

function normalizeNumber(v: unknown): number | null {
  if (v === null || v === undefined) return null
  const n = typeof v === 'number' ? v : Number(v)
  if (!Number.isFinite(n)) return null
  return n
}

export const useDeviceStore = defineStore('device', {
  state: (): DeviceState => ({
    devices: [],
    totalCount: 0,
    statusSummary: null,
    telemetryByDevice: {},
    alarms: [],
    pendingCommandIds: {},
  }),
  getters: {
    onlineCount: (s) => s.statusSummary?.online ?? s.devices.filter((d) => d.status === 1).length,
    offlineCount: (s) => s.statusSummary?.offline ?? s.devices.filter((d) => d.status !== 1).length,
  },
  actions: {
    async fetchList(params?: { page?: number; pageSize?: number; deviceId?: string; status?: number | null }) {
      const res = await listDevicesApi({
        page: params?.page ?? 1,
        pageSize: params?.pageSize ?? 20,
        deviceId: params?.deviceId,
        status: params?.status,
      })
      this.devices = res.data?.records ?? []
      this.totalCount = res.data?.total ?? 0
    },
    async fetchStatusSummary() {
      const res = await getStatusSummaryApi()
      this.statusSummary = res.data ?? null
    },
    async fetchAllForDashboard() {
      const res = await listDevicesApi({ page: 1, pageSize: 9999 })
      this.devices = res.data?.records ?? []
      this.totalCount = res.data?.total ?? 0
    },
    markCommandPending(commandId: string, deviceId: string) {
      this.pendingCommandIds[commandId] = { deviceId, t: Date.now() }
    },
    clearCommandPending(commandId: string) {
      delete this.pendingCommandIds[commandId]
    },
    applyWsMessage(msg: WsDeviceMessage) {
      const deviceId = msg.deviceId
      if (!deviceId) return

      if (msg.type === 'offline') {
        const d = this.devices.find((x) => x.deviceId === deviceId)
        if (d) d.status = 0

        this.alarms.unshift({
          id: `${msg.messageId ?? 'offline'}-${deviceId}-${msg.timestamp ?? Date.now()}`,
          deviceId,
          text: `设备离线：${deviceId}`,
          level: 'danger',
          t: msg.timestamp ?? Date.now(),
        })
        this.alarms = this.alarms.slice(0, 50)
        return
      }

      if (msg.type === 'property') {
        const data = msg.data ?? {}
        const point: TelemetryPoint = {
          t: msg.timestamp ?? Date.now(),
          voltage_a: normalizeNumber(data.voltage_a),
          current_a: normalizeNumber(data.current_a),
          active_power: normalizeNumber(data.active_power),
        }

        const arr = (this.telemetryByDevice[deviceId] = this.telemetryByDevice[deviceId] ?? [])
        arr.push(point)
        if (arr.length > 240) arr.splice(0, arr.length - 240)

        const d = this.devices.find((x) => x.deviceId === deviceId)
        if (d) d.status = 1

        return
      }

      if (msg.type === 'reply') {
        const data = msg.data ?? {}
        const commandId = String(data.commandId ?? '')
        if (commandId) this.clearCommandPending(commandId)
        return
      }
    },
  },
})

