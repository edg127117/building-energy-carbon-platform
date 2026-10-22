import { computed, ref, watch } from 'vue'
import { requestErrorMessage } from '@/shared/utils/request-error'
import {
  carbonProjectApi, energyCatalogApi,
  type ApprovedEnergyItem, type CarbonProjectConfiguration, type CarbonProjectReadiness,
  type CarbonProjectVersion,
  type CarbonProjectPathOption,
} from '../api/carbon-project'

export function useCarbonProject(buildingId: () => string) {
  const versions = ref<CarbonProjectVersion[]>([])
  const selectedVersionId = ref('')
  const selectedVersion = computed(() => versions.value.find(item => item.versionId === selectedVersionId.value) ?? null)
  const catalog = ref<ApprovedEnergyItem[]>([])
  const options = ref<CarbonProjectPathOption[]>([])
  const readiness = ref<CarbonProjectReadiness | null>(null)
  const loading = ref(false)
  const saving = ref(false)
  const error = ref('')
  let generation = 0
  let mutationGeneration = 0

  async function load(target = buildingId(), preferredVersionId = selectedVersionId.value) {
    const owner = ++generation
    const targetBuildingId = target.trim()
    versions.value = []
    readiness.value = null
    catalog.value = []
    options.value = []
    selectedVersionId.value = ''
    error.value = ''
    if (!targetBuildingId) { loading.value = false; return }
    loading.value = true
    const [versionsResult, catalogResult, optionsResult] = await Promise.allSettled([
      carbonProjectApi.listVersions(targetBuildingId),
      energyCatalogApi.listItems(),
      carbonProjectApi.options(targetBuildingId),
    ])
    if (owner !== generation || targetBuildingId !== buildingId().trim()) return
    if (versionsResult.status === 'fulfilled') {
      versions.value = versionsResult.value
      selectedVersionId.value = preferredVersionId && versions.value.some(item => item.versionId === preferredVersionId)
        ? preferredVersionId
        : versions.value[0]?.versionId ?? ''
    } else error.value = requestErrorMessage(versionsResult.reason)
    if (catalogResult.status === 'fulfilled') catalog.value = catalogResult.value.filter(item => item.status === 'APPROVED')
    else if (!error.value) error.value = requestErrorMessage(catalogResult.reason)
    if (optionsResult.status === 'fulfilled') options.value = optionsResult.value
    else if (!error.value) error.value = requestErrorMessage(optionsResult.reason)
    loading.value = false
    if (selectedVersionId.value) await loadReadiness(targetBuildingId, selectedVersionId.value, owner)
  }

  async function loadReadiness(target: string, versionId: string, owner = generation) {
    try {
      const result = await carbonProjectApi.getReadiness(target, versionId)
      if (owner === generation && target === buildingId().trim() && selectedVersionId.value === versionId) readiness.value = result
    } catch (reason) {
      if (owner === generation && target === buildingId().trim() && selectedVersionId.value === versionId) error.value = requestErrorMessage(reason)
    }
  }

  function selectVersion(versionId: string) {
    selectedVersionId.value = versionId
    readiness.value = null
    const target = buildingId().trim()
    if (target && versionId) void loadReadiness(target, versionId)
  }

  async function create(reason: string) {
    const target = buildingId().trim()
    if (!target || !reason.trim()) return false
    return mutate(() => carbonProjectApi.createVersion(target, reason.trim()), target)
  }

  async function save(configuration: CarbonProjectConfiguration, reason: string) {
    const target = buildingId().trim()
    const version = selectedVersion.value
    if (!target || !version || version.status !== 'DRAFT' || !reason.trim()) return false
    const snapshot = JSON.parse(JSON.stringify(configuration)) as CarbonProjectConfiguration
    return mutate(() => carbonProjectApi.saveVersion(target, version.versionId, { expectedRevision: version.revision, configuration: snapshot, reason: reason.trim() }), target)
  }

  async function transition(action: 'submit' | 'withdraw' | 'approve' | 'reject' | 'activate', reason: string, effectiveFrom?: string) {
    const target = buildingId().trim()
    const version = selectedVersion.value
    if (!target || !version || !reason.trim()) return false
    return mutate(() => carbonProjectApi.transition(target, version.versionId, action, {
        expectedRevision: version.revision, reason: reason.trim(), ...(effectiveFrom ? { effectiveFrom } : {}),
      }), target)
  }

  async function mutate(action: () => Promise<CarbonProjectVersion>, target: string) {
    if (saving.value || loading.value) return false
    const commandOwner = ++mutationGeneration
    saving.value = true
    error.value = ''
    try {
      const result = await action()
      if (commandOwner === mutationGeneration && target === buildingId().trim()) await load(target, result.versionId)
      return true
    } catch (reason) {
      if (commandOwner === mutationGeneration && target === buildingId().trim()) error.value = requestErrorMessage(reason)
      return false
    } finally {
      if (commandOwner === mutationGeneration) saving.value = false
    }
  }

  watch(buildingId, value => { mutationGeneration++; saving.value = false; void load(value, '') }, { immediate: true })

  return { versions, selectedVersionId, selectedVersion, catalog, options, readiness, loading, saving, error, load, selectVersion, create, save, transition }
}
