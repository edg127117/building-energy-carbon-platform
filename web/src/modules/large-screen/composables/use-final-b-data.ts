import { computed, onMounted, onScopeDispose, ref, shallowRef, watch } from 'vue'
import { listAccessibleBuildings, type Building } from '@/modules/dashboard/public'
import { queryMonthlyComparison, type MonthlyComparisonResult } from '@/modules/energy-reporting/public'
import { screenCarbonQueries } from '@/modules/energy-classification/public'
import { currentScreenPeriod } from '../models/screen-period'
import type { ScreenPeriods } from '../models/screen-redesign'
import { mapFinalBMonthly, previousMonth, staticElectricityFactor, validateMonthlyResult, yearMonths } from '../models/final-b-monthly'

export function useFinalBData(buildingCode = '') {
  const initial = currentScreenPeriod()
  const periods = ref<ScreenPeriods>({ composition: initial, flow: initial, plan: initial, mirror: initial, usage: initial, intensity: initial })
  const buildings = ref<Building[]>([])
  const buildingId = ref('')
  const reports = shallowRef(new Map<string, MonthlyComparisonResult>())
  const fixedFactors = shallowRef(new Map<string, number>())
  const evidenceChecked = new Set<string>()
  const failedMonths = ref<string[]>([])
  const loading = ref(false)
  const buildingError = ref(false)
  const lastMonth = previousMonth()
  let generation = 0
  let abort: AbortController | undefined
  let disposed = false
  const neededMonths = computed(() => [...new Set([
    lastMonth,
    ...(periods.value.usage.kind === 'year' ? yearMonths(periods.value.usage.value) : []),
    ...(periods.value.mirror.kind === 'year' ? yearMonths(periods.value.mirror.value) : []),
    ...[periods.value.composition, periods.value.intensity].filter(period => period.kind === 'month').map(period => period.value),
  ])])
  async function load() {
    const current = ++generation, id = buildingId.value
    abort?.abort()
    abort = new AbortController()
    const signal = abort.signal
    const retry = new Set(failedMonths.value)
    failedMonths.value = []
    loading.value = false
    if (!id || !buildings.value.some(building => building.buildingId === id)) return
    loading.value = true
    reports.value = new Map([...reports.value].filter(([month]) => neededMonths.value.includes(month)))
    fixedFactors.value = new Map([...fixedFactors.value].filter(([month]) => neededMonths.value.includes(month)))
    const jobs = neededMonths.value.filter(month => {
      const report = reports.value.get(month)
      return !report || retry.has(month) || (!evidenceChecked.has(month) && report.current.metrics.some(metric => metric.metricCode === 'TOTAL_EMISSION' && metric.status === 'AVAILABLE'))
    })
    const valid = () => !disposed && !signal.aborted && generation === current && buildingId.value === id
    // 有界并发读取各月，缓存只属于当前建筑；换建筑、卸载及新期间请求都会作废旧响应。
    await Promise.all(Array.from({ length: Math.min(3, jobs.length) }, async () => {
      while (jobs.length && valid()) {
        const month = jobs.shift()!
        try {
          const result = reports.value.get(month) ?? await queryMonthlyComparison(id, month, signal)
          validateMonthlyResult(result, id, month)
          if (valid()) reports.value = new Map(reports.value).set(month, result)
          const batchId = result.current.metrics.find(metric => metric.metricCode === 'TOTAL_EMISSION' && metric.status === 'AVAILABLE')?.sourceBatchId
          if (batchId && valid()) {
            const detail = await screenCarbonQueries.getCalculation(batchId, signal)
            const item = detail.items[0]
            if (item && valid()) {
              const evidence = await screenCarbonQueries.getEvidence(batchId, item.calculationItemId, signal)
              const factor = staticElectricityFactor(detail, evidence, result)
              if (valid()) {
                evidenceChecked.add(month)
                if (factor !== null) fixedFactors.value = new Map(fixedFactors.value).set(month, factor)
              }
            }
          }
        } catch {
          if (valid()) failedMonths.value.push(month)
        }
      }
    }))
    if (valid()) loading.value = false
  }
  async function loadBuildings() {
    buildingError.value = false
    try {
      const result = await listAccessibleBuildings()
      if (disposed) return
      buildings.value = result
      const selected = buildingCode ? result.find(building => building.buildingCode === buildingCode) : undefined
      if (!buildingId.value && selected) buildingId.value = selected.buildingId
    } catch { if (!disposed) buildingError.value = true }
  }
  watch(buildingId, () => { reports.value = new Map(); fixedFactors.value = new Map(); evidenceChecked.clear(); void load() }, { flush: 'sync' })
  watch(neededMonths, () => { void load() })
  onMounted(() => { void loadBuildings() })
  onScopeDispose(() => { disposed = true; generation++; abort?.abort() })
  return {
    periods, buildings, buildingId, reports, loading, failedMonths, buildingError,
    data: computed(() => mapFinalBMonthly(reports.value, periods.value, lastMonth, fixedFactors.value)),
    reload: load, loadBuildings,
  }
}
