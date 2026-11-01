import { computed, onScopeDispose, ref, shallowRef, watch, type Ref } from 'vue'
import { queryJointFlow, type JointFlowView } from '@/modules/energy-classification/public'
import type { ScreenPeriod } from '../models/screen-period'
import type { ScreenRedesignData } from '../models/screen-redesign'
import { jointFlowRequest, screenJointFlow, validateJointFlow } from '../models/joint-flow'

export function useJointFlow(buildingId: Ref<string>, period: Ref<ScreenPeriod>, configurationVersionId?: string | null) {
  const view = shallowRef<JointFlowView | null>(null)
  const state = ref('NOT_STARTED'), loading = ref(false)
  let generation = 0, disposed = false
  let abort: AbortController | undefined

  async function reload(): Promise<void> {
    const current = ++generation
    abort?.abort()
    view.value = null
    loading.value = false
    if (!buildingId.value) {
      state.value = 'NOT_STARTED'
      return
    }
    const request = jointFlowRequest(buildingId.value, period.value, configurationVersionId ?? undefined)
    if (!request) {
      state.value = 'NOT_STARTED'
      return
    }
    abort = new AbortController()
    const signal = abort.signal
    loading.value = true
    state.value = 'LOADING'
    const valid = () => !disposed && generation === current && !signal.aborted
    try {
      const response = await queryJointFlow(request, signal)
      validateJointFlow(response, request)
      // 先确认两张图都属于当前正式配置；换建筑、期间或卸载后，旧响应绝不能回填到大屏。
      screenJointFlow(response.carbon, 'tCO2e', response.dimensions)
      screenJointFlow(response.energy, 'tce', response.dimensions)
      if (valid()) {
        view.value = response
        state.value = response.state
      }
    } catch {
      if (valid()) state.value = 'FAILED'
    } finally {
      if (valid()) loading.value = false
    }
  }

  watch([buildingId, () => period.value.kind, () => period.value.value], () => { void reload() }, { immediate: true })
  onScopeDispose(() => { disposed = true; generation++; abort?.abort() })
  const data = computed<ScreenRedesignData>(() => {
    const unavailable: ScreenRedesignData = { carbonFlow: undefined, energyFlow: undefined }
    if (!view.value || view.value.buildingId !== buildingId.value || view.value.period !== period.value.value
      || view.value.periodType !== (period.value.kind === 'year' ? 'YEAR' : 'MONTH')) return unavailable
    const carbon = screenJointFlow(view.value.carbon, 'tCO2e', view.value.dimensions)
    const energy = screenJointFlow(view.value.energy, 'tce', view.value.dimensions)
    return {
      carbonFlow: carbon ? { period: period.value, data: carbon } : undefined,
      energyFlow: energy ? { period: period.value, data: energy } : undefined,
    }
  })
  return { view, state, loading, data, reload }
}
