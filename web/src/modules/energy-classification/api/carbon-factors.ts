import { requestApi } from '@/infrastructure/http/public'

export type CarbonFactorScope = 'SCOPE_1' | 'SCOPE_2'
export type CarbonFactorCategory = 'STATIONARY_COMBUSTION' | 'PURCHASED_ELECTRICITY_LOCATION' | 'PURCHASED_HEAT'
export type CarbonFactorApplicability = 'BUILDING_SPECIFIC' | 'PROVINCE' | 'GRID_REGION' | 'NATIONAL' | 'NOT_REGION_SPECIFIC'
export type CarbonFactorUsageNature = 'DEVELOPMENT_REFERENCE' | 'FORMAL'
export type CarbonFactorStatus = 'PENDING_REVIEW' | 'APPROVED' | 'ACTIVE' | 'DISABLED' | 'REJECTED'
export type CarbonFactorComponentType = 'LOWER_HEATING_VALUE' | 'CARBON_CONTENT_PER_HEAT' | 'OXIDATION_RATE' | 'DIRECT_EMISSION_FACTOR'

export interface CarbonFactorSource {
  sourceId: string
  sourceCode: string
  sourceVersionId: string
  versionNo: number
  sourceName: string
  publisher: string
  documentReference: string
  publicationYear: number | null
  publishedOn: string | null
  applicabilityNote: string
  evidenceReference: string
  usageNature: CarbonFactorUsageNature
  createdBy: number
  createdAt: string
}

export interface CarbonFactorComponent {
  componentId: string
  componentType: CarbonFactorComponentType
  value: string | number
  unit: string
  sourceVersionId: string
  evidenceReference: string
}

export interface CarbonFactorVersion {
  factorId: string
  factorCode: string
  factorVersionId: string
  versionNo: number
  scopeType: CarbonFactorScope
  energyItemCode: string
  factorCategory: CarbonFactorCategory
  resultBasis: string
  gasCode: string
  gasCoverage: string
  sourceVersionId: string
  applicabilityLevel: CarbonFactorApplicability
  buildingId: string | null
  regionCode: string | null
  inputUnitCode: string
  standardConditionCode: string | null
  usageNature: CarbonFactorUsageNature
  status: CarbonFactorStatus
  effectiveFrom: string
  effectiveTo: string | null
  formulaVersionId: string
  roundingPolicyVersionId: string
  configRevision: number
  createdBy: number
  createdAt: string
  reviewedBy: number | null
  reviewedAt: string | null
  reviewComment: string | null
  activatedBy: number | null
  activatedAt: string | null
  components: CarbonFactorComponent[]
  dataYear: number | null
  accountingYear: number | null
}

export interface CarbonEnergyItemOption {
  itemId: string
  itemCode: string
  versionId: string
  versionNo: number
  itemName: string
  compatibleCategory: string | null
  usageScopes: string[]
  status: string
}

export interface CarbonEnergyUnitOption {
  unitId: string
  unitCode: string
  versionId: string
  versionNo: number
  symbol: string
  unitName: string
  dimensionCode: string
  precision: number
  status: string
}

export interface CarbonEnergyCompatibilityOption {
  compatibilityId: string
  versionId: string
  versionNo: number
  energyItemCode: string
  unitCode: string
  allowed: boolean
  status: string
}

export interface CarbonFormulaOption {
  versionId: string
  code: string
  versionNo: number
  algorithmCode: string
  resultBasis: string
  usageNature: CarbonFactorUsageNature
  effectiveFrom: string
  effectiveTo: string | null
}

export interface CarbonRoundingPolicyOption {
  versionId: string
  code: string
  versionNo: number
  totalScale: number
  intensityScale: number
  shareScale: number
  roundingMode: string
}

export interface CarbonRuleOptions {
  formulas: CarbonFormulaOption[]
  roundingPolicies: CarbonRoundingPolicyOption[]
}

