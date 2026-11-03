import { onScopeDispose, ref, watch, type Ref } from 'vue'
import { t } from '@/locales'
import {
  screenHourlyCarbonQueries,
  type HourlyCarbonDeclaredSourceNature,
  type HourlyCarbonState,
} from '@/modules/energy-classification/public'
import type { ScreenPlotSeries } from '../components/ScreenPlot.vue'

const BEIJING_OFFSET_MS = 8 * 60 * 60 * 1_000
const HOUR_MS = 60 * 60 * 1_000
const TIMEZONE = 'Asia/Shanghai'
const UNIT = 'KG_CO2E'
const SCOPE = 'PURCHASED_ELECTRICITY_SCOPE_2'
const RESULT_NATURE = 'MONITORING'
const PERIODS = Array.from({ length: 24 }, (_, hour) => `${String(hour).padStart(2, '0')}:00`)
const VIEW_STATES = new Set<HourlyCarbonState>(['MISSING', 'COMPLETE', 'PARTIAL'])
const POINT_STATUSES = new Set(['MISSING', 'COMPLETE', 'INCOMPLETE', 'BLOCKED', 'NOT_DUE'])
const SOURCE_KINDS = new Set(['RAW_CUMULATIVE', 'REVIEWED_INTERVAL'])
const SOURCE_NATURES = new Set<HourlyCarbonDeclaredSourceNature>(['SIMULATED', 'SOURCE_LABELLED'])
const FACTOR_USAGE_NATURES = new Set(['FORMAL', 'DEVELOPMENT_REFERENCE'])

export type ScreenHourlyCarbonStatus = 'loading' | 'ready' | 'partial' | 'missing' | 'error' | 'unavailable'

export interface ScreenHourlyCarbonEvidence {
  calculationBatchId: string | null
  revision: number | null
  declaredSourceNature: HourlyCarbonDeclaredSourceNature | null
  state: HourlyCarbonState
}

interface HourWindow {
  startInclusive: number
  endExclusive: number
}

interface ValidatedHourlyCarbonView {
  evidence: ScreenHourlyCarbonEvidence
  values: Array<number | null>
}

function beijingDay(timestamp: number): string {
  return new Date(timestamp + BEIJING_OFFSET_MS).toISOString().slice(0, 10)
}

function dayWindows(day: string): HourWindow[] | null {
  if (!/^\d{4}-(0[1-9]|1[0-2])-(0[1-9]|[12]\d|3[01])$/.test(day)) return null
  const start = Date.parse(`${day}T00:00:00+08:00`)
  if (!Number.isFinite(start) || beijingDay(start) !== day) return null
  return Array.from({ length: 24 }, (_, hour) => ({
    startInclusive: start + hour * HOUR_MS,
    endExclusive: start + (hour + 1) * HOUR_MS,
  }))
}

function recordOf(value: unknown): Record<string, unknown> | null {
  return value != null && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown>
    : null
}

function instant(value: unknown): number | null {
  if (typeof value !== 'string' || !/(?:Z|[+-]\d{2}:\d{2})$/i.test(value)) return null
  const parsed = Date.parse(value)
  return Number.isFinite(parsed) ? parsed : null
}

function nonNegativeKg(value: unknown): number | null {
  if (typeof value !== 'string' || value.trim().length === 0) return null
  const parsed = Number(value)
  return Number.isFinite(parsed) && parsed >= 0 ? parsed : null
}

function nullableText(value: unknown): string | null | undefined {
  if (value == null) return null
  return typeof value === 'string' && value.trim().length > 0 ? value : undefined
}

function nullableRevision(value: unknown): number | null | undefined {
  if (value == null) return null
  return typeof value === 'number' && Number.isInteger(value) && value >= 0 ? value : undefined
}

function inHourRange(value: unknown): value is number {
  return typeof value === 'number' && Number.isInteger(value) && value >= 0 && value <= 24
}

function statusFor(state: HourlyCarbonState): ScreenHourlyCarbonStatus {
  return state === 'COMPLETE' ? 'ready' : state === 'PARTIAL' ? 'partial' : 'missing'
}

/**
 * 后端用北京整点窗口及 calculationAsOf 裁决到期范围。大屏拒绝错位、重复或未到期数据，
 * 并且只逐点读取已完成的 kgCO2e，绝不插值、相加或将 MISSING 映射为零。
 */
