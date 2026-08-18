export type PageResponse<T> = {
  page: number
  size: number
  total: number
  items: T[]
}

export type DeviceProductStatus = string
export type DeviceProductAction = 'UPDATE' | 'COPY' | 'ENABLE' | 'DISABLE' | string
export type PendingDeviceAction = 'IGNORE' | 'RESTORE' | 'BIND' | string

export type DeviceProductPointTemplate = {
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

export type DeviceProductListItem = {
  productId: string
  productCode: string
  productName: string
  manufacturer: string | null
  model: string | null
  equipmentTypeCode: string
  expectedProfileCode: string
  identityType: string
  status: DeviceProductStatus
  pointCount: number
  updateTime: number
}

export type DeviceProductDetail = Omit<DeviceProductListItem, 'pointCount'> & {
  points: DeviceProductPointTemplate[]
  allowedActions: DeviceProductAction[]
  createTime: number
}

export type DeviceProductForm = {
  productCode?: string
  productName: string
  manufacturer?: string | null
  model?: string | null
  equipmentTypeCode: string
  expectedProfileCode: string
  identityType: string
  points: DeviceProductPointTemplate[]
}

export type DeviceProductQuery = {
  page: number
  size: number
  status?: string
  keyword?: string
}

export type PendingDeviceListItem = {
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

export type PendingDeviceDetail = {
  pendingId: string
  identityType: string
  identityValue: string
  profileCode: string
  lastProfileVersion: number
  status: string
  boundIdentityId: string | null
  reportCount: number
  firstSeenTime: number
  lastSeenTime: number
  latestEventTime: number
  latestTimeSource: string | null
  latestMetrics: unknown
  sampleTruncated: boolean
  allowedActions: PendingDeviceAction[]
}

export type PendingDeviceQuery = {
  page: number
  size: number
  status?: string
  identity?: string
  profileCode?: string
}

export type PointBindingRequest = {
  metricCode: string
  existingPointId?: string | null
  pointCode?: string | null
  pointName?: string | null
  namingRuleId?: string | null
  familyCode?: string | null
  componentCode?: string | null
  dataType?: string | null
}

export type DeviceBindRequest = {
  productId: string
  buildingId: string
  spaceId: string
  systemGroupId: string
  existingEquipmentId?: string | null
  newEquipment?: { equipmentName: string; manufacturer?: string | null } | null
  pointBindings: PointBindingRequest[]
}

export type DeviceBindResult = {
  pendingId: string
  identityId: string
  equipmentId: string
  pointIds: string[]
  status: string
  configEffective: boolean
}

export type IdentityStatusResult = {
  identityId: string
  status: string
  configEffective: boolean
}

const PRODUCT_STATUSES = new Set(['DRAFT', 'ENABLED', 'DISABLED'])
const PENDING_STATUSES = new Set(['DISCOVERED', 'IGNORED', 'BOUND'])

/** 写操作完全服从详情中的 allowedActions；未知状态始终降级为只读。 */
export function canRunProductAction(record: Pick<DeviceProductDetail, 'status' | 'allowedActions'>, action: DeviceProductAction) {
  return PRODUCT_STATUSES.has(record.status) && record.allowedActions.includes(action)
}

/** 待绑定状态由后端权威推进，浏览器不根据列表状态自行放行命令。 */
export function canRunPendingAction(record: Pick<PendingDeviceDetail, 'status' | 'allowedActions'>, action: PendingDeviceAction) {
  return PENDING_STATUSES.has(record.status) && record.allowedActions.includes(action)
}
