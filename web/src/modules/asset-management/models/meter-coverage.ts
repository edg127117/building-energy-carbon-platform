export type MeterTarget = {
  equipmentId: string
  equipmentCode: string | null
  equipmentName: string
  spaceId: string | null
  spaceName: string | null
  active: boolean
}

export type MeterCoverageView = {
  equipmentId: string
  revision: number
  effectiveAt: string | null
  installationSpaceId: string | null
  installationSpaceName: string | null
  scopeLabel: string | null
  reason: string | null
  targets: MeterTarget[]
  quantityMode: 'GROUP_ONLY'
  aggregationPolicy: 'SEPARATE_ONLY'
}

export type MeterCoverageUpdate = {
  expectedRevision: number
  installationSpaceId: string | null
  scopeLabel: string
  targetEquipmentIds: string[]
  reason: string
}

export type MeterCoveragePage<T> = {
  page: number
  size: number
  total: number
  items: T[]
}

export type MeterCandidateQuery = { page: number; size: number; keyword?: string }
