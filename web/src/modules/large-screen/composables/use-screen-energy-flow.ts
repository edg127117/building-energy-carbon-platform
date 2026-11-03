import { computed, onScopeDispose, ref, watch, type Ref } from 'vue'
import { queryEnergyFlow, type EnergyFlowView, type EnergyFlowSelection } from '@/modules/energy-classification/public'
import { beijingMonthWindow } from '../models/screen-carbon'

export function useScreenEnergyFlow(buildingId: Readonly<Ref<string>>, month: Readonly<Ref<string>>) {
  const report = ref<EnergyFlowView | null>(null)
  const loading = ref(false)
  const failed = ref(false)
  const selections = ref<EnergyFlowSelection[]>([])
  let generation = 0
  let abort: AbortController | null = null
  let disposed = false
  const choices = computed(() => {
    const options = report.value?.conversionOptions ?? []
    return options.filter((option, index) => options.findIndex(item => item.energyItemCode === option.energyItemCode && item.unit === option.unit) === index)
      .map(option => ({ energyItemCode: option.energyItemCode, unit: option.unit, options: options.filter(item => item.energyItemCode === option.energyItemCode && item.unit === option.unit) }))
  })

  async function reload() {
    const requestGeneration = ++generation
    abort?.abort()
    abort = new AbortController()
    const signal = abort.signal
    const building = buildingId.value, period = month.value
    // 参数选择仍需可见，结果图必须先清除，防止选用新口径时继续展示旧数。
    if (report.value) report.value = { ...report.value, flows: [], resultVersion: null }
    loading.value = false
    failed.value = false
    if (disposed || !building || !beijingMonthWindow(period)) { report.value = null; return }
    loading.value = true
    const current = () => !disposed && !signal.aborted && requestGeneration === generation && building === buildingId.value && period === month.value
    try {
      const result = await queryEnergyFlow(building, period, selections.value, signal)
      if (!current()) return
      if (result.buildingId !== building || result.month !== period || result.unit !== 'TCE') throw new Error('Flow scope mismatch')
      report.value = result
    } catch {
      if (!current()) return
      report.value = null
      failed.value = true
    } finally { if (current()) loading.value = false }
  }

  function select(energyItemCode: string, unit: string, parameterVersionId: string) {
    selections.value = selections.value.filter(item => item.energyItemCode !== energyItemCode || item.unit !== unit)
    if (parameterVersionId) selections.value.push({ energyItemCode, unit, parameterVersionId })
    void reload()
  }

  watch([buildingId, month], () => {
    selections.value = []
    report.value = null
    void reload()
  }, { immediate: true, flush: 'sync' })
  onScopeDispose(() => { disposed = true; generation += 1; abort?.abort(); report.value = null; loading.value = false })
  return { report, loading, failed, choices, selections, select, reload }
}
