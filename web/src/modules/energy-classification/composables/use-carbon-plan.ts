import { computed, onScopeDispose, ref, watch } from 'vue'
import { requestErrorMessage } from '@/shared/utils/request-error'
import { carbonPlanApi, type CarbonPlanBaseline, type CarbonPlanVersion, type CarbonPlanAction } from '../api/carbon-plan'

/** 建筑、年度、版本切换使旧响应失效；预览与写入独立计代，旧请求不覆盖新草稿。 */
export function useCarbonPlan(building: () => string, year: () => number) {
  const versions = ref<CarbonPlanVersion[]>([])
  const selectedId = ref('')
  const selected = computed(() => versions.value.find(v => v.versionId === selectedId.value) ?? null)
  const baseline = ref<CarbonPlanBaseline | null>(null)
  const reductionPercent = ref('')
  const error = ref('')
  const loading = ref(false)
  const saving = ref(false)
  const previewing = ref(false)
  let generation = 0
  let previewGeneration = 0
  let disposed = false
  const context = () => `${building()}|${year()}`
  function restore() {
    ++previewGeneration; previewing.value = false
    baseline.value = selected.value?.configuration?.baseline ?? null
    reductionPercent.value = selected.value?.configuration?.reductionPercent ?? ''
  }
  watch(selectedId, restore, { flush: 'sync' })
  async function load() {
    const owner = ++generation, target = context(), id = building(), y = year()
    ++previewGeneration
    versions.value = []; selectedId.value = ''; baseline.value = null; reductionPercent.value = ''; error.value = ''; saving.value = false; previewing.value = false
    loading.value = !!id
    if (!id) return
    try {
      const result = await carbonPlanApi.versions(id, y)
      if (disposed || owner !== generation || target !== context()) return
      versions.value = result; selectedId.value = result[0]?.versionId ?? ''; restore()
    } catch (reason) { if (!disposed && owner === generation && target === context()) error.value = requestErrorMessage(reason) }
    finally { if (!disposed && owner === generation) loading.value = false }
  }
  async function preview(request: (id: string) => Promise<CarbonPlanBaseline>) {
    if (saving.value || loading.value || !building()) return
    const owner = ++previewGeneration, target = context(), version = selectedId.value
    previewing.value = true; baseline.value = null; error.value = ''
    try {
      const result = await request(building())
      if (!disposed && owner === previewGeneration && target === context() && version === selectedId.value) baseline.value = result
    } catch (reason) { if (!disposed && owner === previewGeneration && target === context()) error.value = requestErrorMessage(reason) }
    finally { if (!disposed && owner === previewGeneration) previewing.value = false }
  }
  async function mutate(request: () => Promise<CarbonPlanVersion>) {
    if (saving.value || loading.value || previewing.value || !building()) return
    const owner = generation, target = context()
    saving.value = true; error.value = ''
    try {
      const result = await request()
      if (disposed || owner !== generation || target !== context()) return
      // 生效会使上一有效版本退役；重新读取清单，保留当前版本选择。
      const list = await carbonPlanApi.versions(building(), year())
      if (disposed || owner !== generation || target !== context()) return
      versions.value = list; selectedId.value = result.versionId; restore()
    } catch (reason) { if (!disposed && owner === generation && target === context()) error.value = requestErrorMessage(reason) }
    finally { if (!disposed && owner === generation) saving.value = false }
  }
  function create(reason: string) {
    const current = selected.value
    const copy = current && ['ACTIVE', 'RETIRED'].includes(current.status) ? current.versionId : undefined
    return mutate(() => carbonPlanApi.create(building(), year(), reason, copy))
  }
  function save(reason: string) {
    const version = selected.value, b = baseline.value, reduction = reductionPercent.value
    if (!version || !b) return Promise.resolve()
    return mutate(() => carbonPlanApi.save(building(), version, { baseline: b, reductionPercent: reduction }, reason))
  }
  function transition(action: CarbonPlanAction, reason: string) {
    const version = selected.value
    if (!version) return Promise.resolve()
    return mutate(() => carbonPlanApi.transition(building(), version, action, reason))
  }
  watch([building, year], () => { void load() }, { immediate: true, flush: 'sync' })
  onScopeDispose(() => { disposed = true; ++generation; ++previewGeneration })
  return { versions, selectedId, selected, baseline, reductionPercent, error, loading, saving, previewing, load, preview, create, save, transition, restore }
}
