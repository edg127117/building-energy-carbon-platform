/**
 * 资产档案模块消费的 V1 页面契约。
 *
 * 标识符保持不透明，前端只负责传递和展示；资产归属、状态转换及可写动作由后端返回。
 */
export type AssetPage<T> = {
  page: number
  size: number
  total: number
  items: T[]
}

export type AssetReferences = {
  spaces: number
  systemGroups: number
  equipment: number
  points: number
  authorizations: number
  children: number
  aliases: number
  identities: number
}

export type AssetAction = 'CREATE' | 'UPDATE' | 'DELETE' | string

export type AssetBuilding = {
  buildingId: string
  buildingName: string
  buildingCode: string | null
  buildingType: string | null
  constructionYear: number | null
  totalGfa: number | null
  climateZone: string | null
  status: string
  references: AssetReferences
  allowedActions: AssetAction[]
  updateTime: number
}

export type AssetBuildingForm = {
  buildingName: string
  buildingCode: string | null
  buildingType: string | null
  constructionYear: number | null
  totalGfa: number | null
  climateZone: string | null
  status: 'ACTIVE'
}

export type AssetSpace = {
  spaceId: string
  buildingId: string
  parentSpaceId: string | null
  spaceName: string
  spaceCode: string | null
  spaceType: string | null
  sortOrder: number
  usableArea: number | null
  status: string
  references: AssetReferences
  allowedActions: AssetAction[]
  children: AssetSpace[]
  updateTime: number
}

export type AssetSpaceForm = {
  buildingId: string
  parentSpaceId: string | null
  spaceName: string
  spaceCode: string | null
  spaceType: string | null
  sortOrder: number | null
  usableArea: number | null
  status: 'ACTIVE'
}

export type AssetSystemGroup = {
  systemGroupId: string
  buildingId: string
  systemCode: string | null
  systemName: string
  systemType: string | null
  sortOrder: number
  status: string
  references: AssetReferences
  allowedActions: AssetAction[]
  updateTime: number
}

export type AssetSystemGroupForm = {
  buildingId: string
  systemCode: string | null
  systemName: string
  systemType: string | null
  status: 'ACTIVE'
}

export type AssetEquipment = {
  equipmentId: string
  equipmentCode: string | null
  equipmentName: string
  typeCode: string
  category: string | null
  buildingId: string
  buildingName: string | null
  spaceId: string | null
  spaceName: string | null
  systemGroupId: string | null
  systemGroupName: string | null
  productId: string | null
  productName: string | null
  status: string
  expectedProfileCode: string | null
  lastDiscoveredTime: number | null
  pointSummary: {
    total: number
    required: number
    configuredRequired: number
  }
  allowedActions: AssetAction[]
  updateTime: number
}

export type AssetIdentity = {
  identityId: string
  identityType: string
  identityValue: string
  status: string
  expectedProfileCode: string | null
}

export type AssetEquipmentDetail = AssetEquipment & {
  manufacturer: string | null
  ratedCapacity: number | null
  ratedPower: number | null
  designCop: number | null
  parameterGovernanceStatus: string | null
  identities: AssetIdentity[]
  references: AssetReferences
}

export type AssetEquipmentForm = {
  buildingId: string
  spaceId: string | null
  systemGroupId: string | null
  typeCode: string
  equipmentName: string
  productId: string | null
  manufacturer: string | null
  ratedCapacity: number | null
  ratedPower: number | null
  designCop: number | null
  status: 'ACTIVE'
}

export type AssetPoint = {
  pointId: string
  equipmentId: string
  pointCode: string
  pointName: string
  dataType: string | null
  unit: string | null
  minValue: number | null
  maxValue: number | null
  forCalculation: boolean
  required: boolean
  status: string
  sourceAliases: string[]
  references: AssetReferences
  allowedActions: AssetAction[]
  updateTime: number
}

export type AssetPointUpdate = {
  pointName: string | null
  minValue: number | null
  maxValue: number | null
  forCalculation: boolean | null
  status?: string | null
}

export type AssetEquipmentQuery = {
  page: number
  size: number
  buildingId?: string
  spaceId?: string
  systemGroupId?: string
  typeCode?: string
  productId?: string
  status?: string
  keyword?: string
}

export type AssetStatusKey = 'active' | 'disabled' | 'unbound' | 'unknown'

const STATUS_KEYS: Record<string, AssetStatusKey> = {
  ACTIVE: 'active',
  ONLINE: 'active',
  DISABLED: 'disabled',
  INACTIVE: 'disabled',
  UNBOUND: 'unbound',
}

export function assetStatusKey(status: string | null | undefined): AssetStatusKey {
  return STATUS_KEYS[status?.trim().toUpperCase() ?? ''] ?? 'unknown'
}

/** 缺失或未知权限时保持只读，绝不以前端状态推断写权限。 */
export function canRunAssetAction(value: { allowedActions?: AssetAction[] }, action: AssetAction): boolean {
  return value.allowedActions?.includes(action) === true
}

export function flattenSpaces(spaces: AssetSpace[]): AssetSpace[] {
  return spaces.flatMap(space => [space, ...flattenSpaces(space.children)])
}
