import { computed, onScopeDispose, ref, shallowRef, watch, type Ref } from 'vue'
import { screenWeatherQueries } from '@/modules/weather/public'
import { validScreenPeriod, type ScreenPeriod } from '../models/screen-period'
import type { ScreenRedesignData } from '../models/screen-redesign'
import { hourlyScreenData, validateHourlyMonitoring, weatherTemperatures } from '../models/final-b-hourly'
import { readHourlyMonitoring, type HourlyMonitoringView } from '../api/hourly-monitoring'

const REFRESH_MS = 2_000
const WEATHER_REFRESH_MS = 60_000
const WEATHER_TIMEOUT_MS = 5_000
type DaySnapshot = { view?: HourlyMonitoringView; weather?: { history: Array<number | null>; forecast: Array<number | null> }; failed: boolean; weatherFailed: boolean }

export function useFinalBHourly(buildingId: Ref<string>, usage: Ref<ScreenPeriod>, mirror: Ref<ScreenPeriod>) {
  const snapshots = shallowRef(new Map<string, DaySnapshot>())
  const loading = ref(new Set<string>())
  let activeBuilding = buildingId.value
  let disposed = false
  let timer: ReturnType<typeof setTimeout> | null = null
  let weatherTimer: ReturnType<typeof setTimeout> | null = null
  const controllers = new Map<string, AbortController>()
  const weatherControllers = new Map<string, AbortController>()
  const weatherStartedAt = new Map<string, number>()
  const key = (building: string, date: string) => `${building}\u0000${date}`
  const selectedDays = computed(() => [...new Set([usage.value, mirror.value].filter(period => period.kind === 'day' && validScreenPeriod(period)).map(period => period.value))])

  function selected(building: string, date: string) {
    return !disposed && buildingId.value === building && selectedDays.value.includes(date)
  }

  function selectedUsageDay() {
    return usage.value.kind === 'day' && validScreenPeriod(usage.value) ? usage.value.value : null
  }

  async function readWeather(building: string, date: string, signal: AbortSignal) {
    const controller = new AbortController()
    const abortWeather = () => controller.abort()
    signal.addEventListener('abort', abortWeather, { once: true })
    const query = (product: string) => screenWeatherQueries.hourly(building, product, `${date}T00:00:00+08:00`, `${nextDate(date)}T00:00:00+08:00`, controller.signal)
    let timeout: ReturnType<typeof setTimeout> | undefined
    let abortListener: (() => void) | undefined
    try {
      const cancelled = new Promise<never>((_, reject) => {
        abortListener = () => reject(new Error('WEATHER_ABORTED'))
        controller.signal.addEventListener('abort', abortListener, { once: true })
      })
      const result = await Promise.race([
        Promise.all([query('HISTORY_HOURLY'), query('FORECAST_HOURLY')]),
        new Promise<never>((_, reject) => { timeout = setTimeout(() => { controller.abort(); reject(new Error('WEATHER_TIMEOUT')) }, WEATHER_TIMEOUT_MS) }),
        cancelled,
      ])
      return weatherTemperatures([...result[0], ...result[1]], building, date)
    } finally {
      if (timeout !== undefined) clearTimeout(timeout)
      if (abortListener) controller.signal.removeEventListener('abort', abortListener)
      signal.removeEventListener('abort', abortWeather)
    }
  }

  async function loadDay(building: string, date: string): Promise<void> {
    const id = key(building, date)
    if (controllers.has(id) || !selected(building, date)) return
    const controller = new AbortController()
    controllers.set(id, controller)
    loading.value = new Set(loading.value).add(date)
    // Request identity plus current scope blocks late responses after a building or day switch.
    const current = () => selected(building, date) && controllers.get(id) === controller && !controller.signal.aborted
    try {
      const view = await readHourlyMonitoring(building, date, controller.signal)
      validateHourlyMonitoring(view, building, date)
      if (current()) snapshots.value = new Map(snapshots.value).set(id, { ...snapshots.value.get(id), view, failed: false, weatherFailed: snapshots.value.get(id)?.weatherFailed ?? false })
    } catch {
      if (current()) snapshots.value = new Map(snapshots.value).set(id, { ...snapshots.value.get(id), failed: true, weatherFailed: snapshots.value.get(id)?.weatherFailed ?? false })
    } finally {
      if (controllers.get(id) === controller) {
        controllers.delete(id)
        const remaining = new Set(loading.value)
        remaining.delete(date)
        loading.value = remaining
      }
    }
  }

  function stopTimer() {
    if (timer !== null) clearTimeout(timer)
    timer = null
  }

  function stopWeatherTimer() {
    if (weatherTimer !== null) clearTimeout(weatherTimer)
    weatherTimer = null
  }

  function scheduleWeatherRefresh() {
    stopWeatherTimer()
    if (disposed || !selectedUsageDay() || isDocumentHidden()) return
    weatherTimer = setTimeout(() => { void refreshWeather(true) }, WEATHER_REFRESH_MS)
  }

  async function refreshWeather(force = false) {
    const date = selectedUsageDay()
    const building = buildingId.value
    if (!building || !date || !selected(building, date)) {
      for (const [id, controller] of weatherControllers) {
        if (id !== key(building, date ?? '')) controller.abort()
      }
      stopWeatherTimer()
      return
    }
    const id = key(building, date)
    if (weatherControllers.has(id)) return
    const lastStarted = weatherStartedAt.get(id)
    if (!force && lastStarted !== undefined && Date.now() - lastStarted < WEATHER_REFRESH_MS) return
    const controller = new AbortController()
    weatherControllers.set(id, controller)
    weatherStartedAt.set(id, Date.now())
    const current = () => !disposed && buildingId.value === building && selectedUsageDay() === date
      && weatherControllers.get(id) === controller && !controller.signal.aborted
    try {
      const weather = await readWeather(building, date, controller.signal)
      if (current()) snapshots.value = new Map(snapshots.value).set(id, { ...snapshots.value.get(id), weather, weatherFailed: false, failed: snapshots.value.get(id)?.failed ?? false })
    } catch {
      if (current()) snapshots.value = new Map(snapshots.value).set(id, { ...snapshots.value.get(id), weatherFailed: true, failed: snapshots.value.get(id)?.failed ?? false })
    } finally {
      if (weatherControllers.get(id) === controller) weatherControllers.delete(id)
      scheduleWeatherRefresh()
    }
  }

  async function refresh(forceWeather = false) {
    stopTimer()
    if (disposed || isDocumentHidden()) return
    if (buildingId.value !== activeBuilding) {
      for (const controller of controllers.values()) controller.abort()
      for (const controller of weatherControllers.values()) controller.abort()
      controllers.clear()
      weatherControllers.clear()
      snapshots.value = new Map()
      loading.value = new Set()
      activeBuilding = buildingId.value
    }
    const selected = selectedDays.value
    for (const [id, controller] of controllers) {
      if (!selected.some(date => id === key(buildingId.value, date))) controller.abort()
    }
    for (const [id, controller] of weatherControllers) {
      if (id !== key(buildingId.value, selectedUsageDay() ?? '')) controller.abort()
    }
    void refreshWeather(forceWeather)
    await Promise.all(selected.map(date => loadDay(buildingId.value, date)))
    if (selected.length && !disposed && !isDocumentHidden()) timer = setTimeout(() => { void refresh() }, REFRESH_MS)
  }

  function onVisibilityChange() {
    if (isDocumentHidden()) { stopTimer(); stopWeatherTimer() }
    else void refresh(true)
  }

  function reload() { void refresh(true) }
  watch([buildingId, () => selectedDays.value.join(','), () => usage.value.kind + ':' + usage.value.value], () => { void refresh() }, { immediate: true, flush: 'sync' })
  if (typeof document !== 'undefined') document.addEventListener('visibilitychange', onVisibilityChange)
  onScopeDispose(() => {
    disposed = true
    stopTimer()
    stopWeatherTimer()
    if (typeof document !== 'undefined') document.removeEventListener('visibilitychange', onVisibilityChange)
    for (const controller of controllers.values()) controller.abort()
    for (const controller of weatherControllers.values()) controller.abort()
    controllers.clear()
    weatherControllers.clear()
  })

  function dataFor(period: ScreenPeriod, property: 'usage' | 'mirror') {
    if (period.kind !== 'day') return undefined
    const snapshot = snapshots.value.get(key(buildingId.value, period.value))
    if (!snapshot?.view) return undefined
    return hourlyScreenData(snapshot.view, snapshot.weather)[property]
  }
  const data = computed<ScreenRedesignData>(() => {
    const result: ScreenRedesignData = {}
    if (usage.value.kind === 'day') {
      const value = dataFor(usage.value, 'usage')
      if (value && 'electricity' in value) result.usage = { period: usage.value, data: value }
    }
    if (mirror.value.kind === 'day') {
      const value = dataFor(mirror.value, 'mirror')
      if (value && 'dynamicEmissions' in value) result.mirror = { period: mirror.value, data: value }
    }
    return result
  })
  const failed = computed(() => ({
    usage: usage.value.kind === 'day' && !!(snapshots.value.get(key(buildingId.value, usage.value.value))?.failed || snapshots.value.get(key(buildingId.value, usage.value.value))?.weatherFailed),
    mirror: mirror.value.kind === 'day' && !!snapshots.value.get(key(buildingId.value, mirror.value.value))?.failed,
  }))
  // 已成功读取的同建筑、同日期快照在轮询期间继续显示；首次读取才显示加载提示。
  function needsInitialLoad(period: ScreenPeriod) {
    return period.kind === 'day' && loading.value.has(period.value)
      && !snapshots.value.get(key(buildingId.value, period.value))?.view
  }
  const isLoading = computed(() => ({ usage: needsInitialLoad(usage.value), mirror: needsInitialLoad(mirror.value) }))
  return { data, failed, loading: isLoading, snapshots, reload }
}

function isDocumentHidden(): boolean {
  return typeof document !== 'undefined' && String(document.visibilityState) === 'hidden'
}

function nextDate(date: string): string {
  const start = Date.parse(`${date}T00:00:00+08:00`)
  return new Date(start + 86_400_000 + 8 * 60 * 60 * 1_000).toISOString().slice(0, 10)
}
