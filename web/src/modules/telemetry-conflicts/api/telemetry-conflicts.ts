import { requestApi } from '@/infrastructure/http/public'
import type {
  AssignOwnerRequest,
  ConflictListQuery,
  ConflictView,
  OwnerView,
  PageResponse,
  ResolveConflictRequest,
  RecoveryTask,
  CorrectionVersion,
} from '../models/telemetry-conflicts'

const root = '/v1/telemetry-conflicts'
const conflictPath = (pointId: string, eventTime: number) => `${root}/${encodeURIComponent(pointId)}/${eventTime}`

export const telemetryConflictApi = {
  recovery(buildingId: string) {
    return requestApi<RecoveryTask[]>({ method: 'GET', url: `${root}/recovery`, params: { buildingId } })
  },
  retry(request: Pick<RecoveryTask, 'kind' | 'id'>) {
    return requestApi<void>({ method: 'POST', url: `${root}/recovery/retry`, data: request })
  },
  correctionVersions(buildingId: string, pointId: string) {
    return requestApi<CorrectionVersion[]>({ method: 'GET', url: '/v1/energy-aggregation/correction-versions', params: { buildingId, pointId } })
  },
  approveCorrection(versionId: string, request: { expectedRevision: number; reviewComment: string }) {
    return requestApi<CorrectionVersion>({ method: 'POST', url: `/v1/energy-aggregation/correction-versions/${encodeURIComponent(versionId)}/approve`, data: request })
  },
  list(query: ConflictListQuery) {
    return requestApi<PageResponse<ConflictView>>({
      method: 'GET',
      url: root,
      params: query,
    })
  },
  detail(pointId: string, eventTime: number) {
    return requestApi<ConflictView>({ method: 'GET', url: conflictPath(pointId, eventTime) })
  },
  owner(buildingId: string) {
    return requestApi<OwnerView>({ method: 'GET', url: `${root}/owners`, params: { buildingId } })
  },
  assignOwner(request: AssignOwnerRequest) {
    return requestApi<OwnerView>({ method: 'PUT', url: `${root}/owners`, data: request })
  },
  resolve(pointId: string, eventTime: number, request: ResolveConflictRequest) {
    return requestApi<ConflictView>({ method: 'POST', url: `${conflictPath(pointId, eventTime)}/resolve`, data: request })
  },
}
