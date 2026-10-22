import { requestApi } from '@/infrastructure/http/public'

export interface EnergyProfileView {
  profileId: string
  pointId: string
  pointCode: string
  buildingId: string
  energyType: string
  energySubtype: string | null
  valueSemantics: string
  unit: string
  reportingPeriod: string
  annualSummary: boolean
  confirmationStatus: string
  evidenceReference: string
  configRevision: number
  createBy: number | null
  updateBy: number | null
  createTime: string
  updateTime: string
}

export interface EnergyProfilePage {
  page: number
  size: number
  total: number
  items: EnergyProfileView[]
}

export interface EnergyProfileOptions {
  energyTypes: string[]
  electricitySubtypes: string[]
  valueSemantics: string[]
  reportingPeriods: string[]
  confirmationStatuses: string[]
  supportedUnits: string[]
}

export interface EnergyProfileBatchRow {
  pointId: string
  profileId: string | null
  expectedRevision: number | null
}

export interface EnergyProfileBatchRequest {
  buildingId: string
  rows: EnergyProfileBatchRow[]
  energyType: string
  energySubtype: string | null
  valueSemantics: string
  reportingPeriod: string
  annualSummary: boolean
  confirmationStatus: string
  evidenceReference: string
  preview: boolean
}

export interface EnergyProfileBatchView {
  preview: boolean
  count: number
  profiles: EnergyProfileView[]
}

const basePath = '/v1/energy-point-profiles'

export const energyProfileBatchApi = {
  listProfiles(buildingId: string, page: number, size: number, pointId?: string, signal?: AbortSignal) {
    return requestApi<EnergyProfilePage>({
      method: 'get', url: basePath,
      params: { buildingId, page, size, ...(pointId ? { pointId } : {}) }, signal,
    })
  },
  options(signal?: AbortSignal) {
    return requestApi<EnergyProfileOptions>({ method: 'get', url: `${basePath}/options`, signal })
  },
  batch(data: EnergyProfileBatchRequest) {
    return requestApi<EnergyProfileBatchView>({ method: 'post', url: `${basePath}/batch`, data })
  },
}
