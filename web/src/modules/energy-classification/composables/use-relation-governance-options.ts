import { computed, ref } from 'vue'
import { collectRelationPages, relationGovernanceApi } from '../api/relation-governance'
import type {
  RelationMeteringAssignment,
  RelationMeteringBoundary,
  RelationMeterStructure,
  RelationModelReference,
  RelationNodeCatalogueItem,
  RelationQueryMetadata,
  RelationVersionReference,
} from '../models/classification'
import { requestErrorCode, requestErrorMessage, requestErrorStatus } from '@/shared/utils/request-error'

type RequestState = { message: string; code: string | null } | null

/** 分类页面只把正式关系快照转成可选边界，不在浏览器推导表计关系或能耗。 */
export function useRelationGovernanceOptions() {
  const buildingId = ref('')
  const model = ref<RelationModelReference | null>(null)
  const versions = ref<RelationVersionReference[]>([])
  const selectedVersionId = ref<string | null>(null)
  const selectedVersion = computed(() => versions.value.find(item => item.versionId === selectedVersionId.value) ?? null)
  const formalVersions = computed(() => versions.value.filter(item => Boolean(item.effectiveAt)))
  const boundaries = ref<RelationMeteringBoundary[]>([])
  const structures = ref<RelationMeterStructure[]>([])
  const nodes = ref<RelationNodeCatalogueItem[]>([])
  const metadata = ref<RelationQueryMetadata | null>(null)
  const loading = ref(false)
  const detailLoading = ref(false)
  const modelUninitialized = ref(false)
  const error = ref<RequestState>(null)
  let buildingGeneration = 0
  let detailGeneration = 0

  async function loadBuilding(nextBuildingId: string) {
    const target = nextBuildingId.trim()
    const owner = ++buildingGeneration
    detailGeneration++
    buildingId.value = target
    model.value = null
    versions.value = []
    clearDetails()
    modelUninitialized.value = false
    error.value = null
    if (!target) {
      loading.value = false
      return
    }
    loading.value = true
    const [modelResult, versionsResult] = await Promise.allSettled([
      relationGovernanceApi.getModel(target),
      relationGovernanceApi.listVersions(target),
    ])
    if (owner !== buildingGeneration || buildingId.value !== target) return
    if (modelResult.status === 'fulfilled') model.value = modelResult.value
    if (versionsResult.status === 'fulfilled') versions.value = ordered(versionsResult.value)
    if (isUninitializedModel(modelResult, versionsResult)) {
      modelUninitialized.value = true
    } else {
      const failed = versionsResult.status === 'rejected' ? versionsResult.reason : modelResult.status === 'rejected' ? modelResult.reason : null
      if (failed) error.value = requestState(failed)
    }
    loading.value = false
  }

  async function loadSelectedVersion(nextBuildingId: string, nextVersionId: string | null) {
    const targetBuildingId = nextBuildingId.trim()
    const targetVersionId = nextVersionId?.trim() || null
    const owner = ++detailGeneration
    clearDetails()
    selectedVersionId.value = targetVersionId
    if (!targetBuildingId || !targetVersionId || buildingId.value !== targetBuildingId) return
    if (!formalVersions.value.some(item => item.versionId === targetVersionId)) return
    detailLoading.value = true
    error.value = null
    const active = model.value?.activeVersionId === targetVersionId
    try {
      if (active) {
        const [boundaryPage, structurePage, nodePage] = await Promise.all([
          collectRelationPages((page, size) => relationGovernanceApi.listEffectiveBoundaries(targetBuildingId, page, size)),
          collectRelationPages((page, size) => relationGovernanceApi.listEffectiveStructures(targetBuildingId, page, size)),
          collectRelationPages((page, size) => relationGovernanceApi.listNodes(targetBuildingId, targetVersionId, undefined, page, size)),
        ])
        if (owner === detailGeneration && buildingId.value === targetBuildingId) {
          boundaries.value = boundaryPage.items
          structures.value = structurePage.items
          nodes.value = nodePage.items
          metadata.value = structurePage.metadata
        }
      } else {
        const [assignmentPage, structurePage, nodePage] = await Promise.all([
          collectRelationPages((page, size) => relationGovernanceApi.listVersionAssignments(targetBuildingId, targetVersionId, page, size)),
          collectRelationPages((page, size) => relationGovernanceApi.listVersionStructures(targetBuildingId, targetVersionId, page, size)),
          collectRelationPages((page, size) => relationGovernanceApi.listNodes(targetBuildingId, targetVersionId, undefined, page, size)),
        ])
        if (owner === detailGeneration && buildingId.value === targetBuildingId) {
          boundaries.value = boundariesFromAssignments(assignmentPage.items)
          structures.value = structurePage.items
          nodes.value = nodePage.items
          metadata.value = structurePage.metadata
        }
      }
    } catch (reason) {
      if (owner === detailGeneration && buildingId.value === targetBuildingId) error.value = requestState(reason)
    } finally {
      if (owner === detailGeneration && buildingId.value === targetBuildingId) detailLoading.value = false
    }
  }

  function clearDetails() {
    selectedVersionId.value = null
    boundaries.value = []
    structures.value = []
    nodes.value = []
    metadata.value = null
    detailLoading.value = false
  }

  return {
    buildingId, model, versions, formalVersions, selectedVersionId, selectedVersion,
    boundaries, structures, nodes, metadata, loading, detailLoading, modelUninitialized, error,
    loadBuilding, loadSelectedVersion,
  }
}

function boundariesFromAssignments(assignments: RelationMeteringAssignment[]): RelationMeteringBoundary[] {
  const values = new Map<string, RelationMeteringBoundary>()
  for (const assignment of assignments) {
    if (!assignment.meteringBoundaryId) continue
    values.set(assignment.meteringBoundaryId, {
      boundaryId: assignment.meteringBoundaryId,
      boundaryCode: assignment.meteringBoundaryCode ?? assignment.meteringBoundaryId,
      boundaryName: assignment.meteringBoundaryName ?? assignment.meteringBoundaryId,
      energyType: assignment.energyType,
      confirmationStatus: assignment.boundaryConfirmationStatus ?? '',
      status: assignment.boundaryStatus ?? '',
    })
  }
  return [...values.values()].sort((left, right) => left.boundaryCode.localeCompare(right.boundaryCode))
}

function ordered(versions: RelationVersionReference[]): RelationVersionReference[] {
  return [...versions].sort((left, right) => right.versionNo - left.versionNo)
}

/**
 * 仅在模型读取返回稳定的“未找到”且历史版本读取成功为空时显示初始化空态，
 * 避免把权限、网络、版本或其他 404 伪装成可初始化的关系模型。
 */
function isUninitializedModel(
  modelResult: PromiseSettledResult<RelationModelReference>,
  versionsResult: PromiseSettledResult<RelationVersionReference[]>,
): boolean {
  return modelResult.status === 'rejected'
    && requestErrorStatus(modelResult.reason) === 404
    && requestErrorCode(modelResult.reason) === 'RELATION_NOT_FOUND'
    && versionsResult.status === 'fulfilled'
    && versionsResult.value.length === 0
}

function requestState(reason: unknown): RequestState {
  return { message: requestErrorMessage(reason), code: requestErrorCode(reason) }
}
