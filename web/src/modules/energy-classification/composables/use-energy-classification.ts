import { ref } from 'vue'
import { classificationApi } from '../api/classification'
import { cloneConfiguration, emptyConfiguration, type ClassificationConfiguration, type ClassificationQueryRequest, type ClassificationQueryView, type ClassificationVersion, type CreateVersionRequest, type ReadingImportRequest, type ReadingImportView, type ReadingView } from '../models/classification'
import { requestErrorCode, requestErrorMessage } from '@/shared/utils/request-error'

type RequestState = { message: string; code: string | null } | null

/**
 * 分类版本、统计和人工读数分别维护代次。迟到响应不得跨建筑或覆盖较新的请求；前端只呈现后端给出的数量。
 */
export function useEnergyClassification() {
  const buildingId = ref('')
  const versions = ref<ClassificationVersion[]>([])
  const selectedVersion = ref<ClassificationVersion | null>(null)
  const configuration = ref<ClassificationConfiguration>(emptyConfiguration())
  const versionsLoading = ref(false)
  const versionsError = ref<RequestState>(null)
  const versionLoading = ref(false)
  const versionError = ref<RequestState>(null)
  const commandLoading = ref(false)
  const commandError = ref<RequestState>(null)
  const queryResult = ref<ClassificationQueryView | null>(null)
  const queryLoading = ref(false)
  const queryError = ref<RequestState>(null)
  const readingPreview = ref<ReadingImportView | null>(null)
  const importedBatch = ref<ReadingImportView | null>(null)
  const importLoading = ref(false)
  const importError = ref<RequestState>(null)
  const readings = ref<ReadingView[]>([])
  const readingsLoading = ref(false)
  const readingsError = ref<RequestState>(null)

  let buildingGeneration = 0
  let versionGeneration = 0
  let queryGeneration = 0
  let readingsGeneration = 0
  let importGeneration = 0

  async function loadBuilding(nextBuildingId: string) {
    const target = nextBuildingId.trim()
    const owner = ++buildingGeneration
    buildingId.value = target
    const versionOwner = ++versionGeneration
    queryGeneration++
    readingsGeneration++
    importGeneration++
    versions.value = []
    selectedVersion.value = null
    configuration.value = emptyConfiguration()
    versionsError.value = null
    versionError.value = null
    queryResult.value = null
    queryError.value = null
    readings.value = []
    readingsError.value = null
    readingPreview.value = null
    importedBatch.value = null
    importError.value = null
    importLoading.value = false
    if (!target) {
      versionsLoading.value = false
      return []
    }
    versionsLoading.value = true
    try {
      const result = await classificationApi.listVersions(target)
      if (owner === buildingGeneration && buildingId.value === target) {
        versions.value = orderedVersions(result)
        if (versions.value[0] && versionGeneration === versionOwner) acceptVersion(versions.value[0], target)
      }
      return result
    } catch (reason) {
      if (owner === buildingGeneration && buildingId.value === target) versionsError.value = requestState(reason)
      return []
    } finally {
      if (owner === buildingGeneration) versionsLoading.value = false
    }
  }

  async function selectVersion(versionId: string) {
    const targetBuildingId = buildingId.value
    const targetVersionId = versionId.trim()
    const owner = ++versionGeneration
    clearQuery()
    if (!targetBuildingId || !targetVersionId) {
      selectedVersion.value = null
      configuration.value = emptyConfiguration()
      return null
    }
    versionLoading.value = true
    versionError.value = null
    try {
      const result = await classificationApi.getVersion(targetBuildingId, targetVersionId)
      if (owner === versionGeneration && buildingId.value === targetBuildingId) acceptVersion(result, targetBuildingId)
      return result
    } catch (reason) {
      if (owner === versionGeneration && buildingId.value === targetBuildingId) versionError.value = requestState(reason)
      return null
    } finally {
      if (owner === versionGeneration) versionLoading.value = false
    }
  }

  async function createVersion(data: CreateVersionRequest) {
    const targetBuildingId = buildingId.value
    const selectionOwner = versionGeneration
    if (!targetBuildingId || commandLoading.value) return null
    return runCommand(async () => {
      const result = await classificationApi.createVersion(targetBuildingId, data)
      if (canApplyCommand(targetBuildingId, selectionOwner)) acceptVersion(result, targetBuildingId)
      return result
    }, () => canApplyCommand(targetBuildingId, selectionOwner))
  }

  async function saveSelectedVersion(reason: string) {
    const version = selectedVersion.value
    const targetBuildingId = buildingId.value
    const selectionOwner = versionGeneration
    if (!version || !targetBuildingId || commandLoading.value) return null
    return runCommand(async () => {
      const result = await classificationApi.saveVersion(targetBuildingId, version.versionId, {
        expectedRevision: version.revision,
        configuration: cloneConfiguration(configuration.value),
        reason: reason.trim(),
      })
      if (canApplyCommand(targetBuildingId, selectionOwner, version.versionId)) acceptVersion(result, targetBuildingId)
      return result
    }, () => canApplyCommand(targetBuildingId, selectionOwner, version.versionId))
  }

  async function submitSelectedVersion(reason: string) {
    const version = selectedVersion.value
    const targetBuildingId = buildingId.value
    const selectionOwner = versionGeneration
    if (!version || !targetBuildingId || commandLoading.value) return null
    return runCommand(async () => {
      const result = await classificationApi.submitVersion(targetBuildingId, version.versionId, {
        expectedRevision: version.revision,
        reason: reason.trim(),
        effectiveFrom: null,
      })
      if (canApplyCommand(targetBuildingId, selectionOwner, version.versionId)) acceptVersion(result, targetBuildingId)
      return result
    }, () => canApplyCommand(targetBuildingId, selectionOwner, version.versionId))
  }

  async function withdrawSelectedVersion(reason: string) {
    const version = selectedVersion.value
    const targetBuildingId = buildingId.value
    const selectionOwner = versionGeneration
    if (!version || !targetBuildingId || commandLoading.value) return null
    return runCommand(async () => {
      const result = await classificationApi.withdrawVersion(targetBuildingId, version.versionId, {
        expectedRevision: version.revision,
        reason: reason.trim(),
        effectiveFrom: null,
      })
      if (canApplyCommand(targetBuildingId, selectionOwner, version.versionId)) acceptVersion(result, targetBuildingId)
      return result
    }, () => canApplyCommand(targetBuildingId, selectionOwner, version.versionId))
  }

  async function rejectSelectedVersion(reason: string) {
    const version = selectedVersion.value
    const targetBuildingId = buildingId.value
    const selectionOwner = versionGeneration
    if (!version || !targetBuildingId || commandLoading.value) return null
    return runCommand(async () => {
      const result = await classificationApi.rejectVersion(targetBuildingId, version.versionId, {
        expectedRevision: version.revision,
        reason: reason.trim(),
        effectiveFrom: null,
      })
      if (canApplyCommand(targetBuildingId, selectionOwner, version.versionId)) acceptVersion(result, targetBuildingId)
      return result
    }, () => canApplyCommand(targetBuildingId, selectionOwner, version.versionId))
  }

  async function approveSelectedVersion(reason: string, effectiveFrom: string) {
    const version = selectedVersion.value
    const targetBuildingId = buildingId.value
    const selectionOwner = versionGeneration
    if (!version || !targetBuildingId || commandLoading.value) return null
    return runCommand(async () => {
      const result = await classificationApi.approveVersion(targetBuildingId, version.versionId, {
        expectedRevision: version.revision,
        reason: reason.trim(),
        effectiveFrom: effectiveFrom.trim() || null,
      })
      if (canApplyCommand(targetBuildingId, selectionOwner, version.versionId)) acceptVersion(result, targetBuildingId)
      return result
    }, () => canApplyCommand(targetBuildingId, selectionOwner, version.versionId))
  }

  async function runQuery(data: ClassificationQueryRequest) {
    const targetBuildingId = buildingId.value
    const owner = ++queryGeneration
    const selectionOwner = versionGeneration
    const selectedId = selectedVersion.value?.versionId ?? null
    if (!targetBuildingId) return null
    queryLoading.value = true
    queryError.value = null
    queryResult.value = null
    try {
      const result = await classificationApi.query(targetBuildingId, data)
      if (owner === queryGeneration && selectionOwner === versionGeneration && selectedId === selectedVersion.value?.versionId && buildingId.value === targetBuildingId) queryResult.value = result
      return result
    } catch (reason) {
      if (owner === queryGeneration && selectionOwner === versionGeneration && selectedId === selectedVersion.value?.versionId && buildingId.value === targetBuildingId) queryError.value = requestState(reason)
      return null
    } finally {
      if (owner === queryGeneration) queryLoading.value = false
    }
  }

  async function previewReadingImport(data: ReadingImportRequest) {
    const targetBuildingId = buildingId.value
    if (!targetBuildingId || importLoading.value) return null
    const owner = ++importGeneration
    importLoading.value = true
    importError.value = null
    readingPreview.value = null
    try {
      const result = await classificationApi.previewReadings(targetBuildingId, data)
      if (owner === importGeneration && buildingId.value === targetBuildingId) readingPreview.value = result
      return result
    } catch (reason) {
      if (owner === importGeneration && buildingId.value === targetBuildingId) importError.value = requestState(reason)
      return null
    } finally {
      if (owner === importGeneration && buildingId.value === targetBuildingId) importLoading.value = false
    }
  }

  async function importReadings(data: ReadingImportRequest) {
    const targetBuildingId = buildingId.value
    if (!targetBuildingId || importLoading.value) return null
    const owner = ++importGeneration
    importLoading.value = true
    importError.value = null
    try {
      const result = await classificationApi.importReadings(targetBuildingId, data)
      if (owner === importGeneration && buildingId.value === targetBuildingId) importedBatch.value = result
      return result
    } catch (reason) {
      if (owner === importGeneration && buildingId.value === targetBuildingId) importError.value = requestState(reason)
      return null
    } finally {
      if (owner === importGeneration && buildingId.value === targetBuildingId) importLoading.value = false
    }
  }

  async function approveImportedBatch(reason: string) {
    const targetBuildingId = buildingId.value
    const batch = importedBatch.value
    if (!targetBuildingId || !batch || commandLoading.value) return null
    return runCommand(async () => {
      const detail = await classificationApi.getReadingBatch(targetBuildingId, batch.batchId)
      if (detail.status !== 'PENDING_REVIEW') return null
      const result = await classificationApi.approveReadingBatch(targetBuildingId, batch.batchId, {
        expectedRevision: detail.revision,
        reason: reason.trim(),
        effectiveFrom: null,
      })
      if (buildingId.value === targetBuildingId) importedBatch.value = result
      return result
    })
  }

  async function loadReadings(params: { startInclusive: string; endExclusive: string }) {
    const targetBuildingId = buildingId.value
    const owner = ++readingsGeneration
    if (!targetBuildingId) return []
    readingsLoading.value = true
    readingsError.value = null
    readings.value = []
    try {
      const result = await classificationApi.listReadings(targetBuildingId, params)
      if (owner === readingsGeneration && buildingId.value === targetBuildingId) readings.value = result
      return result
    } catch (reason) {
      if (owner === readingsGeneration && buildingId.value === targetBuildingId) readingsError.value = requestState(reason)
      return []
    } finally {
      if (owner === readingsGeneration) readingsLoading.value = false
    }
  }

  function clearReadingPreview() {
    importGeneration++
    readingPreview.value = null
    importError.value = null
    importLoading.value = false
  }

  function acceptVersion(version: ClassificationVersion, targetBuildingId: string) {
    if (buildingId.value !== targetBuildingId) return
    versions.value = orderedVersions([version, ...versions.value.filter(item => item.versionId !== version.versionId)])
    selectedVersion.value = version
    configuration.value = cloneConfiguration(version.configuration)
    clearQuery()
  }

  async function runCommand<T>(work: () => Promise<T>, stillCurrent: () => boolean = () => true): Promise<T | null> {
    commandLoading.value = true
    commandError.value = null
    try {
      return await work()
    } catch (reason) {
      if (stillCurrent()) commandError.value = requestState(reason)
      return null
    } finally {
      commandLoading.value = false
    }
  }

  function clearQuery() {
    queryGeneration++
    queryResult.value = null
    queryError.value = null
    queryLoading.value = false
  }

  function canApplyCommand(targetBuildingId: string, selectionOwner: number, selectedId?: string): boolean {
    return buildingId.value === targetBuildingId
      && versionGeneration === selectionOwner
      && (selectedId === undefined || selectedVersion.value?.versionId === selectedId)
  }

  return {
    buildingId, versions, selectedVersion, configuration,
    versionsLoading, versionsError, versionLoading, versionError, commandLoading, commandError,
    queryResult, queryLoading, queryError,
    readingPreview, importedBatch, importLoading, importError, readings, readingsLoading, readingsError,
    loadBuilding, selectVersion, createVersion, saveSelectedVersion, submitSelectedVersion, withdrawSelectedVersion, rejectSelectedVersion, approveSelectedVersion,
    runQuery, previewReadingImport, importReadings, approveImportedBatch, loadReadings, clearReadingPreview,
  }
}

function orderedVersions(versions: ClassificationVersion[]): ClassificationVersion[] {
  return [...versions].sort((left, right) => right.versionNo - left.versionNo)
}

function requestState(reason: unknown): RequestState {
  return { message: requestErrorMessage(reason), code: requestErrorCode(reason) }
}
