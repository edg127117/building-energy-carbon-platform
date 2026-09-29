import { requestApi } from '@/infrastructure/http/public'
import type { MeterCandidateQuery, MeterCoveragePage, MeterCoverageUpdate, MeterCoverageView, MeterTarget } from '../models/meter-coverage'

const basePath = '/v1/assets'
const encoded = (value: string) => encodeURIComponent(value)

export function getMeterCoverage(equipmentId: string) {
  return requestApi<MeterCoverageView>({ method: 'get', url: `${basePath}/equipment/${encoded(equipmentId)}/meter-coverage` })
}

export function updateMeterCoverage(equipmentId: string, data: MeterCoverageUpdate) {
  return requestApi<MeterCoverageView>({ method: 'put', url: `${basePath}/equipment/${encoded(equipmentId)}/meter-coverage`, data })
}

export function listMeterCoverageHistory(equipmentId: string, params: { page: number; size: number }) {
  return requestApi<MeterCoveragePage<MeterCoverageView>>({ method: 'get', url: `${basePath}/equipment/${encoded(equipmentId)}/meter-coverage/history`, params })
}

export function listMeterCoverageCandidates(equipmentId: string, params: MeterCandidateQuery) {
  return requestApi<MeterCoveragePage<MeterTarget>>({ method: 'get', url: `${basePath}/equipment/${encoded(equipmentId)}/meter-coverage/candidates`, params })
}

export function listMeterCoverages(params: { buildingId?: string; equipmentIds: string[] }) {
  return requestApi<MeterCoverageView[]>({
    method: 'get',
    url: `${basePath}/meter-coverages`,
    params: { buildingId: params.buildingId, equipmentIds: params.equipmentIds.join(',') },
  })
}
