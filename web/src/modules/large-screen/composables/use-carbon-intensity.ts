import { computed, onScopeDispose, ref, shallowRef, watch, type Ref } from 'vue'
import { readCarbonIntensity, type CarbonIntensityView, type IntensityQuery } from '../api/carbon-intensity'
import { mapCarbonIntensity, validateCarbonIntensity, type IntensityPeriod } from '../models/carbon-intensity'

/** 建筑/期间变化立即清空旧值；即使传输层无法中止请求，也不会采纳迟到响应。 */
export function useCarbonIntensity(query: Readonly<Ref<IntensityQuery | null>>, period: Readonly<Ref<IntensityPeriod>>) {
  const result = shallowRef<CarbonIntensityView | null>(null)
  const loading = ref(false), failed = ref(false)
  let generation = 0, disposed = false
  let abort: AbortController | undefined
  async function reload() {
    const owner = ++generation, request = query.value ? { ...query.value } : null
    abort?.abort(); abort = new AbortController()
    result.value = null; failed.value = false; loading.value = false
    if (!request) return
    const signal = abort.signal
    loading.value = true
    const valid = () => !disposed && generation === owner && !signal.aborted
    try { const view = await readCarbonIntensity(request, signal); validateCarbonIntensity(view, request); if (valid()) result.value = view }
    catch { if (valid()) failed.value = true }
    finally { if (valid()) loading.value = false }
  }
  // 其他卡片切期间会重建上层对象；只有强度查询本身的参数变化才作废当前结果。
  watch([
    () => query.value?.buildingId, () => query.value?.mode, () => query.value?.year,
    () => query.value?.month, () => query.value?.timezoneId,
  ], () => { void reload() }, { immediate: true, flush: 'sync' })
  onScopeDispose(() => { disposed = true; generation++; abort?.abort() })
  return { result, loading, failed, reload, data: computed(() => result.value ? mapCarbonIntensity(result.value, period.value) : undefined) }
}
