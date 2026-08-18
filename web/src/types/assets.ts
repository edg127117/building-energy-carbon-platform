/**
 * 资产管理 V1 的浏览器契约。ID 保持不透明，页面不能从编码推断建筑、设备类型或层级。
 * 状态由后端权威决定；未知状态只用于只读展示，不能被前端默认放行写操作。
 */
export type AssetStatus = string

export type AssetAllowedAction = 'CREATE' | 'UPDATE' | 'DELETE' | 'DISABLE' | 'ENABLE' | string

export type AssetPageResponse<T> = {
  page: number
  size: number
  total: number
  items: T[]
}

export type AssetReferenceSummary = {
  spaces: number
  systemGroups: number
  equipment: number
  points: number
  authorizations: number
  children: number
  aliases: number
  identities: number
}

export type AssetBuildingView = {
  buildingId: string
  buildingName: string
  buildingCode: string | null
  buildingType: string | null
  climateZone: string | null
  constructionYear: number | null
  totalGfa: number | null
  status: AssetStatus
  allowedActions: AssetAllowedAction[]
  references: AssetReferenceSummary
  updateTime: number
}

export type AssetBuildingDetail = AssetBuildingView

export type AssetBuildingCreateRequest = {
  buildingName: string
  buildingCode?: string | null
  buildingType?: string | null
  climateZone?: string | null
  constructionYear?: number | null
  totalGfa?: number | null
  status: AssetStatus
}

/** 建筑编码在当前 V1 可编辑，但更新 DTO 仍单独声明，避免与其他资产复用错误字段。 */
export type AssetBuildingUpdateRequest = {
  buildingName: string
  buildingCode?: string | null
  buildingType?: string | null
  climateZone?: string | null
  constructionYear?: number | null
  totalGfa?: number | null
  status: AssetStatus
}

export type AssetBuildingForm = AssetBuildingCreateRequest

export type AssetSpaceView = {
  spaceId: string
  buildingId: string
  parentSpaceId: string | null
  spaceName: string
  spaceCode: string | null
  spaceType: string | null
  sortOrder: number
  usableArea: number | null
  status: AssetStatus
  references: AssetReferenceSummary
  updateTime: number
  allowedActions: AssetAllowedAction[]
  children: AssetSpaceView[]
}

export type AssetSpaceCreateRequest = {
  buildingId: string
  parentSpaceId?: string | null
  spaceName: string
  spaceCode?: string | null
  spaceType?: string | null
  sortOrder?: number
  usableArea?: number | null
  status: AssetStatus
}

export type AssetSpaceUpdateRequest = Omit<AssetSpaceCreateRequest, 'buildingId'>

export type AssetSpaceForm = AssetSpaceCreateRequest

export type AssetSystemGroupView = {
  systemGroupId: string
  buildingId: string
  systemName: string
  systemCode: string | null
  systemType: string | null
  sortOrder: number
  status: AssetStatus
  references: AssetReferenceSummary
  updateTime: number
  allowedActions: AssetAllowedAction[]
}

export type AssetSystemGroupCreateRequest = {
  buildingId: string
  systemName: string
  systemCode?: string | null
  systemType?: string | null
  status: AssetStatus
}

export type AssetSystemGroupUpdateRequest = Omit<AssetSystemGroupCreateRequest, 'buildingId' | 'systemCode'>

export type AssetSystemGroupForm = AssetSystemGroupCreateRequest

export type AssetEquipmentQuery = {
  page: number
  size: number
  buildingId?: string
  spaceId?: string
  systemGroupId?: string
  typeCode?: string
  productId?: string
  status?: AssetStatus
  keyword?: string
}

export type AssetEquipmentView = {
  equipmentId: string
  equipmentName: string
  equipmentCode: string | null
  buildingId: string
  buildingName: string | null
  spaceId: string | null
  spaceName: string | null
  systemGroupId: string | null
  systemGroupName: string | null
  typeCode: string | null
  productId: string | null
  productName: string | null
  status: AssetStatus
  lastDiscoveredTime: number | null
  category: string | null
  expectedProfileCode: string | null
  pointSummary: {
    total: number
    required: number
    configuredRequired: number
  }
  allowedActions: AssetAllowedAction[]
  updateTime: number
}

export type AssetEquipmentDetail = AssetEquipmentView & {
  manufacturer: string | null
  ratedCapacity: number | null
  ratedPower: number | null
  designCop: number | null
  identities: Array<{
    identityId: string
    identityType: string
    identityValue: string
    status: AssetStatus
    expectedProfileCode: string | null
  }>
  references: AssetReferenceSummary
}

export type AssetEquipmentCreateRequest = {
  equipmentName: string
  buildingId: string
  spaceId: string
  systemGroupId: string
  typeCode: string
  productId?: string | null
  manufacturer?: string | null
  ratedCapacity?: number | null
  ratedPower?: number | null
  designCop?: number | null
  status: AssetStatus
}

export type AssetEquipmentUpdateRequest = Pick<AssetEquipmentCreateRequest, 'buildingId' | 'spaceId' | 'systemGroupId' | 'equipmentName' | 'manufacturer' | 'ratedCapacity' | 'ratedPower' | 'designCop' | 'status'>

export type AssetEquipmentForm = AssetEquipmentCreateRequest

export type AssetDataPointView = {
  pointId: string
  equipmentId: string
  pointName: string
  pointCode: string | null
  dataType: string | null
  unit: string | null
  minValue: number | null
  maxValue: number | null
  forCalculation: boolean
  required: boolean
  status: AssetStatus
  sourceAliases: string[]
  references: AssetReferenceSummary
  allowedActions: AssetAllowedAction[]
  updateTime: number
}

export type AssetDataPointUpdateRequest = {
  pointName?: string
  minValue?: number | null
  maxValue?: number | null
  forCalculation?: boolean
  status?: AssetStatus
}

export type AssetBuildingQuery = {
  page: number
  size: number
  keyword?: string
}

export type AssetSystemGroupQuery = {
  page: number
  size: number
  buildingId?: string
  keyword?: string
}

export type AssetStatusPresentation = {
  label: string
  color: 'green' | 'orange' | 'red' | 'default' | 'blue'
  known: boolean
}

const STATUS_PRESENTATIONS: Record<string, Omit<AssetStatusPresentation, 'known'>> = {
  DRAFT: { label: '草稿', color: 'blue' },
  ENABLED: { label: '启用', color: 'green' },
  ACTIVE: { label: '启用', color: 'green' },
  ONLINE: { label: '在线', color: 'green' },
  OFFLINE: { label: '离线', color: 'orange' },
  UNBOUND: { label: '待绑定', color: 'blue' },
  DISABLED: { label: '停用', color: 'orange' },
  INACTIVE: { label: '停用', color: 'orange' },
  ARCHIVED: { label: '已归档', color: 'default' },
}

/** 将服务端枚举转换为页面文案；新状态不能自动获得写入能力。 */
export function presentAssetStatus(status: AssetStatus | null | undefined): AssetStatusPresentation {
  const source = typeof status === 'string' ? status.trim() : ''
  const known = STATUS_PRESENTATIONS[source]
  return known ? { ...known, known: true } : { label: `未知（${source || '空值'}）`, color: 'default', known: false }
}

/** 写操作只接受后端显式授权；未知状态或缺失 allowedActions 都保持只读。 */
export function canRunAssetAction(record: { status: AssetStatus; allowedActions?: AssetAllowedAction[] }, action: AssetAllowedAction): boolean {
  const status = presentAssetStatus(record.status)
  if (!status.known) return false
  return record.allowedActions?.includes(action) === true
}
