import { computed, ref } from 'vue'
import { collectRelationPages, RelationCatalogueLimitError, relationGovernanceApi } from '../api/relation-governance'
import type {
  RelationAssignmentRequest,
  RelationBoundaryRequest,
  RelationMeteringAssignment,
  RelationMeteringBoundary,
  RelationMeterStructure,
  RelationModelReference,
  RelationReviewReference,
  RelationStructureRequest,
  RelationValidation,
  RelationVersionReference,
  RelationNodeCatalogueItem,
} from '../models/classification'
import { requestErrorCode, requestErrorMessage, requestErrorStatus } from '@/shared/utils/request-error'

type RequestState = { message: string; code: string | null } | null

/** 关系草稿的每次变更都携带服务端修订号；刷新后才允许下一次写入，避免覆盖并发配置。 */
export function useRelationGovernanceWorkspace() {
  const buildingId = ref('')
  const model = ref<RelationModelReference | null>(null)
  const versions = ref<RelationVersionReference[]>([])
  const reviews = ref<RelationReviewReference[]>([])
  const selectedVersionId = ref<string | null>(null)
  const structures = ref<RelationMeterStructure[]>([])
  const assignments = ref<RelationMeteringAssignment[]>([])
  const boundaries = ref<RelationMeteringBoundary[]>([])
  const nodes = ref<RelationNodeCatalogueItem[]>([])
  const validation = ref<RelationValidation | null>(null)
  const loading = ref(false)
  const detailLoading = ref(false)
  const busy = ref(false)
  const error = ref<RequestState>(null)
  const selectedVersion = computed(() => versions.value.find(item => item.versionId === selectedVersionId.value) ?? null)
  const editable = computed(() => selectedVersion.value?.status === 'DRAFT')
  const pendingReview = computed(() => reviews.value.find(item => item.versionId === selectedVersionId.value && item.status === 'PENDING') ?? null)
  let buildingGeneration = 0
  let detailGeneration = 0
  let mutationContext: { building: number; detail: number } | null = null

  async function loadBuilding(nextBuildingId: string) {
    const target = nextBuildingId.trim()
    const owner = ++buildingGeneration
    detailGeneration++
    buildingId.value = target
    model.value = null
    versions.value = []
    reviews.value = []
    selectedVersionId.value = null
    clearDetail()
    validation.value = null
    error.value = null
    if (!target) {
      loading.value = false
      return
    }
    loading.value = true
    const [modelResult, versionsResult, reviewsResult] = await Promise.allSettled([
      relationGovernanceApi.getModel(target),
      relationGovernanceApi.listVersions(target),
      relationGovernanceApi.listReviews(target),
    ])
    if (owner !== buildingGeneration || buildingId.value !== target) return
    if (modelResult.status === 'fulfilled') model.value = modelResult.value
    if (versionsResult.status === 'fulfilled') versions.value = orderedVersions(versionsResult.value)
    if (reviewsResult.status === 'fulfilled') reviews.value = reviewsResult.value
    const failure = firstUnexpectedFailure(modelResult, versionsResult, reviewsResult)
    if (failure) error.value = requestState(failure)
    loading.value = false
    const preferredVersionId = model.value?.draftVersionId ?? versions.value.find(item => item.status === 'DRAFT')?.versionId ?? model.value?.activeVersionId ?? null
    if (preferredVersionId) await selectVersion(preferredVersionId)
  }

  async function selectVersion(nextVersionId: string | null | undefined) {
    const targetBuildingId = buildingId.value
    const targetVersionId = nextVersionId?.trim() || null
    const owner = ++detailGeneration
    selectedVersionId.value = targetVersionId
    clearDetail()
    validation.value = null
    if (!targetBuildingId || !targetVersionId) return
    detailLoading.value = true
    error.value = null
    try {
      const [detail, assignmentPage, structurePage, boundaryPage, nodePage] = await Promise.all([
        relationGovernanceApi.getVersionDetail(targetBuildingId, targetVersionId),
        collectRelationPages((page, size) => relationGovernanceApi.listVersionAssignments(targetBuildingId, targetVersionId, page, size)),
        collectRelationPages((page, size) => relationGovernanceApi.listVersionStructures(targetBuildingId, targetVersionId, page, size)),
        collectRelationPages((page, size) => relationGovernanceApi.listBoundaryCatalogue(targetBuildingId, targetVersionId, page, size)),
        collectRelationPages((page, size) => relationGovernanceApi.listNodes(targetBuildingId, targetVersionId, undefined, page, size)),
      ])
      if (owner !== detailGeneration || buildingId.value !== targetBuildingId || selectedVersionId.value !== targetVersionId) return
      replaceVersion(detail.version)
      assignments.value = assignmentPage.items
      structures.value = structurePage.items
      boundaries.value = boundaryPage.items
      nodes.value = nodePage.items
    } catch (reason) {
      if (owner === detailGeneration && buildingId.value === targetBuildingId) error.value = requestState(reason)
    } finally {
      if (owner === detailGeneration && buildingId.value === targetBuildingId) detailLoading.value = false
    }
  }

  async function initialize(reason: string) {
    if (!buildingId.value || !reason.trim()) return false
    return mutate(async () => {
      const version = await relationGovernanceApi.initialize(buildingId.value, { reason: reason.trim() }, nextKey())
      await reloadBuilding(version.versionId)
    })
  }

  async function createDraft(reason: string, copiedFromVersionId: string | null) {
    if (!buildingId.value || !model.value || !reason.trim()) return false
    const snapshot = model.value
    return mutate(async () => {
      const version = await relationGovernanceApi.createVersion(buildingId.value, {
        reason: reason.trim(), copiedFromVersionId, expectedModelRevision: snapshot.modelRevision,
      }, nextKey())
      await reloadBuilding(version.versionId)
    })
  }

  async function saveBoundary(data: Omit<RelationBoundaryRequest, 'expectedRevision'>, boundaryId: string | null) {
    return writeSelected(async version => {
      if (boundaryId) await relationGovernanceApi.updateBoundary(version.versionId, boundaryId, { ...data, expectedRevision: version.revision })
      else await relationGovernanceApi.createBoundary(version.versionId, { ...data, expectedRevision: version.revision }, nextKey())
    })
  }

  async function retireBoundary(boundaryId: string) {
    return writeSelected(version => relationGovernanceApi.retireBoundary(version.versionId, boundaryId, version.revision))
  }

  async function saveStructure(data: Omit<RelationStructureRequest, 'expectedRevision'>, structureItemId: string | null) {
    return writeSelected(async version => {
      if (structureItemId) await relationGovernanceApi.updateStructure(version.versionId, structureItemId, { ...data, expectedRevision: version.revision })
      else await relationGovernanceApi.createStructure(version.versionId, { ...data, expectedRevision: version.revision }, nextKey())
    })
  }

  async function deleteStructure(structureItemId: string) {
    return writeSelected(version => relationGovernanceApi.deleteStructure(version.versionId, structureItemId, version.revision))
  }

  async function saveAssignment(data: Omit<RelationAssignmentRequest, 'expectedRevision'>, assignmentItemId: string | null) {
    return writeSelected(async version => {
      if (assignmentItemId) await relationGovernanceApi.updateAssignment(version.versionId, assignmentItemId, { ...data, expectedRevision: version.revision })
      else await relationGovernanceApi.createAssignment(version.versionId, { ...data, expectedRevision: version.revision }, nextKey())
    })
  }

  async function deleteAssignment(assignmentItemId: string) {
    return writeSelected(version => relationGovernanceApi.deleteAssignment(version.versionId, assignmentItemId, version.revision))
  }

  async function validateSelected() {
    const version = selectedVersion.value
    if (!version) return false
    return mutate(async () => {
      const result = await relationGovernanceApi.validateVersion(version.versionId)
      if (mutationCurrent()) validation.value = result
    })
  }

  async function submitSelected(reason: string) {
    return writeSelected(version => relationGovernanceApi.submitVersion(version.versionId, { reason: reason.trim(), expectedRevision: version.revision }, nextKey()))
  }

  async function withdrawSelected(reason: string) {
    const version = selectedVersion.value
    if (!version || !reason.trim()) return false
    return mutate(async () => {
      await relationGovernanceApi.withdrawVersion(version.versionId, { reason: reason.trim(), expectedRevision: version.revision })
      await reloadBuilding(null)
    })
  }

  async function approvePending(reason: string) {
    const review = pendingReview.value
    if (!review || !reason.trim()) return false
    return mutate(async () => {
      await relationGovernanceApi.approveReview(review.requestId, { reason: reason.trim() }, nextKey())
      await reloadBuilding(review.versionId)
    })
  }

  async function rejectPending(reason: string) {
    const review = pendingReview.value
    if (!review || !reason.trim()) return false
    return mutate(async () => {
      await relationGovernanceApi.rejectReview(review.requestId, { reason: reason.trim() }, nextKey())
      await reloadBuilding(null)
    })
  }

  async function activateSelected(reason: string) {
    const version = selectedVersion.value
    const currentModel = model.value
    if (!version || !currentModel || !reason.trim()) return false
    return mutate(async () => {
      await relationGovernanceApi.activateVersion(version.versionId, { reason: reason.trim(), expectedModelRevision: currentModel.modelRevision }, nextKey())
      await reloadBuilding(version.versionId)
    })
  }

  async function refreshSelected() {
    if (selectedVersionId.value) await selectVersion(selectedVersionId.value)
  }

  async function writeSelected(work: (version: RelationVersionReference) => Promise<unknown>) {
    const version = selectedVersion.value
    if (!version || !editable.value) return false
    return mutate(async () => {
      await work(version)
      if (mutationCurrent()) await selectVersion(version.versionId)
    })
  }

  async function reloadBuilding(preferredVersionId: string | null) {
    if (!mutationCurrent()) return
    const target = buildingId.value
    const owner = buildingGeneration + 1
    await loadBuilding(target)
    if (preferredVersionId && buildingGeneration === owner && buildingId.value === target) await selectVersion(preferredVersionId)
  }

  function mutationCurrent() {
    return mutationContext?.building === buildingGeneration && mutationContext?.detail === detailGeneration
  }

  async function mutate(work: () => Promise<void>) {
    if (busy.value) return false
    busy.value = true
    error.value = null
    mutationContext = { building: buildingGeneration, detail: detailGeneration }
    try {
      await work()
      return true
    } catch (reason) {
      if (mutationCurrent()) error.value = requestState(reason)
      return false
    } finally {
      busy.value = false
      mutationContext = null
    }
  }

  function replaceVersion(next: RelationVersionReference) {
    versions.value = orderedVersions([next, ...versions.value.filter(item => item.versionId !== next.versionId)])
  }

  function clearDetail() {
    structures.value = []
    assignments.value = []
    boundaries.value = []
    nodes.value = []
    detailLoading.value = false
  }

  return {
    buildingId, model, versions, reviews, selectedVersionId, selectedVersion, pendingReview, editable,
    structures, assignments, boundaries, nodes, validation, loading, detailLoading, busy, error,
    loadBuilding, selectVersion, initialize, createDraft,
    saveBoundary, retireBoundary, saveStructure, deleteStructure, saveAssignment, deleteAssignment,
    validateSelected, submitSelected, withdrawSelected, approvePending, rejectPending, activateSelected, refreshSelected,
  }
}

function orderedVersions(versions: RelationVersionReference[]): RelationVersionReference[] {
  return [...versions].sort((left, right) => right.versionNo - left.versionNo)
}

function firstUnexpectedFailure(...results: PromiseSettledResult<unknown>[]): unknown | null {
  for (const result of results) {
    if (result.status === 'rejected' && requestErrorStatus(result.reason) !== 404) return result.reason
  }
  return null
}

function requestState(reason: unknown): RequestState {
  if (reason instanceof RelationCatalogueLimitError) return { message: '', code: 'RELATION_CATALOGUE_LIMIT' }
  return { message: requestErrorMessage(reason), code: requestErrorCode(reason) }
}

function nextKey(): string {
  return globalThis.crypto?.randomUUID?.() ?? `relation-governance-${Date.now()}`
}
