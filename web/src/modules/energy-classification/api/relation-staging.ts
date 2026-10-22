import { requestApi } from '@/infrastructure/http/public'

export interface StagedAsset {
  stagedId: string; objectType: 'EQUIPMENT' | 'POINT'; objectId: string; nodeId: string
  code: string; name: string; status: 'STAGED' | 'PUBLISHED'; definitionJson: string
  spaceId: string | null; systemGroupId: string | null; equipmentId: string | null
}
export interface StagedMutation { versionId: string; revision: number; assets: StagedAsset[] }
export interface StagingOption { id: string; name: string; code: string }
export interface StagingOptions { equipmentTypes: StagingOption[]; products: StagingOption[]; namingRules: StagingOption[] }
export interface StagingBase { expectedRevision: number; reason: string; name: string }
export interface StagedEquipmentRequest extends StagingBase {
  typeCode: string; productId: string | null; manufacturer: string | null; spaceId: string | null; systemGroupId: string | null
}
export interface StagedPointRequest extends StagingBase {
  namingRuleId: string; pointCode: string | null; suffixCode: string | null; equipmentId: string | null
  systemGroupId: string | null; dataType: string; unit: string | null; isForCalc: 0 | 1; status: 'ONLINE' | 'OFFLINE'
}
const path = (building: string, version: string) => `/v1/relation-models/${encodeURIComponent(building)}/versions/${encodeURIComponent(version)}/staged-assets`
export const relationStagingApi = {
  list: (building: string, version: string) => requestApi<StagedMutation>({ method: 'get', url: path(building, version) }),
  options: (building: string, version: string) => requestApi<StagingOptions>({ method: 'get', url: `${path(building, version)}/options` }),
  equipment: (building: string, version: string, data: StagedEquipmentRequest) => requestApi<StagedMutation>({ method: 'post', url: `${path(building, version)}/equipment`, data }),
  point: (building: string, version: string, data: StagedPointRequest) => requestApi<StagedMutation>({ method: 'post', url: `${path(building, version)}/points`, data }),
  remove: (building: string, version: string, id: string, expectedRevision: number, reason: string) => requestApi<StagedMutation>({ method: 'delete', url: `${path(building, version)}/${encodeURIComponent(id)}`, data: { expectedRevision, reason } }),
  assignment: (version: string, data: { objectType: string; objectId: string; spaceId: string | null; systemGroupId: string | null; equipmentId: string | null; expectedRevision: number }) => requestApi({ method: 'put', url: `/v1/relation-models/versions/${encodeURIComponent(version)}/structure/assignments`, data }),
}
