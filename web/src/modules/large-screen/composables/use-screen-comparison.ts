import { onScopeDispose, ref, watch, type Ref } from 'vue'
import {
  screenCarbonQueries,
  type CalculationDetail,
} from '@/modules/energy-classification/public'
import {
  beijingMonthWindow,
  comparisonMonths,
  selectScreenMonthlyCarbonResult,
  type ScreenCarbonMonthWindow,
  type ScreenCarbonState,
} from '../models/screen-carbon'

function emptyDetails(): Array<CalculationDetail | null> {
  return [null, null, null]
}

function emptyStates(): ScreenCarbonState[] {
  return ['empty', 'empty', 'empty']
}

export function useScreenComparison(buildingId: Readonly<Ref<string>>, month: Readonly<Ref<string>>) {
  const periods = ref<string[]>([])
  const details = ref<Array<CalculationDetail | null>>(emptyDetails())
  const states = ref<ScreenCarbonState[]>(emptyStates())
  const loading = ref(false)
  let generation = 0
  let abort: AbortController | null = null
  let disposed = false

  function clear(nextPeriods: string[] = []): void {
    periods.value = nextPeriods
    details.value = emptyDetails()
    states.value = emptyStates()
  }

  function isCurrent(
    requestGeneration: number,
    currentBuildingId: string,
    currentMonth: string,
    signal: AbortSignal,
  ): boolean {
    return !disposed && !signal.aborted && generation === requestGeneration
      && buildingId.value === currentBuildingId && month.value === currentMonth
  }

  /** 三个期间由一次精确月结果查询裁决；缺项或重复响应不可回退扫描历史批次。 */
  async function reload(): Promise<void> {
    generation += 1
    abort?.abort()
    abort = null
    const currentBuildingId = buildingId.value
    const currentMonth = month.value
    const nextPeriods = comparisonMonths(currentMonth)
    clear(nextPeriods ?? [])
    loading.value = false
    if (disposed || !currentBuildingId || !nextPeriods) return

    const targets = nextPeriods.map(beijingMonthWindow)
    if (targets.some(target => target == null)) return
    const windows = targets as ScreenCarbonMonthWindow[]
    const requestGeneration = generation
    const controller = new AbortController()
    abort = controller
    loading.value = true
    try {
      const results = await screenCarbonQueries.getMonthlyResults(currentBuildingId, nextPeriods, controller.signal)
      if (!isCurrent(requestGeneration, currentBuildingId, currentMonth, controller.signal)) return
      const selections = windows.map(target => {
        const matches = results.filter(result => result.month === target.month)
        return selectScreenMonthlyCarbonResult(matches.length === 1 ? matches[0] : null, currentBuildingId, target)
      })
      states.value = selections.map(selection => selection.state)
      details.value = selections.map(selection => selection.detail)
    } catch {
      if (!isCurrent(requestGeneration, currentBuildingId, currentMonth, controller.signal)) return
      details.value = emptyDetails()
      states.value = ['error', 'error', 'error']
    } finally {
      if (generation === requestGeneration && abort === controller) abort = null
      if (isCurrent(requestGeneration, currentBuildingId, currentMonth, controller.signal)) loading.value = false
    }
  }

  watch([buildingId, month], () => { void reload() }, { immediate: true, flush: 'sync' })
  onScopeDispose(() => {
    disposed = true
    generation += 1
    abort?.abort()
    abort = null
    loading.value = false
    clear()
  })

  return { periods, details, states, loading, reload }
}
