import { requestApi } from '@/infrastructure/http/public'
import type {
  ClassificationQueryRequest,
  ClassificationQueryView,
  ClassificationVersion,
  CreateVersionRequest,
  ReadingImportRequest,
  ReadingImportView,
  ReadingView,
  SaveVersionRequest,
  TransitionRequest,
} from '../models/classification'

const basePath = '/v1/energy-classification/buildings'
const encoded = (value: string) => encodeURIComponent(value)
const buildingPath = (buildingId: string) => `${basePath}/${encoded(buildingId)}`
const versionPath = (buildingId: string, versionId: string) => `${buildingPath(buildingId)}/versions/${encoded(versionId)}`

export const classificationApi = {
  listVersions(buildingId: string, signal?: AbortSignal) {
    return requestApi<ClassificationVersion[]>({ method: 'get', url: `${buildingPath(buildingId)}/versions`, signal })
  },
  getVersion(buildingId: string, versionId: string, signal?: AbortSignal) {
    return requestApi<ClassificationVersion>({ method: 'get', url: versionPath(buildingId, versionId), signal })
  },
  createVersion(buildingId: string, data: CreateVersionRequest) {
    return requestApi<ClassificationVersion>({ method: 'post', url: `${buildingPath(buildingId)}/versions`, data })
  },
  saveVersion(buildingId: string, versionId: string, data: SaveVersionRequest) {
    return requestApi<ClassificationVersion>({ method: 'put', url: versionPath(buildingId, versionId), data })
  },
  submitVersion(buildingId: string, versionId: string, data: TransitionRequest) {
    return requestApi<ClassificationVersion>({ method: 'post', url: `${versionPath(buildingId, versionId)}/submit`, data })
  },
  withdrawVersion(buildingId: string, versionId: string, data: TransitionRequest) {
    return requestApi<ClassificationVersion>({ method: 'post', url: `${versionPath(buildingId, versionId)}/withdraw`, data })
  },
  rejectVersion(buildingId: string, versionId: string, data: TransitionRequest) {
    return requestApi<ClassificationVersion>({ method: 'post', url: `${versionPath(buildingId, versionId)}/reject`, data })
  },
  approveVersion(buildingId: string, versionId: string, data: TransitionRequest) {
    return requestApi<ClassificationVersion>({ method: 'post', url: `${versionPath(buildingId, versionId)}/approve`, data })
  },
  query(buildingId: string, data: ClassificationQueryRequest, signal?: AbortSignal) {
    return requestApi<ClassificationQueryView>({ method: 'post', url: `${buildingPath(buildingId)}/query`, data, signal })
  },
  previewReadings(buildingId: string, data: ReadingImportRequest) {
    return requestApi<ReadingImportView>({ method: 'post', url: `${buildingPath(buildingId)}/readings/preview`, data })
  },
  importReadings(buildingId: string, data: ReadingImportRequest) {
    return requestApi<ReadingImportView>({ method: 'post', url: `${buildingPath(buildingId)}/readings/import`, data })
  },
  approveReadingBatch(buildingId: string, batchId: string, data: TransitionRequest) {
    return requestApi<ReadingImportView>({ method: 'post', url: `${buildingPath(buildingId)}/readings/batches/${encoded(batchId)}/approve`, data })
  },
  listReadings(buildingId: string, params: { startInclusive: string; endExclusive: string }, signal?: AbortSignal) {
    return requestApi<ReadingView[]>({ method: 'get', url: `${buildingPath(buildingId)}/readings`, params, signal })
  },
}
