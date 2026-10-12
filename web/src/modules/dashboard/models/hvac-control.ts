/**
 * 控制结果只采纳后端返回。OBSERVED_STABLE 和 cloudStable 是云端读取结论，不能把 UNKNOWN 升级为成功，也不能替代现场确认。
 */
export const HVAC_CONTROL_ACTIONS = ['ON_OFF', 'TEMPERATURE', 'MODE', 'AIR_FLOW', 'FAN_SPEED'] as const
export type HvacControlAction = typeof HVAC_CONTROL_ACTIONS[number]

export const HVAC_CONTROL_OPERATION_STATUSES = [
  'PREPARING', 'SENDING', 'ACCEPTED', 'OBSERVED_MATCH', 'OBSERVED_STABLE', 'CONFIRMED', 'REJECTED', 'NOT_SENT', 'UNKNOWN', 'UNCONFIRMED', 'CLOSED',
] as const
export type HvacControlOperationStatus = typeof HVAC_CONTROL_OPERATION_STATUSES[number]

export interface HvacControlActionOption {
  action: HvacControlAction
  allowed: boolean
  reasonCode: string | null
  values: string[]
  minimum: number | null
  maximum: number | null
}

export interface HvacControlOptions {
  equipmentId: string
  canControl: boolean
  reasonCode: string | null
  revision: string | null
  actions: HvacControlActionOption[]
  activeOperationId: string | null
}

export interface HvacControlOperation {
  operationId: string
  equipmentId: string
  action: HvacControlAction
  value: string
  status: HvacControlOperationStatus
  reasonCode: string | null
  vendorCode: string | null
  observedValue: string | null
  createdAt: number
  updatedAt: number
  closedReason: string | null
  deadlineAt: number | null
  stableCount: number
  stableSince: number | null
  lastReadAt: number | null
  unitStatus: string | null
  isolated: boolean
  cloudStable: boolean
}

export interface HvacControlOperationPage {
  page: number
  size: number
  total: number
  items: HvacControlOperation[]
}

export interface HvacControlCommand {
  action: HvacControlAction
  value: string
  idempotencyKey: string
  revision: string
}

export function isHvacControlPollingStatus(status: HvacControlOperationStatus): boolean {
  return status === 'PREPARING' || status === 'SENDING' || status === 'ACCEPTED' || status === 'UNKNOWN'
}

export function needsHvacControlResolution(status: HvacControlOperationStatus): boolean {
  return status === 'UNKNOWN' || status === 'UNCONFIRMED' || status === 'OBSERVED_STABLE'
}

export function canHvacControlRecheck(operation: HvacControlOperation): boolean {
  return operation.status === 'ACCEPTED'
    || needsHvacControlResolution(operation.status)
    || (operation.status === 'CLOSED' && operation.isolated)
}

export function canHvacControlClose(operation: HvacControlOperation): boolean {
  return operation.status === 'UNKNOWN' || operation.status === 'UNCONFIRMED'
}

export function canHvacControlConfirm(operation: HvacControlOperation): boolean {
  return operation.status === 'OBSERVED_STABLE'
}

export function canHvacControlRelease(operation: HvacControlOperation): boolean {
  return operation.isolated
    && operation.cloudStable
    && (operation.status === 'UNKNOWN' || operation.status === 'UNCONFIRMED' || operation.status === 'CLOSED')
}

export function occupiesHvacControlSlot(operation: HvacControlOperation | null | undefined): boolean {
  return operation != null
    && (isHvacControlPollingStatus(operation.status) || needsHvacControlResolution(operation.status) || operation.isolated)
}
