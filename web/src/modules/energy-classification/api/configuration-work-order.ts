import { requestApi } from '@/infrastructure/http/public'
import type { ConfigurationGuide, ConfigurationGuideDomain } from './configuration-guide'
export interface ConfigurationWorkOrder { guide: ConfigurationGuide; fingerprint: string }
export interface WorkOrderCommand { fingerprint: string; domain: ConfigurationGuideDomain; action: 'SUBMIT' | 'APPROVE' | 'REJECT' | 'WITHDRAW' | 'ACTIVATE'; reason: string; effectiveFrom: string | null; idempotencyKey: string }
export interface WorkOrderReceipt { buildingId: string; projectVersionId: string; domain: ConfigurationGuideDomain; action: string; reference: string; state: string; revision: number }
const path = (building: string, version: string) => `/v1/carbon-projects/buildings/${encodeURIComponent(building)}/versions/${encodeURIComponent(version)}/work-order`
export const configurationWorkOrderApi = {
  get: (building: string, version: string, signal?: AbortSignal) => requestApi<ConfigurationWorkOrder>({ method: 'get', url: path(building, version), signal }),
  execute: (building: string, version: string, data: WorkOrderCommand) => requestApi<WorkOrderReceipt>({ method: 'post', url: `${path(building, version)}/commands`, data }),
}