export function validateScreenHourlyCarbonView(
  value: unknown,
  buildingId: string,
  date: string,
): ValidatedHourlyCarbonView | null {
  const view = recordOf(value)
  const windows = dayWindows(date)
  if (!view || !windows
    || view.buildingId !== buildingId
    || view.date !== date
    || view.timezoneId !== TIMEZONE
    || view.unit !== UNIT
    || view.scope !== SCOPE
    || view.resultNature !== RESULT_NATURE
    || typeof view.state !== 'string'
    || !VIEW_STATES.has(view.state as HourlyCarbonState)
    || !inHourRange(view.dueHours)
    || !inHourRange(view.availableHours)
    || !Array.isArray(view.points)
    || view.points.length !== 24) return null

  const state = view.state as HourlyCarbonState
  let sourceNature: HourlyCarbonDeclaredSourceNature | null = null
  if (state === 'MISSING') {
    if (view.sourceKind !== null || view.declaredSourceNature !== null || view.factorUsageNature !== null) return null
  } else {
    if (typeof view.sourceKind !== 'string' || !SOURCE_KINDS.has(view.sourceKind)
      || typeof view.declaredSourceNature !== 'string' || !SOURCE_NATURES.has(view.declaredSourceNature as HourlyCarbonDeclaredSourceNature)
      || typeof view.factorUsageNature !== 'string' || !FACTOR_USAGE_NATURES.has(view.factorUsageNature)) return null
    sourceNature = view.declaredSourceNature as HourlyCarbonDeclaredSourceNature
  }
  const batchId = nullableText(view.calculationBatchId)
  const revision = nullableRevision(view.revision)
  if (batchId === undefined || revision === undefined) return null
  if (state !== 'MISSING' && (batchId == null || revision == null)) return null

  const calculationAsOf = view.calculationAsOf == null ? null : instant(view.calculationAsOf)
  if (view.calculationAsOf != null && calculationAsOf == null) return null
  if (state !== 'MISSING' && calculationAsOf == null) return null

  const values = Array<number | null>(24).fill(null)
  const seen = new Set<number>()
  let completeHours = 0
  for (const rawPoint of view.points) {
    const point = recordOf(rawPoint)
    if (!point || typeof point.hour !== 'number' || !Number.isInteger(point.hour)
      || point.hour < 0 || point.hour > 23 || seen.has(point.hour)
      || typeof point.status !== 'string' || !POINT_STATUSES.has(point.status)) return null
    const window = windows[point.hour]
    if (instant(point.startInclusive) !== window.startInclusive || instant(point.endExclusive) !== window.endExclusive) return null
    seen.add(point.hour)

    const status = point.status
    if (calculationAsOf != null) {
      const isDue = window.endExclusive <= calculationAsOf
      if (isDue ? status === 'NOT_DUE' : status !== 'NOT_DUE') return null
    }
    if (status === 'COMPLETE') {
      const emission = nonNegativeKg(point.emissionKgCo2e)
      if (emission == null) return null
      values[point.hour] = emission
      completeHours += 1
    }
  }
  if (seen.size !== 24 || view.availableHours !== completeHours) return null

  if (calculationAsOf != null) {
    const dueHours = windows.filter(window => window.endExclusive <= calculationAsOf).length
    if (view.dueHours !== dueHours) return null
  }

  if (state === 'MISSING') {
    if (completeHours !== 0 || view.availableHours !== 0 || view.points.some(rawPoint => {
      const status = recordOf(rawPoint)?.status
      return status !== 'MISSING' && status !== 'NOT_DUE'
    })) return null
  } else if (state === 'COMPLETE') {
    if (view.dueHours !== 24 || completeHours !== 24 || view.points.some(rawPoint => recordOf(rawPoint)?.status !== 'COMPLETE')) return null
  } else if (view.points.some(rawPoint => recordOf(rawPoint)?.status === 'MISSING') || completeHours === 24) return null

  return {
    evidence: { calculationBatchId: batchId ?? null, revision: revision ?? null, declaredSourceNature: sourceNature, state },
    values,
  }
}

export function useScreenHourlyCarbon(buildingId: Readonly<Ref<string>>, day: Readonly<Ref<string>>) {
  const periods = ref<string[]>([...PERIODS])
  const series = ref<ScreenPlotSeries[]>([])
  const status = ref<ScreenHourlyCarbonStatus>('unavailable')
  const evidence = ref<ScreenHourlyCarbonEvidence | null>(null)
  let generation = 0
  let abort: AbortController | null = null
  let disposed = false

  function clear(): void {
    series.value = []
    evidence.value = null
  }

  function isCurrent(requestGeneration: number, currentBuildingId: string, currentDay: string, signal: AbortSignal): boolean {
    return !disposed && !signal.aborted && generation === requestGeneration
      && buildingId.value === currentBuildingId && day.value === currentDay
  }

  /** 切换建筑或日期先撤销并清空旧结果，迟到响应只能回写自己的请求世代。 */
  async function load(): Promise<void> {
    generation += 1
    abort?.abort()
    abort = null
    clear()
    if (disposed) return

    const currentBuildingId = buildingId.value
    const currentDay = day.value
    if (!currentBuildingId || !dayWindows(currentDay)) {
      status.value = 'unavailable'
      return
    }

    const requestGeneration = generation
    const controller = new AbortController()
    abort = controller
    status.value = 'loading'
    try {
      const result = await screenHourlyCarbonQueries.getHourly(currentBuildingId, currentDay, controller.signal)
      if (!isCurrent(requestGeneration, currentBuildingId, currentDay, controller.signal)) return
      const validated = validateScreenHourlyCarbonView(result, currentBuildingId, currentDay)
      if (!validated) {
        clear()
        status.value = 'error'
        return
      }
      evidence.value = validated.evidence
      series.value = [{ name: t('largeScreen.emissions'), values: validated.values }]
      status.value = statusFor(validated.evidence.state)
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

  return { periods, series, status, evidence, reload }
}