export interface CreateCarbonFactorSourceRequest {
  sourceCode: string
  sourceName: string
  publisher: string
  documentReference: string
  publicationYear: number | null
  publishedOn: string | null
  applicabilityNote: string
  evidenceReference: string
  usageNature: CarbonFactorUsageNature
}

export interface CreateCarbonFactorComponentRequest {
  componentType: CarbonFactorComponentType
  value: string
  unit: string
  sourceVersionId: string
  evidenceReference: string
}

export interface CreateCarbonFactorVersionRequest {
  factorCode: string
  scopeType: CarbonFactorScope
  energyItemCode: string
  factorCategory: CarbonFactorCategory
  resultBasis: string
  gasCode: string
  gasCoverage: string
  sourceVersionId: string
  applicabilityLevel: CarbonFactorApplicability
  buildingId: string | null
  regionCode: string | null
  inputUnitCode: string
  standardConditionCode: string | null
  usageNature: CarbonFactorUsageNature
  effectiveFrom: string
  effectiveTo: string | null
  formulaVersionId: string
  roundingPolicyVersionId: string
  components: CreateCarbonFactorComponentRequest[]
  dataYear: number | null
  accountingYear: number | null
}

export interface CarbonFactorReviewRequest {
  expectedRevision: number
  approved: boolean
  reviewComment: string
}

export interface CarbonFactorLifecycleRequest {
  expectedRevision: number
}

const basePath = '/v1/carbon-management'
const encoded = (value: string) => encodeURIComponent(value)
const factorPath = (versionId: string) => `${basePath}/factors/${encoded(versionId)}`

/** 只映射已公开的碳规则、能源目录和单位目录契约；不在前端补造公式或舍入配置。 */
export const carbonFactorsApi = {
  listSources(signal?: AbortSignal) {
    return requestApi<CarbonFactorSource[]>({ method: 'get', url: `${basePath}/factor-sources`, signal })
  },
  createSource(data: CreateCarbonFactorSourceRequest) {
    return requestApi<CarbonFactorSource>({ method: 'post', url: `${basePath}/factor-sources`, data })
  },
  listFactors(signal?: AbortSignal) {
    return requestApi<CarbonFactorVersion[]>({ method: 'get', url: `${basePath}/factors`, signal })
  },
  getRuleOptions(signal?: AbortSignal) {
    return requestApi<CarbonRuleOptions>({ method: 'get', url: `${basePath}/rule-options`, signal })
  },
  createFactor(data: CreateCarbonFactorVersionRequest) {
    return requestApi<CarbonFactorVersion>({ method: 'post', url: `${basePath}/factors`, data })
  },
  reviewFactor(versionId: string, data: CarbonFactorReviewRequest) {
    return requestApi<CarbonFactorVersion>({ method: 'post', url: `${factorPath(versionId)}/review`, data })
  },
  activateFactor(versionId: string, data: CarbonFactorLifecycleRequest) {
    return requestApi<CarbonFactorVersion>({ method: 'post', url: `${factorPath(versionId)}/activate`, data })
  },
  disableFactor(versionId: string, data: CarbonFactorLifecycleRequest) {
    return requestApi<CarbonFactorVersion>({ method: 'post', url: `${factorPath(versionId)}/disable`, data })
  },
  listEnergyItems(signal?: AbortSignal) {
    return requestApi<CarbonEnergyItemOption[]>({ method: 'get', url: '/v1/energy-catalog/items', signal })
  },
  listEnergyUnits(signal?: AbortSignal) {
    return requestApi<CarbonEnergyUnitOption[]>({ method: 'get', url: '/v1/energy-catalog/units', signal })
  },
  listEnergyCompatibilities(signal?: AbortSignal) {
    return requestApi<CarbonEnergyCompatibilityOption[]>({ method: 'get', url: '/v1/energy-catalog/compatibilities', signal })
  },
}
