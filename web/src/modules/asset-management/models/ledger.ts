import type { AssetEquipment, AssetSpace } from './assets'

export type LedgerGroup = 'ALL' | 'POWER' | 'HVAC' | 'RENEWABLE' | 'WATER' | 'ENVIRONMENT' | 'UNCLASSIFIED'
export const ledgerGroups: LedgerGroup[] = ['ALL', 'POWER', 'HVAC', 'RENEWABLE', 'WATER', 'ENVIRONMENT', 'UNCLASSIFIED']

export type LedgerOperationValue = 'RUNNING' | 'STOPPED' | 'UNKNOWN' | 'NOT_APPLICABLE'
export type LedgerCommunicationValue = 'ONLINE' | 'OFFLINE' | 'UNKNOWN'
export type LedgerOperationScope = 'DEVICE' | 'COMPRESSOR'
export type LedgerCommunicationScope = 'DEVICE'
export type LedgerDisplayPointStatus = 'UNCONFIGURED' | 'UNMATCHED' | 'AMBIGUOUS' | 'DISABLED' | 'AVAILABLE' | 'NO_DATA' | 'QUALITY_BLOCKED' | 'UNAVAILABLE'

export const ledgerCommunicationReasons = [
  'LIVE_TELEMETRY',
  'LIVE_POLL',
  'REPORT_DELAYED',
  'DEVICE_TIMEOUT',
  'DEVICE_REPORTED_OFFLINE',
  'UPSTREAM_GATEWAY_OFFLINE',
  'PLATFORM_PIPELINE_UNAVAILABLE',
  'WAITING_INITIAL_CONTACT',
  'NOT_MONITORED',
  'IDENTITY_CONFLICT',
  'STALE_SCOPE',
] as const

export type LedgerCommunicationReason = (typeof ledgerCommunicationReasons)[number]
export type LedgerCommunicationDisplayValue = LedgerCommunicationValue | 'NOT_MONITORED'

/** 台账状态由服务端在设备身份和展示规则范围内判定；前端不得从档案启用、控制器连接或实时读数反推。 */
export type LedgerState<Value extends string = string, Scope extends string = string> = {
  applicable: boolean
  value: Value
  reason: string
  source: string | null
  observedAt: number | null
  scope: Scope
}

export type LedgerOperationState = LedgerState<LedgerOperationValue, LedgerOperationScope>
export type LedgerCommunicationState = LedgerState<LedgerCommunicationValue, LedgerCommunicationScope>

/** 通讯结论只使用后端台账已判定的字段；浏览器不按本地时间或读数重新计算在线状态。 */
export function ledgerCommunicationDisplayValue(state: LedgerCommunicationState | null | undefined): LedgerCommunicationDisplayValue {
  if (!state) return 'UNKNOWN'
  return state.applicable ? state.value : 'NOT_MONITORED'
}

export function isLedgerCommunicationDelayed(state: LedgerCommunicationState | null | undefined): boolean {
  return state?.applicable === true && state.value === 'ONLINE' && state.reason === 'REPORT_DELAYED'
}

export type LedgerDisplayPoint = {
  status: LedgerDisplayPointStatus
  reason: string | null
  pointId: string | null
  pointCode: string | null
  pointName: string | null
  unit: string | null
  value: number | null
  eventTime: number | null
  receivedTime: number | null
  dataQuality: number | null
  usageStatus: string | null
}

export type AssetLedgerItem = {
  equipment: AssetEquipment
  professionalGroup: LedgerGroup
  classificationSource: string
  ruleRevision: number
  classificationRevision: number
  operation: LedgerOperationState
  communication: LedgerCommunicationState
  displayPoint: LedgerDisplayPoint
}

export type AssetLedgerQuery = {
  page: number
  size: number
  buildingId?: string
  spaceId?: string
  systemGroupId?: string
  keyword?: string
  typeCode?: string
  professionalGroup: LedgerGroup
}

export type AssetLedgerPage = {
  page: number
  size: number
  total: number
  items: AssetLedgerItem[]
  groupCounts: Record<LedgerGroup, number>
  summary: {
    assets: number
    points: number
    online: number
    offline: number
    communicationError: number
    communicationUnknown: number
    notMonitored: number
    delayed: number
  }
  generatedAt: number
}

/** 列表只展示后端选定的展示测点；右侧临时选点不应影响这个值。 */
export function ledgerDisplayPointValue(point: LedgerDisplayPoint): number | null {
  return point.status === 'AVAILABLE' && point.usageStatus === 'AVAILABLE' && point.value != null && Number.isFinite(point.value) ? point.value : null
}

export function filterLedgerSpaces(spaces: AssetSpace[], keyword: string): AssetSpace[] {
  const query = keyword.trim().toLocaleLowerCase()
  if (!query) return spaces
  return spaces.flatMap(space => {
    const children = filterLedgerSpaces(space.children, query)
    return space.spaceName.toLocaleLowerCase().includes(query) || children.length
      ? [{ ...space, children: space.spaceName.toLocaleLowerCase().includes(query) ? space.children : children }]
      : []
  })
}
