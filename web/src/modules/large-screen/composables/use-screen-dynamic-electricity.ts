import { computed, onScopeDispose, ref, shallowRef, watch, type Ref } from 'vue'
import {
  readDynamicElectricity,
  type DynamicElectricityPointStatus,
  type DynamicElectricityPeriodType,
} from '@/modules/energy-classification/public'
import { validScreenPeriod, type ScreenPeriod } from '../models/screen-period'

const TIMEZONE = 'Asia/Shanghai'
const EMISSION_UNIT = 'tCO2'
const FACTOR_UNIT = 'kgCO2/kWh'
const RESULT_NATURE = 'MONITORING'
const DAY_MS = 24 * 60 * 60 * 1_000
const POINT_STATUSES = new Set<DynamicElectricityPointStatus>(['COMPLETE', 'MISSING', 'PARTIAL', 'NOT_ENDED', 'STALE'])

export type ScreenDynamicElectricityStatus = 'loading' | 'ready' | 'partial' | 'missing' | 'error' | 'unavailable'

export interface ScreenDynamicElectricityData {
  period: ScreenPeriod
  dynamicEmissions: Array<number | null>
  dynamicFactors: Array<number | null>
  emissionUnit: 'tCO₂'
  factorUnit: 'kgCO₂/kWh'
  sourceNatures: string[]
  hasSimulatedSource: boolean
  status: Extract<ScreenDynamicElectricityStatus, 'ready' | 'partial' | 'missing'>
}

interface DynamicWindow {
  period: string
  startInclusive: number
  endExclusive: number
}

function recordOf(value: unknown): Record<string, unknown> | null {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown>
    : null
}

function stringList(value: unknown): string[] | null {
  return Array.isArray(value) && value.every(item => typeof item === 'string') ? value : null
}

function instant(value: unknown): number | null {
  if (typeof value !== 'string' || !/(?:Z|[+-]\d{2}:\d{2})$/i.test(value)) return null
  const parsed = Date.parse(value)
  return Number.isFinite(parsed) ? parsed : null
}

function nonNegativeDecimal(value: unknown): number | null | undefined {
  if (value === null) return null
  // Java BigDecimal 的字符串可用科学计数法（如 0E-34）；仅投影有限非负 Number，不换算业务数值。
  if (typeof value !== 'string' || !/^\d+(?:\.\d+)?(?:[eE][+-]?\d+)?$/.test(value)) return undefined
  const parsed = Number(value)
  return Number.isFinite(parsed) && parsed >= 0 ? parsed : undefined
}

function beijingMidnight(date: string): number | null {
  const parsed = Date.parse(`${date}T00:00:00+08:00`)
  return Number.isFinite(parsed) ? parsed : null
}

function periodType(period: ScreenPeriod): DynamicElectricityPeriodType {
  return period.kind === 'year' ? 'YEAR' : 'MONTH'
}

function windowsFor(period: ScreenPeriod): DynamicWindow[] | null {
  if (!validScreenPeriod(period)) return null
  if (period.kind === 'year') {
    const year = Number(period.value)
    const windows: DynamicWindow[] = []
    for (let index = 0; index < 12; index += 1) {
      const month = index + 1
      const value = `${year}-${String(month).padStart(2, '0')}`
      const next = month === 12 ? `${year + 1}-01` : `${year}-${String(month + 1).padStart(2, '0')}`
      const startInclusive = beijingMidnight(`${value}-01`)
      const endExclusive = beijingMidnight(`${next}-01`)
      if (startInclusive === null || endExclusive === null) return null
      windows.push({ period: value, startInclusive, endExclusive })
    }
    return windows
  }
  const [year, month] = period.value.split('-').map(Number)
  const days = new Date(Date.UTC(year, month, 0)).getUTCDate()
  const windows: DynamicWindow[] = []
  for (let index = 0; index < days; index += 1) {
    const value = `${period.value}-${String(index + 1).padStart(2, '0')}`
    const startInclusive = beijingMidnight(value)
    if (startInclusive === null) return null
    windows.push({ period: value, startInclusive, endExclusive: startInclusive + DAY_MS })
  }
  return windows
}

function statusFor(statuses: DynamicElectricityPointStatus[]): Extract<ScreenDynamicElectricityStatus, 'ready' | 'partial' | 'missing'> {
  if (statuses.every(status => status === 'COMPLETE')) return 'ready'
  return statuses.every(status => status === 'MISSING') ? 'missing' : 'partial'
}

/**
 * 已保存动态结果按北京日/月槽位返回。前端只投影完整槽位，不插值、累计或换算；
 * 任何建筑、单位、时区、窗口或字段不一致都会整体拒绝，避免把错口径结果画进双轴卡。
 */
