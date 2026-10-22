import { requestApi, requestFile } from '@/infrastructure/http/public'
import type { ReadingImportRequest, ReadingImportView } from '../models/classification'
export interface WorkbookPreview { request: ReadingImportRequest | null; preview: ReadingImportView }
const path = (building: string) => `/v1/energy-classification/buildings/${encodeURIComponent(building)}/readings/workbook`
export const readingWorkbookApi = {
  template: (building: string) => requestFile({ method: 'get', url: `${path(building)}/template` }),
  preview: (building: string, file: File, idempotencyKey: string, reason: string) => {
    const data = new FormData(); data.append('file', file); data.append('idempotencyKey', idempotencyKey); data.append('reason', reason)
    return requestApi<WorkbookPreview>({ method: 'post', url: `${path(building)}/preview`, data })
  },
}
