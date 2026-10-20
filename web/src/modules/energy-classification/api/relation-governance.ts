import { requestApi } from '@/infrastructure/http/public'
import type {
  RelationMeteringAssignment,
  RelationMeteringBoundary,
  RelationMeterStructure,
  RelationBoundaryCatalogue,
  RelationAssignmentRequest,
  RelationBoundaryRequest,
  RelationCreateVersionRequest,
  RelationModelReference,
  RelationNodeCatalogue,
  RelationPage,
  RelationReasonRequest,
  RelationRevisionReasonRequest,
  RelationReviewReference,
  RelationStructureRequest,
  RelationValidation,
  RelationVersionDetail,
  RelationVersionReference,
} from '../models/classification'

const basePath = '/v1/relation-models'
const encoded = (value: string) => encodeURIComponent(value)
const buildingPath = (buildingId: string) => `${basePath}/${encoded(buildingId)}`
const versionPath = (versionId: string) => `${basePath}/versions/${encoded(versionId)}`
const cataloguePageSize = 100

export class RelationCatalogueLimitError extends Error {}

/** 分类只读取既有关系治理快照；关系草稿的写入仍由原治理工作区处理。 */
export const relationGovernanceApi = {
  getModel(buildingId: string) {
    return requestApi<RelationModelReference>({ method: 'get', url: basePath, params: { buildingId } })
  },
  listVersions(buildingId: string) {
    return requestApi<RelationVersionReference[]>({ method: 'get', url: `${buildingPath(buildingId)}/versions` })
  },
  getVersionDetail(buildingId: string, versionId: string) {
    return requestApi<RelationVersionDetail>({ method: 'get', url: `${buildingPath(buildingId)}/versions/${encoded(versionId)}` })
  },
  listReviews(buildingId: string) {
    return requestApi<RelationReviewReference[]>({ method: 'get', url: `${basePath}/reviews`, params: { buildingId } })
  },
  listEffectiveBoundaries(buildingId: string, page = 1, size = cataloguePageSize) {
    return requestApi<RelationPage<RelationMeteringBoundary>>({ method: 'get', url: `${buildingPath(buildingId)}/effective/metering-boundaries`, params: { page, size } })
  },
  listEffectiveStructures(buildingId: string, page = 1, size = cataloguePageSize) {
    return requestApi<RelationPage<RelationMeterStructure>>({ method: 'get', url: `${buildingPath(buildingId)}/effective/metering-structures`, params: { page, size } })
  },
  listVersionAssignments(buildingId: string, versionId: string, page = 1, size = cataloguePageSize) {
    return requestApi<RelationPage<RelationMeteringAssignment>>({ method: 'get', url: `${buildingPath(buildingId)}/versions/${encoded(versionId)}/query/metering-assignments`, params: { page, size } })
  },
  listVersionStructures(buildingId: string, versionId: string, page = 1, size = cataloguePageSize) {
    return requestApi<RelationPage<RelationMeterStructure>>({ method: 'get', url: `${buildingPath(buildingId)}/versions/${encoded(versionId)}/query/metering-structures`, params: { page, size } })
  },
  listBoundaryCatalogue(buildingId: string, versionId: string, page = 1, size = cataloguePageSize) {
    return requestApi<RelationBoundaryCatalogue>({ method: 'get', url: `${buildingPath(buildingId)}/versions/${encoded(versionId)}/metering/boundaries`, params: { page, size } })
  },
  listNodes(buildingId: string, versionId: string, nodeType?: string, page = 1, size = cataloguePageSize) {
    return requestApi<RelationNodeCatalogue>({ method: 'get', url: `${buildingPath(buildingId)}/versions/${encoded(versionId)}/nodes`, params: { nodeType, page, size } })
  },
  initialize(buildingId: string, data: RelationReasonRequest, idempotencyKey: string) {
    return requestApi<RelationVersionReference>({ method: 'post', url: `${buildingPath(buildingId)}/initialize`, data, headers: { 'Idempotency-Key': idempotencyKey } })
  },
  createVersion(buildingId: string, data: RelationCreateVersionRequest, idempotencyKey: string) {
    return requestApi<RelationVersionReference>({ method: 'post', url: `${buildingPath(buildingId)}/versions`, data, headers: { 'Idempotency-Key': idempotencyKey } })
  },
  createBoundary(versionId: string, data: RelationBoundaryRequest, idempotencyKey: string) {
    return requestApi<RelationMeteringBoundary>({ method: 'post', url: `${versionPath(versionId)}/metering/boundaries`, data, headers: { 'Idempotency-Key': idempotencyKey } })
  },
  updateBoundary(versionId: string, boundaryId: string, data: RelationBoundaryRequest) {
    return requestApi<RelationMeteringBoundary>({ method: 'put', url: `${versionPath(versionId)}/metering/boundaries/${encoded(boundaryId)}`, data })
  },
  retireBoundary(versionId: string, boundaryId: string, expectedRevision: number) {
    return requestApi<void>({ method: 'delete', url: `${versionPath(versionId)}/metering/boundaries/${encoded(boundaryId)}`, params: { expectedRevision } })
  },
  createStructure(versionId: string, data: RelationStructureRequest, idempotencyKey: string) {
    return requestApi<RelationMeterStructure>({ method: 'post', url: `${versionPath(versionId)}/metering/structures`, data, headers: { 'Idempotency-Key': idempotencyKey } })
  },
  updateStructure(versionId: string, structureItemId: string, data: RelationStructureRequest) {
    return requestApi<RelationMeterStructure>({ method: 'put', url: `${versionPath(versionId)}/metering/structures/${encoded(structureItemId)}`, data })
  },
  deleteStructure(versionId: string, structureItemId: string, expectedRevision: number) {
    return requestApi<void>({ method: 'delete', url: `${versionPath(versionId)}/metering/structures/${encoded(structureItemId)}`, params: { expectedRevision } })
  },
  createAssignment(versionId: string, data: RelationAssignmentRequest, idempotencyKey: string) {
    return requestApi<string>({ method: 'post', url: `${versionPath(versionId)}/metering/assignments`, data, headers: { 'Idempotency-Key': idempotencyKey } })
  },
  updateAssignment(versionId: string, assignmentItemId: string, data: RelationAssignmentRequest) {
    return requestApi<string>({ method: 'put', url: `${versionPath(versionId)}/metering/assignments/${encoded(assignmentItemId)}`, data })
  },
  deleteAssignment(versionId: string, assignmentItemId: string, expectedRevision: number) {
    return requestApi<void>({ method: 'delete', url: `${versionPath(versionId)}/metering/assignments/${encoded(assignmentItemId)}`, params: { expectedRevision } })
  },
  validateVersion(versionId: string) {
    return requestApi<RelationValidation>({ method: 'post', url: `${versionPath(versionId)}/validate` })
  },
  submitVersion(versionId: string, data: RelationRevisionReasonRequest, idempotencyKey: string) {
    return requestApi<RelationReviewReference>({ method: 'post', url: `${versionPath(versionId)}/submit`, data, headers: { 'Idempotency-Key': idempotencyKey } })
  },
  withdrawVersion(versionId: string, data: RelationRevisionReasonRequest) {
    return requestApi<RelationVersionReference>({ method: 'post', url: `${versionPath(versionId)}/withdraw`, data })
  },
  approveReview(requestId: string, data: RelationReasonRequest, idempotencyKey: string) {
    return requestApi<RelationReviewReference>({ method: 'post', url: `${basePath}/review-requests/${encoded(requestId)}/approve`, data, headers: { 'Idempotency-Key': idempotencyKey } })
  },
  rejectReview(requestId: string, data: RelationReasonRequest, idempotencyKey: string) {
    return requestApi<RelationReviewReference>({ method: 'post', url: `${basePath}/review-requests/${encoded(requestId)}/reject`, data, headers: { 'Idempotency-Key': idempotencyKey } })
  },
  activateVersion(versionId: string, data: { expectedModelRevision: number; reason: string }, idempotencyKey: string) {
    return requestApi<RelationVersionReference>({ method: 'post', url: `${versionPath(versionId)}/activate`, data, headers: { 'Idempotency-Key': idempotencyKey } })
  },
}

/** 分页目录超出治理上限时中止显示，禁止悄悄丢弃可配置对象。 */
export async function collectRelationPages<T>(loadPage: (page: number, size: number) => Promise<RelationPage<T>>): Promise<RelationPage<T>> {
  const first = await loadPage(1, cataloguePageSize)
  if (first.total > 5000) throw new RelationCatalogueLimitError()
  const pages = Math.ceil(first.total / Math.max(first.size, 1))
  if (pages <= 1) return first
  const remainder = await Promise.all(Array.from({ length: pages - 1 }, (_, index) => loadPage(index + 2, first.size)))
  const items = [first.items, ...remainder.map(item => item.items)].flat()
  if (items.length < first.total) throw new RelationCatalogueLimitError()
  return { ...first, items }
}
