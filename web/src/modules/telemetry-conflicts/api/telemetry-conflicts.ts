import { requestApi } from '@/infrastructure/http/public'
import type {
  AssignOwnerRequest,
  ConflictListQuery,
  ConflictView,
  OwnerView,
  PageResponse,
  ResolveConflictRequest,
} from '../models/telemetry-conflicts'

const root = '/v1/telemetry-conflicts'
const conflictPath = (pointId: string, eventTime: number) => `${root}/${encodeURIComponent(pointId)}/${eventTime}`

export const telemetryConflictApi = {
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