export function validateScreenDynamicElectricityView(
  value: unknown,
  buildingId: string,
  selectedPeriod: ScreenPeriod,
): ScreenDynamicElectricityData | null {
  const view = recordOf(value)
  const windows = windowsFor(selectedPeriod)
  if (!view || !windows
    || view.buildingId !== buildingId
    || view.periodType !== periodType(selectedPeriod)
    || view.period !== selectedPeriod.value
    || view.timezoneId !== TIMEZONE
    || view.emissionUnit !== EMISSION_UNIT
    || view.factorUnit !== FACTOR_UNIT
    || view.resultNature !== RESULT_NATURE
    || !Array.isArray(view.points)
    || view.points.length !== windows.length) return null

  const dynamicEmissions: Array<number | null> = []
  const dynamicFactors: Array<number | null> = []
  const statuses: DynamicElectricityPointStatus[] = []
  const sourceNatures: string[] = []
  for (const [index, rawPoint] of view.points.entries()) {
    const point = recordOf(rawPoint)
    const expected = windows[index]
    const grains = point ? stringList(point.grains) : null
    const resultIds = point ? stringList(point.resultIds) : null
    const pointSourceNatures = point ? stringList(point.sourceNatures) : null
    if (!point || !expected
      || point.period !== expected.period
      || instant(point.startInclusive) !== expected.startInclusive
      || instant(point.endExclusive) !== expected.endExclusive
      || typeof point.status !== 'string'
      || !POINT_STATUSES.has(point.status as DynamicElectricityPointStatus)
      || (point.reasonCode !== null && typeof point.reasonCode !== 'string')
      || grains === null || resultIds === null || pointSourceNatures === null) return null

    const status = point.status as DynamicElectricityPointStatus
    statuses.push(status)
    if (status !== 'COMPLETE') {
      if (point.electricityKwh !== null || point.emissionTco2 !== null || point.factorKgCo2PerKwh !== null) return null
      dynamicEmissions.push(null)
      dynamicFactors.push(null)
      continue
    }

    const electricity = nonNegativeDecimal(point.electricityKwh)
    const emission = nonNegativeDecimal(point.emissionTco2)
    const factor = nonNegativeDecimal(point.factorKgCo2PerKwh)
    if (electricity === null || electricity === undefined || emission === null || emission === undefined
      || factor === undefined || (electricity > 0 && factor === null)) return null
    dynamicEmissions.push(emission)
    dynamicFactors.push(factor)
    sourceNatures.push(...pointSourceNatures)
  }
  const distinctSourceNatures = [...new Set(sourceNatures)]
  return {
    period: { ...selectedPeriod }, dynamicEmissions, dynamicFactors,
    emissionUnit: 'tCO₂', factorUnit: 'kgCO₂/kWh', sourceNatures: distinctSourceNatures,
    hasSimulatedSource: distinctSourceNatures.includes('SIMULATED'), status: statusFor(statuses),
  }
}

/** 建筑或期间切换立即撤销并清空旧动态槽位，即使传输层迟到也不能覆盖当前图表。 */
export function useScreenDynamicElectricity(buildingId: Readonly<Ref<string>>, period: Readonly<Ref<ScreenPeriod>>) {
  const data = shallowRef<ScreenDynamicElectricityData | null>(null)
  const status = ref<ScreenDynamicElectricityStatus>('unavailable')
  let generation = 0
  let abort: AbortController | null = null
  let disposed = false

  function requestKey(): string {
    return `${buildingId.value}\u0000${period.value.kind}\u0000${period.value.value}`
  }

  function clear(): void {
    data.value = null
  }

  function isCurrent(owner: number, key: string, controller: AbortController): boolean {
    return !disposed && !controller.signal.aborted && generation === owner && abort === controller && requestKey() === key
  }

  async function load(): Promise<void> {
    const owner = ++generation
    abort?.abort()
    abort = null
    clear()
    if (disposed) return

    const currentBuildingId = buildingId.value
    const currentPeriod = { ...period.value }
    if (!currentBuildingId || !windowsFor(currentPeriod)) {
      status.value = 'unavailable'
      return
    }

    const key = requestKey()
    const controller = new AbortController()
    abort = controller
    status.value = 'loading'
    try {
      const result = await readDynamicElectricity(currentBuildingId, periodType(currentPeriod), currentPeriod.value, controller.signal)
      if (!isCurrent(owner, key, controller)) return
      const validated = validateScreenDynamicElectricityView(result, currentBuildingId, currentPeriod)
      if (!validated) {
        clear()
        status.value = 'error'
        return
      }
      data.value = validated
      status.value = validated.status
    } catch {
      if (!isCurrent(owner, key, controller)) return
      clear()
      status.value = 'error'
    } finally {
      if (generation === owner && abort === controller) abort = null
    }
  }

  watch([buildingId, () => period.value.kind, () => period.value.value], () => { void load() }, { immediate: true, flush: 'sync' })
  onScopeDispose(() => {
    disposed = true
    generation += 1
    abort?.abort()
    abort = null
    clear()
    status.value = 'unavailable'
  })

  return {
    data, status,
    loading: computed(() => status.value === 'loading'),
    failed: computed(() => status.value === 'error'),
    reload: load,
  }
}
