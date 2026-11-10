import { computed, onScopeDispose, ref, shallowRef, watch, type Ref } from 'vue'
import { getTemperatureTrend, temperaturePeriodDates, temperatureTrendValues, type TemperatureTrend } from '@/modules/weather/public'
import type { ScreenPeriod } from '../models/screen-period'

/** 天气请求独立于电量；建筑/期间变化立即作废旧响应，失败不回退到原型温度。 */
export function useOutdoorTemperature(buildingId: Ref<string>, period: Ref<ScreenPeriod>) {
  const view = shallowRef<TemperatureTrend | null>(null)
  const loading = ref(false), state = ref('UNCONFIGURED')
  let generation = 0, disposed = false, abort: AbortController | undefined
  async function reload() {
    const current = ++generation
    abort?.abort(); view.value = null; loading.value = false
    const building = buildingId.value, selected = { ...period.value }
    if (!building) { state.value = 'UNCONFIGURED'; return }
    if (selected.kind === 'day') { state.value = 'UNSUPPORTED_PERIOD'; return }
    const type = selected.kind === 'year' ? 'YEAR' : 'MONTH'
    try { temperaturePeriodDates(type, selected.value) } catch { state.value = 'UNSUPPORTED_PERIOD'; return }
    abort = new AbortController(); const signal = abort.signal
    const valid = () => !disposed && generation === current && !signal.aborted
    loading.value = true; state.value = 'LOADING'
    try {
      const response = await getTemperatureTrend(building, type, selected.value, signal)
      temperatureTrendValues(response, building, type, selected.value)
      if (valid()) { view.value = response; state.value = response.status }
    } catch { if (valid()) state.value = 'FAILED' }
    finally { if (valid()) loading.value = false }
  }
  watch([buildingId, () => period.value.kind, () => period.value.value], () => { void reload() }, { immediate: true })
  onScopeDispose(() => { disposed = true; generation++; abort?.abort() })
  const values = computed(() => {
    const current = view.value, selected = period.value, type = selected.kind === 'year' ? 'YEAR' : 'MONTH'
    if (selected.kind === 'day') return undefined
    if (!current || current.buildingId !== buildingId.value || current.periodType !== type || current.period !== selected.value) return undefined
    return temperatureTrendValues(current, buildingId.value, type, selected.value)
  })
  return { view, values, loading, state, reload }
}
