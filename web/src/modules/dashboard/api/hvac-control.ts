import { requestApi } from '@/infrastructure/http/public'
import type {
  HvacControlCommand,
  HvacControlOperation,
  HvacControlOperationPage,
  HvacControlOptions,
} from '../models/hvac-control'

const root = '/v1/hvac-control'
const devicePath = (equipmentId: string) => `${root}/devices/${encodeURIComponent(equipmentId)}`
const operationPath = (operationId: string) => `${root}/operations/${encodeURIComponent(operationId)}`

/** 控制端点使用平台统一 Result 包装；客户端只消费其 data，不直接访问厂家接口。 */
export const hvacControlApi = {
  options: (equipmentId: string) => requestApi<HvacControlOptions>({ method: 'GET', url: `${devicePath(equipmentId)}/options` }),
  createOperation: (equipmentId: string, command: HvacControlCommand) => requestApi<HvacControlOperation>({
    method: 'POST', url: `${devicePath(equipmentId)}/operations`, data: command,
  }),
  operation: (operationId: string) => requestApi<HvacControlOperation>({ method: 'GET', url: operationPath(operationId) }),
  operations: (equipmentId: string, page = 1, size = 20) => requestApi<HvacControlOperationPage>({
    method: 'GET', url: `${devicePath(equipmentId)}/operations`, params: { page, size },
  }),
  recheck: (operationId: string) => requestApi<HvacControlOperation>({ method: 'POST', url: `${operationPath(operationId)}/recheck` }),
  close: (operationId: string, reason: string) => requestApi<HvacControlOperation>({
    method: 'POST', url: `${operationPath(operationId)}/close`, data: { reason },
  }),
  confirm: (operationId: string, reason: string) => requestApi<HvacControlOperation>({
    method: 'POST', url: `${operationPath(operationId)}/confirm`, data: { reason },
  }),
  release: (operationId: string, reason: string) => requestApi<HvacControlOperation>({
    method: 'POST', url: `${operationPath(operationId)}/release`, data: { reason },
  }),
}

export function newHvacControlIdempotencyKey(): string {
  const uuid = globalThis.crypto?.randomUUID?.()
  return uuid ?? `web-control-${Date.now()}-${Math.random().toString(36).slice(2)}`
}
