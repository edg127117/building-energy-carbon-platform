import { requestApi } from '@/infrastructure/http/public'

/** tCO2e十进制来自后端；前端不累计实际值、不推导配额。 */
export interface CarbonPlanBaselineMonth {
  month: number; value: string; unit: string; source: string
  batchId: string | null; evidenceHash: string | null; referenceVersionId: string | null
}
export interface CarbonPlanBaseline {
  nature: 'FORMAL_YEAR' | 'EXTERNAL_CONFIRMED' | 'TEST_FIXTURE'
  sourceYear: number | null; timezoneId: string; boundary: string; source: string
  months: CarbonPlanBaselineMonth[]; annualBaseline: string; evidenceHash: string
}
export interface CarbonPlanConfiguration { baseline: CarbonPlanBaseline; reductionPercent: string }
export interface CarbonPlanVersion {
  versionId: string; buildingId: string; year: number; versionNo: number; revision: number
  status: 'DRAFT' | 'PENDING_REVIEW' | 'APPROVED' | 'ACTIVE' | 'RETIRED'
  createdBy: number; reviewedBy: number | null; activatedBy: number | null; activatedAt: string | null
  copiedFromVersionId: string | null; configuration: CarbonPlanConfiguration | null
}
export interface CarbonPlanMonth {
  month: number; baseline: string | null; quota: string | null; actual: string | null
  actualStatus: string; batchId: string | null; evidenceHash: string | null
  referenceVersionId: string | null; startInclusive: string; endExclusive: string
}
export interface CarbonPlanResponse {
  buildingId: string; year: number; timezoneId: string; unit: string
  status: 'NO_ACTIVE_PLAN' | 'NO_COMPLETED_MONTH' | 'PERIOD_NOT_ENDED' | 'INCOMPLETE_ACTUAL' | 'COMPLETE'
  throughMonth: string | null; latestCompletedMonth: string | null; missingMonths: number[]
  plan: CarbonPlanVersion | null; annualBaseline: string | null; reductionPercent: string | null
  annualQuota: string | null; actualCumulative: string | null; remainingQuota: string | null; months: CarbonPlanMonth[]
}
export type CarbonPlanAction = 'submit' | 'withdraw' | 'approve' | 'reject' | 'activate'
const path = (building: string) => `/v1/carbon-management/buildings/${encodeURIComponent(building)}/plan`
export const carbonPlanApi = {
  query(building: string, year: number, throughMonth?: string, signal?: AbortSignal) {
    return requestApi<CarbonPlanResponse>({ method: 'get', url: path(building), params: { year, throughMonth }, signal })
  },
  versions(building: string, year: number, signal?: AbortSignal) {
    return requestApi<CarbonPlanVersion[]>({ method: 'get', url: `${path(building)}/versions`, params: { year }, signal })
  },
  baseline(building: string, sourceYear: number, signal?: AbortSignal) {
    return requestApi<CarbonPlanBaseline>({ method: 'get', url: `${path(building)}/baseline-preview`, params: { sourceYear }, signal })
  },
  importPreview(building: string, file: File, nature: 'EXTERNAL_CONFIRMED' | 'TEST_FIXTURE', source: string, boundary: string, signal?: AbortSignal) {
    const data = new FormData()
    data.append('file', file); data.append('nature', nature); data.append('source', source); data.append('boundary', boundary)
    return requestApi<CarbonPlanBaseline>({ method: 'post', url: `${path(building)}/import-preview`, data, signal })
  },
  create(building: string, year: number, reason: string, copiedFromVersionId?: string) {
    return requestApi<CarbonPlanVersion>({ method: 'post', url: `${path(building)}/versions`, data: { year, reason, copiedFromVersionId } })
  },
  save(building: string, version: CarbonPlanVersion, configuration: CarbonPlanConfiguration, reason: string) {
    return requestApi<CarbonPlanVersion>({ method: 'put', url: `${path(building)}/versions/${encodeURIComponent(version.versionId)}`, data: { expectedRevision: version.revision, configuration, reason } })
  },
  transition(building: string, version: CarbonPlanVersion, action: CarbonPlanAction, reason: string) {
    return requestApi<CarbonPlanVersion>({ method: 'post', url: `${path(building)}/versions/${encodeURIComponent(version.versionId)}/${action}`, data: { expectedRevision: version.revision, reason } })
  },
}
