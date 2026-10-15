/**
 * 控制结果只采纳后端返回。OBSERVED_STABLE 和 cloudStable 是云端读取结论，不能把 UNKNOWN 升级为成功，也不能替代现场确认。
 */
export const HVAC_CONTROL_ACTIONS = ['ON_OFF', 'TEMPERATURE', 'MODE', 'AIR_FLOW', 'FAN_SPEED'] as const
export type HvacControlAction = typeof HVAC_CONTROL_ACTIONS[number]

export const HVAC_CONTROL_BATCH_ITEM_STATUSES = [
  'QUEUED', 'DISPATCHING', 'ACTIVE', 'MATCHED', 'ALREADY_TARGET', 'UNSUPPORTED',
  'FAILED', 'UNCONFIRMED', 'CANCELLED', 'NOT_SENT', 'SUPERSEDED',
] as const
export type HvacControlBatchItemStatus = typeof HVAC_CONTROL_BATCH_ITEM_STATUSES[number]

export const HVAC_CONTROL_OPERATION_STATUSES = [
  'PREPARING', 'SENDING', 'ACCEPTED', 'OBSERVED_MATCH', 'OBSERVED_STABLE', 'CLOUD_VERIFIED', 'CONFIRMED', 'REJECTED', 'NOT_SENT', 'UNKNOWN', 'UNCONFIRMED', 'CLOSED',
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

/** 批量预检由服务端保存快照；浏览器只用它展示可选共同动作，不能据此绕过提交时复核。 */
export interface HvacControlBatchPreviewItem {
  equipmentId: string
  revision: string | null
  reasonCode: string | null
  actions: HvacControlActionOption[]
  impactedEquipmentIds: string[]
  impactedEquipmentLabels?: Record<string, string>
  impactKnown: boolean
}

export interface HvacControlBatchPreview {
  previewId: string
  expiresAt: number
  items: HvacControlBatchPreviewItem[]
}

export interface HvacControlBatchCommand {
  previewId: string
  action: HvacControlAction
  value: string
  idempotencyKey: string
  confirmLinkedImpact: boolean
}

export interface HvacControlBatchItem {
  itemId: string
  equipmentId: string
  status: HvacControlBatchItemStatus
  reasonCode: string | null
  operationId: string | null
  observedValue: string | null
  unitStatus: string | null
  createdAt: number
  dispatchedAt: number | null
  acceptedAt: number | null
  readbackAt: number | null
  queuedMillis: number | null
  acceptanceMillis: number | null
  readbackMillis: number | null
}

/** 所有时间点和耗时均为毫秒；请求目标不能作为当前监测读值展示。 */
export interface HvacControlBatch {
  batchId: string
  action: HvacControlAction
  value: string
  createdAt: number
  updatedAt: number
  completed: boolean
  total: number
  completedCount: number
  queuedCount: number
  activeCount: number
  attentionCount: number
  items: HvacControlBatchItem[]
}

export interface HvacControlBatchPage {
  page: number
  size: number
  total: number
  items: HvacControlBatch[]
}

/**
 * 仅按服务端本次预检返回的许可值启用提交；服务端仍是最终授权和前置核验边界。
 */
export function isHvacControlActionValueAllowed(option: HvacControlActionOption, value: string): boolean {
  if (!option.allowed || !value) return false
  if (option.action !== 'TEMPERATURE') return option.values.includes(value)

  if (!/^\d{2}$/.test(value)) return false
  const temperature = Number(value)
  if (!Number.isInteger(temperature)) return false

  const hasRange = option.minimum != null && option.maximum != null
  if (hasRange && (option.minimum! > option.maximum! || temperature < option.minimum! || temperature > option.maximum!)) return false
  return option.values.length > 0 ? option.values.includes(value) : hasRange
}

export interface HvacControlOptions {
  equipmentId: string
  canControl: boolean
  testOpenControls: boolean
  reasonCode: string | null
  revision: string | null
  actions: HvacControlActionOption[]
  activeOperationId: string | null
  // 旧服务端响应可能尚未返回该字段；有值时才作为设温输入的权威初值。
  currentTemperature?: number | null
}

export type HvacControlGrantAction = Extract<HvacControlAction, 'ON_OFF' | 'TEMPERATURE'>

export interface HvacControlDeviceGrantAccess {
  canManage: boolean
  testOpenControls: boolean
}

export interface HvacControlBuilding {
  buildingId: string
  buildingName: string
}

export type HvacControlOnOff = 'on' | 'off'

/**
 * 历史隔离恢复只适用于服务端判定的单台旧异常；页面不能把它当成普通控制或推断默认现场值。
 */
export interface HvacControlRecovery {
  operation: HvacControlOperation | null
  eligible: boolean
  simpleRecovery: boolean
  reasonCode: string | null
}

export interface HvacControlRecoveryObserveRequest {
  onOff: HvacControlOnOff
  temperature: number
}

export interface HvacControlRecoveryObservation {
  operationId: string
  onOff: string | null
  temperature: number | null
  mode: string | null
  fanSpeed: string | null
  airFlow: string | null
  unitStatus: string | null
  stableCount: number
  stableSince: number | null
  lastReadAt: number | null
  canRecover: boolean
  proof: string | null
  reasonCode: string | null
}

export interface HvacControlRecoveryCompleteRequest extends HvacControlRecoveryObserveRequest {
  proof: string
  reason: string
  onsiteConfirmed: true
}

export interface HvacControlRecoveryContinueRequest {
  reason: string
}

export interface HvacControlDeviceGrantBatchRequest {
  equipmentIds: string[]
  actions: HvacControlGrantAction[]
  evidence: string
}

export interface HvacControlDeviceGrantBatchItem {
  equipmentId: string
  opened: boolean
  reasonCode: string | null
  version: number | null
  actions: HvacControlActionOption[]
}

export interface HvacControlDeviceGrantBatchResult {
  items: HvacControlDeviceGrantBatchItem[]
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
  verificationPolicy: string
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

/**
 * TEST_FAST_V1 只核验本次控制字段的一次云端回显；不能把旧稳定采样或 cloudStable 文案套用到它。
 */
export function isHvacControlFastVerification(operation: Pick<HvacControlOperation, 'verificationPolicy'>): boolean {
  return operation.verificationPolicy === 'TEST_FAST_V1'
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
