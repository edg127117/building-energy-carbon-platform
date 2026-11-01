import { computed, onScopeDispose, ref, shallowRef, watch, type Ref } from 'vue'
import { readDailyTrends, type DailyTrends } from '@/modules/energy-reporting/public'
import { dailyScreenData, validateDailyTrends } from '../models/final-b-daily'
import { validScreenPeriod, type ScreenPeriod } from '../models/screen-period'
import type { ScreenRedesignData } from '../models/screen-redesign'

/** 两张卡同月共用一次读取；切换建筑、期间或销毁后，旧请求不能回填新画布。 */
export function useFinalBDaily(buildingId: Ref<string>, usage: Ref<ScreenPeriod>, mirror: Ref<ScreenPeriod>) {
  const views = shallowRef<ReadonlyMap<string, DailyTrends>>(new Map())
  const failedMonths = ref<string[]>([]), loading = ref(false)
  let generation = 0, disposed = false, controller: AbortController | undefined
  const months = computed(() => [...new Set([usage.value, mirror.value]
    .filter(p => p.kind === 'month' && validScreenPeriod(p)).map(p => p.value))].sort())
  async function reload() {
    const current = ++generation, building = buildingId.value, requested = [...months.value]
    controller?.abort(); views.value = new Map(); failedMonths.value = []; loading.value = false
    if (!building || !requested.length) return
    controller = new AbortController(); const signal = controller.signal
    const valid = () => !disposed && current === generation && !signal.aborted && building === buildingId.value
    loading.value = true
    const results = await Promise.all(requested.map(async month => {
      try {
        const view = await readDailyTrends(building, month, signal)
        validateDailyTrends(view, building, month)
        return { month, view }
      } catch { return { month, view: null } }
    }))
    if (!valid()) return
    views.value = new Map(results.filter(item => item.view !== null).map(item => [item.month, item.view!]))
    failedMonths.value = results.filter(item => item.view === null).map(item => item.month)
    loading.value = false
  }
  watch([buildingId, () => months.value.join(',')], () => { void reload() }, { immediate: true })
  onScopeDispose(() => { disposed = true; generation++; controller?.abort() })
  const data = computed<ScreenRedesignData>(() => {
    let result: ScreenRedesignData = {}
    // 月视图读取失败也清空对应月输入，绝不回用另一个期间的值或原型静态数据。
    if (usage.value.kind === 'month') result.usage = undefined
    if (mirror.value.kind === 'month') result.mirror = undefined
    for (const month of months.value) {
      const view = views.value.get(month)
      if (view?.buildingId === buildingId.value) result = { ...result, ...dailyScreenData(view, usage.value, mirror.value) }
    }
    return result
  })
  return { data, views, failedMonths, loading, reload }
}
