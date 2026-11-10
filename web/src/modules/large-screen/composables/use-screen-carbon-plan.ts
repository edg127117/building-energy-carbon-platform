import { computed, onScopeDispose, ref, watch } from 'vue'
import { carbonPlanApi, type CarbonPlanResponse } from '@/modules/energy-classification/public'
import { requestErrorMessage } from '@/shared/utils/request-error'
import { adaptCarbonPlan } from '../models/carbon-plan-adapter'

/** 年/月均保留全年序列；切换建筑或期间立即清除旧结果并拒绝迟到响应。 */
export function useScreenCarbonPlan(building: () => string, year: () => number, throughMonth: () => string | undefined) {
  const response = ref<CarbonPlanResponse | null>(null)
  const loading = ref(false)
  const error = ref('')
  let generation = 0
  let controller: AbortController | undefined
  const key = () => `${building()}|${year()}|${throughMonth() ?? ''}`
  async function refresh() {
    const owner = ++generation, target = key()
    controller?.abort(); controller = new AbortController()
    response.value = null; error.value = ''; loading.value = !!building()
    if (!building()) return
    try {
      const result = await carbonPlanApi.query(building(), year(), throughMonth(), controller.signal)
      if (owner === generation && target === key()) response.value = result
    } catch (reason) { if (owner === generation && target === key()) error.value = requestErrorMessage(reason) }
    finally { if (owner === generation) loading.value = false }
  }
  watch([building, year, throughMonth], () => { void refresh() }, { immediate: true, flush: 'sync' })
  onScopeDispose(() => { ++generation; controller?.abort() })
  return { response, data: computed(() => response.value ? adaptCarbonPlan(response.value) : null), loading, error, refresh }
}
