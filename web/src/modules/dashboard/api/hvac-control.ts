import { requestApi } from '@/infrastructure/http/public'
import type {
  HvacControlBuilding,
  HvacControlDeviceGrantAccess,
  HvacControlDeviceGrantBatchRequest,
  HvacControlDeviceGrantBatchResult,
  HvacControlCommand,
  HvacControlOperation,
  HvacControlOperationPage,
  HvacControlOptions,
  HvacControlRecovery,
  HvacControlRecoveryContinueRequest,
  HvacControlRecoveryCompleteRequest,
  HvacControlRecoveryObservation,
  HvacControlRecoveryObserveRequest,
} from '../models/hvac-control'

const root = '/v1/hvac-control'
const devicePath = (equipmentId: string) => `${root}/devices/${encodeURIComponent(equipmentId)}`
const operationPath = (operationId: string) => `${root}/operations/${encodeURIComponent(operationId)}`
const DEVICE_GRANT_BATCH_TIMEOUT_MILLIS = 180_000
const RECOVERY_READ_TIMEOUT_MILLIS = 30_000

/** 控制端点使用平台统一 Result 包装；客户端只消费其 data，不直接访问厂家接口。 */
export const hvacControlApi = {
  deviceGrantAccess: () => requestApi<HvacControlDeviceGrantAccess>({ method: 'GET', url: `${root}/device-grants/access` }),
  buildings: () => requestApi<HvacControlBuilding[]>({ method: 'GET', url: `${root}/buildings` }),
  grantDevices: (request: HvacControlDeviceGrantBatchRequest) => requestApi<HvacControlDeviceGrantBatchResult>({
    method: 'POST', url: `${root}/device-grants/batch`, data: request, timeout: DEVICE_GRANT_BATCH_TIMEOUT_MILLIS,
  }),
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
  recovery: (equipmentId: string) => requestApi<HvacControlRecovery>({ method: 'GET', url: `${devicePath(equipmentId)}/recovery` }),
  observeRecovery: (operationId: string, request: HvacControlRecoveryObserveRequest) => requestApi<HvacControlRecoveryObservation>({
    method: 'POST', url: `${operationPath(operationId)}/recovery/observe`, data: request, timeout: RECOVERY_READ_TIMEOUT_MILLIS,
  }),
  completeRecovery: (operationId: string, request: HvacControlRecoveryCompleteRequest) => requestApi<HvacControlOperation>({
    method: 'POST', url: `${operationPath(operationId)}/recovery/complete`, data: request, timeout: RECOVERY_READ_TIMEOUT_MILLIS,
  }),
  continueRecovery: (operationId: string, request: HvacControlRecoveryContinueRequest) => requestApi<HvacControlOperation>({
    method: 'POST', url: `${operationPath(operationId)}/recovery/continue`, data: request, timeout: RECOVERY_READ_TIMEOUT_MILLIS,
  }),
}

export function newHvacControlIdempotencyKey(): string {
  const uuid = globalThis.crypto?.randomUUID?.()
  return uuid ?? `web-control-${Date.now()}-${Math.random().toString(36).slice(2)}`
}
