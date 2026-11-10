import { computed, onScopeDispose, ref, shallowRef, watch, type Ref } from 'vue'
import { readDailyTrends, type DailyTrends } from '@/modules/energy-reporting/public'
import { dailyScreenData, validateDailyTrends } from '../models/final-b-daily'
import { validScreenPeriod, type ScreenPeriod } from '../models/screen-period'
import type { ScreenRedesignData } from '../models/screen-redesign'

const REFRESH_INTERVAL_MS = 5_000

/** 两卡共享当前所选月；每五秒复查来源，在途不重启，准备中或失败时撤下旧月数据。 */
export function useFinalBDaily(buildingId: Ref<string>, usage: Ref<ScreenPeriod>, mirror: Ref<ScreenPeriod>) {
  const views = shallowRef<ReadonlyMap<string, DailyTrends>>(new Map())
  const failedMonths = ref<string[]>([]), loading = ref(false)
  const inFlight = new Map<string, { controller: AbortController; promise: Promise<void> }>()
  const months = computed(() => [...new Set([usage.value, mirror.value]
    .filter(p => p.kind === 'month' && validScreenPeriod(p)).map(p => p.value))].sort())
  let activeBuilding = buildingId.value, disposed = false
  let refreshTimer: ReturnType<typeof setInterval> | null = null
  const requestKey = (building: string, month: string) => `${building}\u0000${month}`

  function updateLoading() {
    loading.value = !!activeBuilding && months.value.some(month => inFlight.has(requestKey(activeBuilding, month)))
  }

  function abortAll() {
    for (const request of inFlight.values()) request.controller.abort()
    inFlight.clear()
  }

  function startRequest(building: string, month: string) {
    const key = requestKey(building, month), controller = new AbortController()
    const request: { controller: AbortController; promise: Promise<void> } = { controller, promise: Promise.resolve() }
    inFlight.set(key, request)
    request.promise = (async () => {
      const current = () => !disposed && inFlight.get(key) === request && building === activeBuilding
        && building === buildingId.value && months.value.includes(month)
      try {
        const view = await readDailyTrends(building, month, controller.signal, () => {
          if (!current()) return
          const currentViews = new Map(views.value)
          currentViews.delete(month)
          views.value = currentViews
        })
        validateDailyTrends(view, building, month)
        if (!current()) return
        views.value = new Map([...views.value, [month, view]])
        failedMonths.value = failedMonths.value.filter(item => item !== month)
      } catch {
        if (current()) {
          const currentViews = new Map(views.value)
          currentViews.delete(month)
          views.value = currentViews
          if (!failedMonths.value.includes(month)) failedMonths.value = [...failedMonths.value, month]
        }
      } finally {
        if (inFlight.get(key) === request) inFlight.delete(key)
        updateLoading()
      }
    })()
    updateLoading()
    return request.promise
  }

  async function load(forceRefresh = false) {
    const building = buildingId.value, requested = [...months.value]
    if (building !== activeBuilding) {
      abortAll()
      activeBuilding = building
      views.value = new Map()
      failedMonths.value = []
    } else {
      const requestedKeys = new Set(requested.map(month => requestKey(building, month)))
      for (const [key, request] of inFlight) {
        if (requestedKeys.has(key)) continue
        request.controller.abort()
        inFlight.delete(key)
      }
    }

    const retained = new Map([...views.value].filter(([month, view]) => requested.includes(month) && view.buildingId === building))
    views.value = retained
    failedMonths.value = failedMonths.value.filter(month => requested.includes(month))
    if (!building || disposed) {
      updateLoading()
      return
    }

    const pending: Promise<void>[] = []
    for (const month of requested) {
      const key = requestKey(building, month)
      if (forceRefresh) {
        const previous = inFlight.get(key)
        previous?.controller.abort()
        if (previous) inFlight.delete(key)
        retained.delete(month)
        failedMonths.value = failedMonths.value.filter(item => item !== month)
      } else if (retained.has(month)) continue

      const existing = inFlight.get(key)
      pending.push(existing?.promise ?? startRequest(building, month))
    }
    views.value = new Map(retained)
    updateLoading()
    await Promise.all(pending)
  }

  async function refreshSelectedMonths() {
    const building = buildingId.value
    if (!building || disposed || building !== activeBuilding) return
    const pending: Promise<void>[] = []
    for (const month of months.value) {
      const key = requestKey(building, month)
      if (inFlight.has(key)) continue
      pending.push(startRequest(building, month))
    }
    await Promise.all(pending)
  }

  const reload = () => load(true)
  watch([buildingId, () => months.value.join(',')], () => { void load() }, { immediate: true })
  refreshTimer = setInterval(() => { void refreshSelectedMonths() }, REFRESH_INTERVAL_MS)
  onScopeDispose(() => {
    disposed = true
    if (refreshTimer !== null) clearInterval(refreshTimer)
    refreshTimer = null
    abortAll()
  })
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
