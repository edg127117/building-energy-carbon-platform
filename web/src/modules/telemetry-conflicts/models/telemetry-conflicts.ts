export type ConflictState = 'OPEN' | 'EXPIRED' | 'RESOLVED' | 'PENDING_REVIEW'

export type RawTelemetryEvent = {
  pointId: string
  pointCode: string | null
  value: number
  eventTime: number
  receivedTime: number
  sourceSystem: string | null
  sourcePointCode: string | null
  sourceDeviceId: string | null
  dataQuality: number | null
  buildingId?: string | null
  systemGroupId?: string | null
  equipId?: string | null
  equipCode?: string | null
  isForCalc?: number | null
  late?: boolean | null
}

export type ConflictVariant = {
  evidenceId: string
  event: RawTelemetryEvent
  firstSeenAt: number
  lastSeenAt: number
  attempts: number
}

export type ConflictView = {
  pointId: string
  buildingId: string
  eventTime: number
  state: ConflictState
  revision: number
  original: RawTelemetryEvent
  variants: ConflictVariant[]
  effectiveValue: number
  selectionVersion: number
  mode: 'DIRECT' | 'ENERGY_REVIEW'
  ownerId: number | null
  allowedActions: string[]
  correctionVersionId: string | null
}

export type PageResponse<T> = {
  page: number
  size: number
  total: number
  items: T[]
}

export type ConflictListQuery = {
  buildingId: string
  state?: ConflictState
  page: number
  size: number
}

export type OwnerView = {
  buildingId: string
  userId: number | null
  allowedActions: string[]
}

export type AssignOwnerRequest = {
  buildingId: string
  userId: number
}

export type ResolveConflictRequest = {
  evidenceId: string | null
  expectedRevision: number
  reason: string
  evidenceReference: string
}
