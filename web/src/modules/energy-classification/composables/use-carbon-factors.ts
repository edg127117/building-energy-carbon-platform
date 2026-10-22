import { computed, getCurrentInstance, onBeforeUnmount, ref, watch } from 'vue'
import { requestErrorMessage } from '@/shared/utils/request-error'
import {
  carbonFactorsApi,
  type CarbonEnergyCompatibilityOption,
  type CarbonEnergyItemOption,
  type CarbonEnergyUnitOption,
  type CarbonFactorLifecycleRequest,
  type CarbonFactorReviewRequest,
  type CarbonFactorSource,
  type CarbonFactorVersion,
  type CarbonRuleOptions,
  type CreateCarbonFactorSourceRequest,
  type CreateCarbonFactorVersionRequest,
} from '../api/carbon-factors'

export function useCarbonFactors(buildingId: () => string) {
  const sources = ref<CarbonFactorSource[]>([])
  const factors = ref<CarbonFactorVersion[]>([])
  const energyItems = ref<CarbonEnergyItemOption[]>([])
  const energyUnits = ref<CarbonEnergyUnitOption[]>([])
  const energyCompatibilities = ref<CarbonEnergyCompatibilityOption[]>([])
  const ruleOptions = ref<CarbonRuleOptions>({ formulas: [], roundingPolicies: [] })
  const loading = ref(false)
  const saving = ref(false)
  const error = ref('')
  let generation = 0
  let mutationGeneration = 0
  let activeRequest: AbortController | null = null

  const approvedEnergyItems = computed(() => energyItems.value.filter(item => item.status === 'APPROVED'))
  const approvedEnergyUnits = computed(() => energyUnits.value.filter(item => item.status === 'APPROVED'))
  const approvedEnergyCompatibilities = computed(() => energyCompatibilities.value
    .filter(item => item.status === 'APPROVED' && item.allowed))

  // 因子清单不按建筑过滤，但切换建筑仍使旧读取和写入后的刷新失效，避免旧上下文覆盖当前选择。
  async function load(target = buildingId().trim()): Promise<void> {
    const owner = ++generation
    activeRequest?.abort()
    const controller = new AbortController()
    activeRequest = controller
    loading.value = true
    error.value = ''

    const [sourcesResult, factorsResult, ruleOptionsResult, itemsResult, unitsResult, compatibilitiesResult] = await Promise.allSettled([
      carbonFactorsApi.listSources(controller.signal),
      carbonFactorsApi.listFactors(controller.signal),
      carbonFactorsApi.getRuleOptions(controller.signal),
      carbonFactorsApi.listEnergyItems(controller.signal),
      carbonFactorsApi.listEnergyUnits(controller.signal),
      carbonFactorsApi.listEnergyCompatibilities(controller.signal),
    ])
    if (owner !== generation || target !== buildingId().trim()) return

    if (sourcesResult.status === 'fulfilled') sources.value = sourcesResult.value
    if (factorsResult.status === 'fulfilled') factors.value = factorsResult.value
    if (ruleOptionsResult.status === 'fulfilled') ruleOptions.value = ruleOptionsResult.value
    if (itemsResult.status === 'fulfilled') energyItems.value = itemsResult.value
    if (unitsResult.status === 'fulfilled') energyUnits.value = unitsResult.value
    if (compatibilitiesResult.status === 'fulfilled') energyCompatibilities.value = compatibilitiesResult.value
    const rejected = [sourcesResult, factorsResult, ruleOptionsResult, itemsResult, unitsResult, compatibilitiesResult]
      .find(result => result.status === 'rejected')
    if (rejected?.status === 'rejected') error.value = requestErrorMessage(rejected.reason)
    if (owner === generation) {
      loading.value = false
      activeRequest = null
    }
  }

  async function createSource(request: CreateCarbonFactorSourceRequest): Promise<boolean> {
    return mutate(() => carbonFactorsApi.createSource(request))
  }

  async function createFactor(request: CreateCarbonFactorVersionRequest): Promise<boolean> {
    return mutate(() => carbonFactorsApi.createFactor(request))
  }

  async function reviewFactor(versionId: string, request: CarbonFactorReviewRequest): Promise<boolean> {
    return mutate(() => carbonFactorsApi.reviewFactor(versionId, request))
  }

  async function activateFactor(versionId: string, request: CarbonFactorLifecycleRequest): Promise<boolean> {
    return mutate(() => carbonFactorsApi.activateFactor(versionId, request))
  }

  async function disableFactor(versionId: string, request: CarbonFactorLifecycleRequest): Promise<boolean> {
    return mutate(() => carbonFactorsApi.disableFactor(versionId, request))
  }

  async function mutate(action: () => Promise<unknown>): Promise<boolean> {
    if (loading.value || saving.value) return false
    const owner = ++mutationGeneration
    const target = buildingId().trim()
    saving.value = true
    error.value = ''
    try {
      await action()
      if (owner !== mutationGeneration || target !== buildingId().trim()) return false
      await load(target)
      return owner === mutationGeneration && target === buildingId().trim()
    } catch (reason) {
      if (owner === mutationGeneration && target === buildingId().trim()) error.value = requestErrorMessage(reason)
      return false
    } finally {
      if (owner === mutationGeneration) saving.value = false
    }
  }

  watch(buildingId, () => {
    mutationGeneration++
    void load()
  }, { immediate: true })
  if (getCurrentInstance()) {
    onBeforeUnmount(() => {
      generation++
      mutationGeneration++
      activeRequest?.abort()
    })
  }

  return {
    sources, factors, energyItems, energyUnits, energyCompatibilities, ruleOptions,
    approvedEnergyItems, approvedEnergyUnits, approvedEnergyCompatibilities,
    loading, saving, error, load, createSource, createFactor, reviewFactor, activateFactor, disableFactor,
  }
}
