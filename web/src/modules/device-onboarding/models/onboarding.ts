/**
 * 设备接入模块消费的 V1 契约。
 *
 * 产品草稿、待绑定设备和审批申请各自有独立状态机；页面不以状态字符串推断可执行敏感动作。
 */
export type OnboardingPage<T> = {
  page: number
  size: number
  total: number
  items: T[]
}

export type ProductPointTemplate = {
  templatePointId?: string
  metricCode: string
  pointNameTemplate: string
  suffixCode: string
  unit: string
  minValue: number | null
  maxValue: number | null
  forCalc: boolean
  required: boolean
  sortOrder: number
  enabled: boolean
}

export type DeviceProductForm = {
  productCode: string
  productName: string
  manufacturer: string | null
  model: string | null
  equipmentTypeCode: string
  expectedProfileCode: string
  identityType: string
  points: ProductPointTemplate[]
}

export type DeviceProductListItem = {
  productId: string
  productCode: string
  productName: string
  manufacturer: string | null
  model: string | null
  equipmentTypeCode: string
  expectedProfileCode: string
  identityType: string
  status: string
  pointCount: number
  updateTime: number
}

export type DeviceProductDetail = DeviceProductListItem & {
  points: ProductPointTemplate[]
  allowedActions: string[]
  createTime: number
}

export type PendingDevice = {
  pendingId: string
  identityType: string
  maskedIdentityValue: string
  profileCode: string
  lastProfileVersion: number
  status: string
  reportCount: number
  firstSeenTime: number
  lastSeenTime: number
  sampleTruncated: boolean
}

export type PendingDeviceDetail = PendingDevice & {
  identityValue: string
  boundIdentityId: string | null
  latestEventTime: number
  latestTimeSource: string | null
  latestMetrics: unknown
  allowedActions: string[]
}

export type PendingStatus = 'DISCOVERED' | 'IGNORED'

export type PendingStatusRequest = {
  status: PendingStatus
  reason: string | null
}

export type PointBinding = {
  metricCode: string
  existingPointId?: string | null
  pointCode?: string | null
  pointName?: string | null
  namingRuleId?: string | null
  familyCode?: string | null
  componentCode?: string | null
  dataType?: string | null
}

export type PendingBindRequest = {
  productId: string
  buildingId: string
  spaceId: string
  systemGroupId: string
  existingEquipmentId?: string | null
  newEquipment?: { equipmentName: string; manufacturer: string | null } | null
  pointBindings: PointBinding[]
}

export type ProductStatusKey = 'draft' | 'enabled' | 'disabled' | 'unknown'
export type PendingStatusKey = 'discovered' | 'ignored' | 'bound' | 'unknown'

const PRODUCT_STATUS_KEYS: Record<string, ProductStatusKey> = { DRAFT: 'draft', ENABLED: 'enabled', DISABLED: 'disabled' }
const PENDING_STATUS_KEYS: Record<string, PendingStatusKey> = { DISCOVERED: 'discovered', IGNORED: 'ignored', BOUND: 'bound' }

export function productStatusKey(status: string | null | undefined): ProductStatusKey {
  return PRODUCT_STATUS_KEYS[status?.trim().toUpperCase() ?? ''] ?? 'unknown'
}

export function pendingStatusKey(status: string | null | undefined): PendingStatusKey {
  return PENDING_STATUS_KEYS[status?.trim().toUpperCase() ?? ''] ?? 'unknown'
}

/** 后端未显式给出的动作一律不显示为可执行操作。 */
export function canRunOnboardingAction(value: { allowedActions?: string[] }, action: string): boolean {
  return value.allowedActions?.includes(action) === true
}
