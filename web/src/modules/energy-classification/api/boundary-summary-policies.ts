import { requestApi } from '@/infrastructure/http/public'

export interface BoundarySummaryPolicy {
  policyId: string
  versionId: string
  versionNo: number
  buildingId: string
  meteringBoundaryId: string
  energyItemCode: string
  aggregationMode: string
  status: string
  sourceType: 'FORMAL' | 'SIMULATION'
  evidenceReference: string
  effectiveFrom: string
  effectiveTo: string | null
  configRevision: number
  createdBy: number
  createdAt: string
  approvedBy: number | null
  approvedAt: string | null
  reviewComment: string | null
}

export interface CreateBoundarySummaryPolicyRequest {
  buildingId: string
  meteringBoundaryId: string
  energyItemCode: string
  aggregationMode: 'MAIN_METER_TOTAL' | 'SUBMETER_SUM' | 'INDEPENDENT_METER_SUM' | 'MAIN_WITH_SUBMETER_BREAKDOWN'
  sourceType: 'FORMAL' | 'SIMULATION'
  evidenceReference: string
  effectiveFrom: string
  effectiveTo: string | null
}

export interface ApproveBoundarySummaryPolicyRequest {
  expectedRevision: number
  reviewComment: string
}

const basePath = '/v1/energy-boundary-summaries/policies'
const encoded = (value: string) => encodeURIComponent(value)

/** 页面只选择服务端目录中的边界与能源品种，口径、独立审核和版本冲突仍由后端裁决。 */
export const boundarySummaryPoliciesApi = {
  list(buildingId: string, signal?: AbortSignal) {
    return requestApi<BoundarySummaryPolicy[]>({ method: 'get', url: basePath, params: { buildingId }, signal })
  },
  create(data: CreateBoundarySummaryPolicyRequest) {
    return requestApi<BoundarySummaryPolicy>({ method: 'post', url: basePath, data })
  },
  approve(versionId: string, data: ApproveBoundarySummaryPolicyRequest) {
    return requestApi<BoundarySummaryPolicy>({ method: 'post', url: `${basePath}/${encoded(versionId)}/approve`, data })
  },
}
