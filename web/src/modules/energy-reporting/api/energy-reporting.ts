import { requestApi, requestFile } from '@/infrastructure/http/public'
import type {
  CreateReportingBatchRequest,
  CreateReportingSourceRequest,
  ReportingBatch,
  ReportingBatchTransition,
  ReportingBatchTransitionRequest,
  ReportingContext,
  ReportingPreview,
  ReportingSource,
  ReportingSourceTransition,
  ReportingSourceTransitionRequest,
  ReportingWorkbookMappingRequest,
  ReportingWorkbookPreview,
  UpdateReportingBatchRequest,
  UpdateReportingSourceRequest,
} from '../models/reporting'

const encoded = (value: string) => encodeURIComponent(value)
const basePath = '/v1/energy-reporting/buildings'
const buildingPath = (buildingId: string) => `${basePath}/${encoded(buildingId)}`
const sourcePath = (buildingId: string, sourceId: string) => `${buildingPath(buildingId)}/sources/${encoded(sourceId)}`
const batchPath = (buildingId: string, batchId: string) => `${buildingPath(buildingId)}/batches/${encoded(batchId)}`

export const energyReportingApi = {
  context(buildingId: string, month: string, signal?: AbortSignal) {
    return requestApi<ReportingContext>({ method: 'get', url: `${buildingPath(buildingId)}/context`, params: { month }, signal })
  },
  createSource(buildingId: string, data: CreateReportingSourceRequest) {
    return requestApi<ReportingSource>({ method: 'post', url: `${buildingPath(buildingId)}/sources`, data })
  },
  updateSource(buildingId: string, sourceId: string, data: UpdateReportingSourceRequest) {
    return requestApi<ReportingSource>({ method: 'put', url: sourcePath(buildingId, sourceId), data })
  },
  transitionSource(buildingId: string, sourceId: string, action: ReportingSourceTransition, data: ReportingSourceTransitionRequest) {
    return requestApi<ReportingSource>({ method: 'post', url: `${sourcePath(buildingId, sourceId)}/${action}`, data })
  },
  getBatch(buildingId: string, batchId: string, signal?: AbortSignal) {
    return requestApi<ReportingBatch>({ method: 'get', url: batchPath(buildingId, batchId), signal })
  },
  createBatch(buildingId: string, data: CreateReportingBatchRequest) {
    return requestApi<ReportingBatch>({ method: 'post', url: `${buildingPath(buildingId)}/batches`, data })
  },
  updateBatch(buildingId: string, batchId: string, data: UpdateReportingBatchRequest) {
    return requestApi<ReportingBatch>({ method: 'put', url: batchPath(buildingId, batchId), data })
  },
  previewBatch(buildingId: string, batchId: string) {
    return requestApi<ReportingPreview>({ method: 'post', url: `${batchPath(buildingId, batchId)}/preview` })
  },
  transitionBatch(buildingId: string, batchId: string, action: ReportingBatchTransition, data: ReportingBatchTransitionRequest) {
    return requestApi<ReportingBatch>({ method: 'post', url: `${batchPath(buildingId, batchId)}/${action}`, data })
  },
  workbookTemplate(buildingId: string, batchId: string) {
    return requestFile({ method: 'get', url: `${batchPath(buildingId, batchId)}/workbook-template` })
  },
  previewWorkbook(buildingId: string, batchId: string, file: File, mapping?: ReportingWorkbookMappingRequest) {
    const data = new FormData()
    data.append('file', file)
    if (mapping) data.append('mapping', JSON.stringify(mapping))
    return requestApi<ReportingWorkbookPreview>({ method: 'post', url: `${batchPath(buildingId, batchId)}/workbook-preview`, data })
  },
}
