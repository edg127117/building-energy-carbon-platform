import { requestApi } from '@/infrastructure/http/public'

export interface CalculationBatch {
  batchId: string
  buildingId: string
  status: string
  periodType: string
  periodStart: string
  periodEnd: string
  timezoneId: string
  publicationStatus: string
  resultNature: string
  idempotencyKey: string
  completedAt: string | null
  safeErrorCode: string | null
  safeErrorMessage: string | null
  createdAt: string | null
}

export interface RunCalculationRequest {
  buildingId: string
  periodType: 'MONTH' | 'QUARTER' | 'YEAR'
  startInclusive: string
  endExclusive: string
  timezoneId: string
  resultNature: 'FORMAL'
  idempotencyKey: string
}

export interface CalculationDetail {
  calculationBatchId: string
  buildingId: string
  periodType: string
  startInclusive: string
  endExclusive: string
  timezoneId: string
  resultNature: string
  publicationStatus: string
  status: string
  idempotencyKey: string
  supersedesCalculationBatchId: string | null
  startedAt: string | null
  completedAt: string | null
  durationMs: number | null
  snapshotCount: number
  detailCount: number
  slowCalculation: boolean
  safeErrorCode: string | null
  safeErrorMessage: string | null
  items: CalculationItem[]
  failures: CalculationFailure[]
  summaries: CalculationSummary[]
  requestHash: string
  createdBy: number
  roundingPolicyVersionId: string
}

export interface CalculationItem {
  calculationItemId: string
  snapshotId: string
  energyItemCode: string
  scopeType: string
  activityQuantity: string
  activityUnitCode: string
  factorVersionId: string
  formulaVersionId: string
  gwpVersionId: string | null
  emissionKgCo2e: string
  matchReason: string
  evidenceHash: string
  evidenceUrl: string
}

export interface CalculationFailure {
  snapshotId: string
  energyItemCode: string
  startInclusive: string
  endExclusive: string
  errorCode: string
  errorMessage: string
  activityEvidenceHash: string
}

export interface CalculationSummary {
  metricCode: string
  dimensionCode: string
  rawValue: string | null
  finalValue: string | null
  unitCode: string
  denominatorVersionId: string | null
  unavailableReason: string | null
  evidenceHash: string
}

export interface ManualRecalculationRequest {
  buildingId: string
  accountingYear: number
  resultNature: 'FORMAL' | 'DEVELOPMENT_SIMULATION'
  reason: string
  organizationBoundary: string
}

export interface ManualRecalculationAccepted {
  changeId: string
  status: string
  buildingId: string
  accountingYear: number
}

export interface RecalculationItem {
  recalculationItemId: string
  buildingId: string
  accountingYear: number
  oldCalculationBatchId: string
  candidateCalculationBatchId: string | null
  status: string
  approvalEligible: boolean
  retryCount: number
  nextAttemptAt: string | null
  safeErrorCode: string | null
  safeErrorMessage: string | null
}

export interface RecalculationBatch {
  recalculationBatchId: string
  triggerReason: string
  organizationBoundary: string
  resultNature: string
  status: string
  scopeFrozen: boolean
  itemCount: number
  eligibleItemCount: number
  initiatedBy: number | null
  approvedBy: number | null
  approvedAt: string | null
  reviewComment: string | null
  safeErrorCode: string | null
  items: RecalculationItem[]
}

export interface RecalculationReviewRequest {
  reviewComment: string
}

export interface CarbonClassificationReport {
  buildingId: string
  batchId: string
  status: string
  unit: 'KG_CO2E'
  totalKgCo2e: string | null
  classificationComplete: boolean
  nodes: Array<{
    schemeCode: string
    classificationVersionId: string
    nodeCode: string
    nodeName: string
    energyItemCode: string
    activityUnit: string
    additive: boolean
    activityQuantity: string
    emissionKgCo2e: string
  }>
  periods: Array<{ startInclusive: string; scopeType: string; emissionKgCo2e: string }>
  excludedSources: unknown[]
  warnings: string[]
  flows: Array<{
    schemeCode: string
    available: boolean
    unavailableReason: string | null
    nodes: Array<{ id: string; type: string; code: string; name: string }>
    links: Array<{ source: string; target: string; value: string }>
  }>
}

const basePath = '/v1/carbon-management/calculations'
const encoded = (value: string) => encodeURIComponent(value)

export const carbonResultsApi = {
  listBatches(buildingId: string, signal?: AbortSignal) {
    return requestApi<CalculationBatch[]>({ method: 'get', url: basePath, params: { buildingId }, signal })
  },
  runCalculation(data: RunCalculationRequest) {
    return requestApi<CalculationDetail>({ method: 'post', url: basePath, data })
  },
  getCalculation(batchId: string, signal?: AbortSignal) {
    return requestApi<CalculationDetail>({ method: 'get', url: `${basePath}/${encoded(batchId)}`, signal })
  },
  getClassificationReport(batchId: string, signal?: AbortSignal) {
    return requestApi<CarbonClassificationReport>({ method: 'get', url: `${basePath}/${encoded(batchId)}/classification-report`, signal })
  },
  getEvidence(batchId: string, itemId: string, signal?: AbortSignal) {
    return requestApi<unknown>({ method: 'get', url: `${basePath}/${encoded(batchId)}/items/${encoded(itemId)}/evidence`, signal })
  },
  submitManualRecalculation(data: ManualRecalculationRequest) {
    return requestApi<ManualRecalculationAccepted>({ method: 'post', url: '/v1/carbon-management/recalculations/manual', data })
  },
  listRecalculations(buildingId: string, signal?: AbortSignal) {
    return requestApi<RecalculationBatch[]>({ method: 'get', url: '/v1/carbon-management/recalculations', params: { buildingId }, signal })
  },
  getRecalculation(batchId: string, signal?: AbortSignal) {
    return requestApi<RecalculationBatch>({ method: 'get', url: `/v1/carbon-management/recalculations/${encoded(batchId)}`, signal })
  },
  approveRecalculation(batchId: string, data: RecalculationReviewRequest) {
    return requestApi<RecalculationBatch>({ method: 'post', url: `/v1/carbon-management/recalculations/${encoded(batchId)}/approve`, data })
  },
  rejectRecalculation(batchId: string, data: RecalculationReviewRequest) {
    return requestApi<RecalculationBatch>({ method: 'post', url: `/v1/carbon-management/recalculations/${encoded(batchId)}/reject`, data })
  },
}
