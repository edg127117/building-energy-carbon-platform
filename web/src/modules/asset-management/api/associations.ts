import { requestApi } from '@/infrastructure/http/public'
export { TransportError } from '@/infrastructure/http/public'
import type { AssociationPage, RelationAction, RelationDiff, RelationModel, RelationReview, RelationValidation, RelationVersion } from '../models/associations'

const root = '/v1/relation-models'
const encoded = encodeURIComponent

/** 复用带建筑范围过滤的继承查询，不通过管理员资产接口扩大能源管理员权限。 */
export function listAssociationBuildings(page: number, keyword: string) {
  return requestApi<{ records: Array<{ buildingId: string; buildingName: string }>; total: number }>({
    method: 'get', url: '/building/list', params: { page, size: 20, keyword },
  })
}
export const getRelationModel = (buildingId: string) => requestApi<RelationModel>({ method: 'get', url: root, params: { buildingId } })
export const listRelationVersions = (buildingId: string) => requestApi<RelationVersion[]>({ method: 'get', url: `${root}/${encoded(buildingId)}/versions` })
export const listRelationReviews = (buildingId: string) => requestApi<RelationReview[]>({ method: 'get', url: `${root}/reviews`, params: { buildingId } })
export function listEquipmentAssociations(buildingId: string, params: { versionId?: string; page: number; size: number; spaceId?: string; keyword?: string; unassigned?: boolean }) {
  return requestApi<AssociationPage>({ method: 'get', url: `${root}/${encoded(buildingId)}/equipment-associations`, params })
}
export function updateEquipmentAssociation(version: RelationVersion, equipmentId: string, spaceId: string | null, systemGroupId: string | null) {
  return requestApi<RelationVersion>({ method: 'put', url: `${root}/versions/${encoded(version.versionId)}/structure/assignments`,
    data: { objectType: 'EQUIPMENT', objectId: equipmentId, spaceId, systemGroupId, equipmentId: null, expectedRevision: version.revision } })
}
export const validateRelationVersion = (versionId: string) => requestApi<RelationValidation>({ method: 'post', url: `${root}/versions/${encoded(versionId)}/validate` })
export function getRelationDiff(buildingId: string, versionId: string, againstVersionId: string) {
  return requestApi<RelationDiff>({ method: 'get', url: `${root}/${encoded(buildingId)}/versions/${encoded(versionId)}/diff`, params: { againstVersionId, sampleSize: 500 } })
}
/** 每个明确操作独立幂等键，修订号来自已读快照；冲突不自动重试覆盖他人修改。 */
export function runRelationAction(action: RelationAction, context: { buildingId: string; model: RelationModel | null; version: RelationVersion | null; review?: RelationReview }, reason: string, key: string) {
  let url: string
  let data: object = { reason }
  if (action === 'initialize') url = `${root}/${encoded(context.buildingId)}/initialize`
  else if (action === 'create') {
    url = `${root}/${encoded(context.buildingId)}/versions`
    data = { reason, expectedModelRevision: context.model!.modelRevision }
  } else if (action === 'approve' || action === 'reject') url = `${root}/review-requests/${encoded(context.review!.requestId)}/${action}`
  else {
    url = `${root}/versions/${encoded(context.version!.versionId)}/${action}`
    data = action === 'activate' ? { reason, expectedModelRevision: context.model!.modelRevision } : { reason, expectedRevision: context.version!.revision }
  }
  return requestApi<RelationVersion | RelationReview>({ method: 'post', url, data, headers: { 'Idempotency-Key': key } })
}
