import { onScopeDispose, ref, watch } from 'vue'
import { readAnnualOverview, validateAnnualOverview, type AnnualOverview, type AnnualQuery } from '../api/annual-overview'

/** 建筑或期间切换立即清空旧读数；取消与序号共同阻止迟到响应覆盖新查询。 */
export function useAnnualOverview(query: () => AnnualQuery | null) {
  const result = ref<AnnualOverview | null>(null)
  const loading = ref(false)
  const error = ref<unknown>(null)
  let generation = 0
  let controller: AbortController | undefined
  async function reload() {
    const owner = ++generation
    controller?.abort()
    result.value = null
    error.value = null
    const target = query()
    loading.value = target !== null
    if (!target) return
    const snapshot = { ...target }
    controller = new AbortController()
    try {
      const response = await readAnnualOverview(snapshot, controller.signal)
      if (owner !== generation) return
      validateAnnualOverview(response, snapshot)
      result.value = response
    } catch (reason) {
      if (owner === generation) error.value = reason
    } finally {
      if (owner === generation) loading.value = false
    }
  }
  watch(query, reload, { immediate: true, deep: true })
  onScopeDispose(() => { ++generation; controller?.abort() })
  return { result, loading, error, reload }
}
