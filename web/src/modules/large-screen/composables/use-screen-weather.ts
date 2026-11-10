import { onScopeDispose, ref, watch, type Ref } from 'vue'
import { t } from '@/locales'
import { screenWeatherQueries, type WeatherSeries } from '@/modules/weather/public'
import type { ScreenPlotSeries } from '../components/ScreenPlot.vue'

const BEIJING_OFFSET_MS = 8 * 60 * 60 * 1_000
const DAY_MS = 24 * 60 * 60 * 1_000
const HOUR_MS = 60 * 60 * 1_000
const HISTORY_HOURLY = 'HISTORY_HOURLY'
const READY_STATUS = 'READY'
const PARTIAL_STATUS = 'PARTIAL'
const HISTORY_DATA_KIND = 'MODEL_HISTORY_BEST_MATCH'
const PERIODS = Array.from({ length: 24 }, (_, hour) => `${String(hour).padStart(2, '0')}:00`)

type ScreenWeatherStatus = 'loading' | 'ready' | 'partial' | 'empty' | 'error' | 'unavailable'
type DayWindow = { from: string; to: string; slots: number[] }
type HourEvidence = { count: number; value: number | null }

function beijingDay(timestamp: number): string {
  return new Date(timestamp + BEIJING_OFFSET_MS).toISOString().slice(0, 10)
}

function dayWindow(day: string): DayWindow | null {
  if (!/^\d{4}-(0[1-9]|1[0-2])-(0[1-9]|[12]\d|3[01])$/.test(day)) return null
  const from = Date.parse(`${day}T00:00:00+08:00`)
  if (!Number.isFinite(from) || beijingDay(from) !== day) return null
  const to = from + DAY_MS
  return {
    from: new Date(from).toISOString(),
    to: new Date(to).toISOString(),
    slots: Array.from({ length: 24 }, (_, hour) => from + hour * HOUR_MS),
  }
}

function isValidLocation(series: WeatherSeries, buildingId: string, at: number): boolean {
  const location = series.location
  if (!location || location.buildingId !== buildingId || location.enabled !== true
    || !Number.isFinite(location.latitude) || location.latitude < -90 || location.latitude > 90
    || !Number.isFinite(location.longitude) || location.longitude < -180 || location.longitude > 180) return false
  const effectiveFrom = Date.parse(location.effectiveFrom)
  const effectiveTo = location.effectiveTo == null ? null : Date.parse(location.effectiveTo)
  return Number.isFinite(effectiveFrom) && effectiveFrom <= at
    && (effectiveTo == null || Number.isFinite(effectiveTo) && at < effectiveTo)
}

/** PARTIAL 表示任一气象字段缺失；有限的 temperature_2m 仍可作为大屏温度证据。 */
function isHistoricalAvailable(series: WeatherSeries): boolean {
  return series.source === 'OPEN_METEO'
    && series.product === HISTORY_HOURLY
    && (series.status === READY_STATUS || series.status === PARTIAL_STATUS)
    && series.provenance?.dataKind === HISTORY_DATA_KIND
    && series.provenance.timezone === 'Asia/Shanghai'
    && series.provenance.units.temperature_2m === '°C'
}

function isInstant(value: string): number | null {
  if (!/(?:Z|[+-]\d{2}:\d{2})$/i.test(value)) return null
  const timestamp = Date.parse(value)
  return Number.isFinite(timestamp) ? timestamp : null
}

function collectTemperature(series: WeatherSeries[], window: DayWindow): Array<number | null> {
  const evidence = new Map<number, HourEvidence>()
  const slots = new Set(window.slots)
  for (const item of series) {
    for (const sample of item.samples) {
      const timestamp = isInstant(sample.time)
      if (timestamp == null || !slots.has(timestamp)) continue
      const hour = evidence.get(timestamp) ?? { count: 0, value: null }
      hour.count += 1
      const temperature = sample.values.temperature_2m
      if (typeof temperature === 'number' && Number.isFinite(temperature)) hour.value = temperature
      evidence.set(timestamp, hour)
    }
  }
  return window.slots.map(slot => {
    const hour = evidence.get(slot)
    return hour?.count === 1 ? hour.value : null
  })
}

function datasetIds(series: WeatherSeries[]): string[] {
  return [...new Set(series.map(item => item.datasetId).filter((id): id is string => typeof id === 'string' && id.trim().length > 0))]
}

function hasUnavailableLocation(series: WeatherSeries[], buildingId: string, at: number): boolean {
  return series.some(item => item.product === HISTORY_HOURLY
    && (item.status === 'NOT_CONFIGURED' || !isValidLocation(item, buildingId, at)))
}

export function useScreenWeather(buildingId: Readonly<Ref<string>>, day: Readonly<Ref<string>>) {
  const periods = ref<string[]>([...PERIODS])
  const series = ref<ScreenPlotSeries[]>([])
  const status = ref<ScreenWeatherStatus>('unavailable')
  const currentDatasetIds = ref<string[]>([])
  let generation = 0
  let abort: AbortController | null = null
  let disposed = false

  function clear(): void {
    series.value = []
    currentDatasetIds.value = []
  }

  function isCurrent(requestGeneration: number, currentBuildingId: string, currentDay: string, signal: AbortSignal): boolean {
    return !disposed && !signal.aborted && generation === requestGeneration
      && buildingId.value === currentBuildingId && day.value === currentDay
  }

  /** 切换建筑或日期先撤销并清空旧证据，迟到响应只能写回自己的请求世代。 */
  async function load(): Promise<void> {
    generation += 1
    abort?.abort()
    abort = null
    clear()
    if (disposed) return

    const currentBuildingId = buildingId.value
    const currentDay = day.value
    const window = dayWindow(currentDay)
    if (!currentBuildingId || !window) {
      status.value = 'unavailable'
      return
    }

    const requestGeneration = generation
    const controller = new AbortController()
    abort = controller
    status.value = 'loading'
    try {
      const result = await screenWeatherQueries.hourly(
        currentBuildingId,
        HISTORY_HOURLY,
        window.from,
        window.to,
        controller.signal,
      )
      if (!isCurrent(requestGeneration, currentBuildingId, currentDay, controller.signal)) return
      const eligible = result.filter(item => isHistoricalAvailable(item) && isValidLocation(item, currentBuildingId, Date.parse(window.from)))
      const values = collectTemperature(eligible, window)
      currentDatasetIds.value = datasetIds(eligible)
      if (values.some(value => value !== null)) {
        series.value = [{ name: t('largeScreen.outsideTemperature'), axis: 1, values }]
        status.value = eligible.some(item => item.status === PARTIAL_STATUS) || values.some(value => value === null) ? 'partial' : 'ready'
      } else status.value = hasUnavailableLocation(result, currentBuildingId, Date.parse(window.from)) ? 'unavailable' : 'empty'
    } catch {
      if (!isCurrent(requestGeneration, currentBuildingId, currentDay, controller.signal)) return
      clear()
      status.value = 'error'
    } finally {
      if (generation === requestGeneration && abort === controller) abort = null
    }
  }

  function reload(): Promise<void> {
    return load()
  }

  watch([buildingId, day], () => { void reload() }, { immediate: true, flush: 'sync' })
  onScopeDispose(() => {
    disposed = true
    generation += 1
    abort?.abort()
    abort = null
    clear()
    status.value = 'unavailable'
  })

  return { periods, series, status, datasetIds: currentDatasetIds, reload }
}
