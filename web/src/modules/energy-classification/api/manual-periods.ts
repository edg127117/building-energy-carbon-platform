import { requestApi } from '@/infrastructure/http/public'

export interface ManualPeriodPolicy {
  policyId: string
  versionId: string
  versionNo: number
  buildingId: string
  timezoneId: string
  closingDelayHours: number
  lockMode: string
  status: 'PENDING_REVIEW' | 'APPROVED' | 'DISABLED'
  sourceType: string
  evidenceReference: string
  effectiveFrom: string
  effectiveTo: string | null
  configRevision: number
  createdBy: number
  createdAt: string
  approvedBy: number | null
  approvedAt: string | null
}

export interface ManualRelationVersion {
  versionId: string
  modelId: string
  buildingId: string
  versionNo: number
  baseVersionId: string | null
  copiedFromVersionId: string | null
  status: string
  revision: number
  submittedRevision: number | null
  snapshotSha256: string | null
  changeReason: string | null
  createdBy: number | null
  submittedBy: number | null
  approvedBy: number | null
  activatedBy: number | null
  createdAt: string
  effectiveAt: string | null
}

export interface ManualReading {
  pointId: string
  startInclusive: string
  endExclusive: string
  energyItemCode: string
  unit: string
  semantics: string
  startReading: string | null
  endReading: string | null
  quantity: string | null
  multiplier: string | null
  alreadyScaled: boolean
  status: string
  sourceReference: string | null
  timePrecision: string
  supersedesFactId: string | null
}

export interface ManualReadingView {
  factId: string
  batchId: string
  reading: ManualReading
  normalizedQuantity: string
  createdBy: number
  batchStatus: string
}

export interface ManualProjectionReference {
  projectionId: string
  revision: number
  status: string
  quantity: string
  unit: string
  nature: string
}

export interface ManualPendingLock {
  requestId: string
  projectionId: string
  projectionRevision: number
  submittedBy: number
  status: string
}

export interface ManualPeriodContext {
  policies: ManualPeriodPolicy[]
  relations: ManualRelationVersion[]
  readings: ManualReadingView[]
  projection: ManualProjectionReference | null
  pendingLocks: ManualPendingLock[]
}

export interface ManualMonthRequest {
  pointId: string
  month: string
  policyVersionId: string
  relationVersionId: string
  factIds: string[]
  expectedProjectionRevision: number
  reason: string
}

export interface ManualMonthCandidate {
  projectionId: string | null
  revision: number
  buildingId: string
  pointId: string
  startInclusive: string
  endExclusive: string
  quantity: string
  unit: string
  evidenceHash: string
  status: 'PREVIEW_ONLY' | 'PENDING_INDEPENDENT_LOCK'
}

export interface CreateFormalPeriodPolicyRequest {
  buildingId: string
  timezoneId: string
  closingDelayHours: number
  lockMode: 'REVIEW_REQUIRED'
  sourceType: 'FORMAL'
  evidenceReference: string
  effectiveFrom: string
  effectiveTo: string | null
}

export interface ApprovePeriodPolicyRequest {
  expectedRevision: number
  reviewComment: string
}

export interface SubmitLockRequest {
  projectionId: string
  expectedRevision: number
  reason: string
  evidenceReference: string
}

export interface LockRequestView {
  requestId: string
  projectionId: string
  projectionRevision: number
  buildingId: string
  status: string
  issuePolicyVersions: string[]
  reason: string
  evidenceReference: string
  submittedBy: number
  submittedAt: string
  reviewedBy: number | null
  reviewedAt: string | null
  reviewComment: string | null
}

export interface ApproveLockRequest {
  reviewComment: string
}

export interface ManualLockSnapshot {
  snapshotId: string
  projectionId: string
  buildingId: string
  pointId: string
  nativeQuantityStatus: string
  tceStatus: string
}

const encoded = (value: string) => encodeURIComponent(value)
const manualPath = (buildingId: string) => `/v1/energy-periods/buildings/${encoded(buildingId)}/manual-months`

/** 人工月量只转发后端所需的来源、口径和版本证据；浏览器不推导原生用量或封账状态。 */
export const manualPeriodsApi = {
  context(buildingId: string, pointId: string, month: string, policyVersionId?: string, signal?: AbortSignal) {
    return requestApi<ManualPeriodContext>({
      method: 'get', url: `${manualPath(buildingId)}/context`,
      params: { pointId, month, ...(policyVersionId ? { policyVersionId } : {}) }, signal,
    })
  },
  preview(buildingId: string, data: ManualMonthRequest) {
    return requestApi<ManualMonthCandidate>({ method: 'post', url: `${manualPath(buildingId)}/preview`, data })
  },
  createCandidate(buildingId: string, data: ManualMonthRequest) {
    return requestApi<ManualMonthCandidate>({ method: 'post', url: manualPath(buildingId), data })
  },
  createFormalPolicy(data: CreateFormalPeriodPolicyRequest) {
    return requestApi<ManualPeriodPolicy>({ method: 'post', url: '/v1/energy-periods/policies', data })
  },
  approvePolicy(versionId: string, data: ApprovePeriodPolicyRequest) {
    return requestApi<ManualPeriodPolicy>({ method: 'post', url: `/v1/energy-periods/policies/${encoded(versionId)}/approve`, data })
  },
  submitLock(data: SubmitLockRequest) {
    return requestApi<LockRequestView>({ method: 'post', url: '/v1/energy-periods/lock-requests', data })
  },
  approveLock(requestId: string, data: ApproveLockRequest) {
    return requestApi<ManualLockSnapshot>({ method: 'post', url: `/v1/energy-periods/lock-requests/${encoded(requestId)}/approve`, data })
  },
}
