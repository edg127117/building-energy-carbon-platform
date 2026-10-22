import { requestApi } from '@/infrastructure/http/public'

export type EnergyPathway = 'STATIONARY_COMBUSTION' | 'PURCHASED_ELECTRICITY' | 'PURCHASED_HEAT' | 'WATER' | 'GREEN_ENERGY'
export type EnergyCapability = 'ENABLED' | 'RESERVED'

export interface CarbonProjectEnergy {
  energyItemCode: string
  pathway: EnergyPathway
  capability: EnergyCapability
}
export interface CarbonProjectPathOption { code: EnergyPathway; capability: EnergyCapability; scopeType: string | null }

export interface CarbonProjectExclusion {
  energyItemCode: string
  pointIds: string[]
  reason: string
  evidence: string
}

export interface CarbonProjectConfiguration {
  classificationVersionId: string | null
  energies: CarbonProjectEnergy[]
  exclusions: CarbonProjectExclusion[]
}

export interface CarbonProjectVersion {
  versionId: string
  buildingId: string
  versionNo: number
  revision: number
  status: string
  effectiveFrom: string | null
  effectiveTo: string | null
  createdBy: number
  reviewedBy: number | null
  configuration: CarbonProjectConfiguration
}

export interface CarbonProjectReadiness {
  buildingId: string
  versionId: string
  ready: boolean
  issues: Array<{ code: string; reference: string; message: string }>
}

export interface CarbonProjectTransition {
  expectedRevision: number
  reason: string
  effectiveFrom?: string | null
}

export interface CarbonProjectImpact {
  buildingId: string
  versionId: string
  revision: number
  startInclusive: string
  endExclusive: string
  ready: boolean
  calculationStatus: 'NOT_CALCULATED'
  lines: Array<{
    energyItemCode: string
    unit: string | null
    status: 'VERIFIED' | 'UNVERIFIED' | 'MISSING' | 'RESERVED' | 'UNCONFIGURED'
    grossQuantity: string | null
    includedQuantity: string | null
    excludedQuantity: string | null
    excludedSources: unknown[]
  }>
  issues: Array<{ code: string; reference: string; message: string }>
}

const basePath = '/v1/carbon-projects/buildings'
const encoded = (value: string) => encodeURIComponent(value)
const buildingPath = (buildingId: string) => `${basePath}/${encoded(buildingId)}`
const versionPath = (buildingId: string, versionId: string) => `${buildingPath(buildingId)}/versions/${encoded(versionId)}`

export const carbonProjectApi = {
  impact(buildingId: string, versionId: string, startInclusive: string, endExclusive: string) {
    return requestApi<CarbonProjectImpact>({ method: 'get', url: `${versionPath(buildingId, versionId)}/impact`, params: { startInclusive, endExclusive } })
  },
  options(buildingId: string, signal?: AbortSignal) {
    return requestApi<CarbonProjectPathOption[]>({ method: 'get', url: `${buildingPath(buildingId)}/options`, signal })
  },
  listVersions(buildingId: string, signal?: AbortSignal) {
    return requestApi<CarbonProjectVersion[]>({ method: 'get', url: `${buildingPath(buildingId)}/versions`, signal })
  },
  getReadiness(buildingId: string, versionId: string, signal?: AbortSignal) {
    return requestApi<CarbonProjectReadiness>({ method: 'get', url: `${versionPath(buildingId, versionId)}/readiness`, signal })
  },
  createVersion(buildingId: string, reason: string, copiedFromVersionId?: string | null) {
    return requestApi<CarbonProjectVersion>({ method: 'post', url: `${buildingPath(buildingId)}/versions`, data: { reason, copiedFromVersionId: copiedFromVersionId || null } })
  },
  saveVersion(buildingId: string, versionId: string, data: { expectedRevision: number; configuration: CarbonProjectConfiguration; reason: string }) {
    return requestApi<CarbonProjectVersion>({ method: 'put', url: versionPath(buildingId, versionId), data })
  },
  transition(buildingId: string, versionId: string, action: 'submit' | 'withdraw' | 'approve' | 'reject' | 'activate', data: CarbonProjectTransition) {
    return requestApi<CarbonProjectVersion>({ method: 'post', url: `${versionPath(buildingId, versionId)}/${action}`, data })
  },
}

export interface ApprovedEnergyItem {
  itemId: string
  itemCode: string
  itemName: string
  status: string
  versionId: string
  versionNo: number
  compatibleCategory?: string
  usageScopes?: string[]
}

export const energyCatalogApi = {
  listItems(signal?: AbortSignal) {
    return requestApi<ApprovedEnergyItem[]>({ method: 'get', url: '/v1/energy-catalog/items', signal })
  },
}
