import type { CalculationDetail } from '@/modules/energy-classification/public'
import { t } from '@/locales'
import { readCarbonSummary } from './screen-carbon'
import type { ScreenPlotSeries } from '../components/ScreenPlot.vue'

/** 只排列后端已计算的同单位汇总；缺少可比能源分项时退回各期总量，不汇总明细或补零。 */
export function carbonComparisonSeries(details: Array<CalculationDetail | null>): ScreenPlotSeries[] {
  const ready = details.filter((detail): detail is CalculationDetail => detail !== null)
  if (!ready.length) return []
  const codes = (detail: CalculationDetail) => [...new Set(detail.summaries
    .filter(metric => metric.metricCode === 'ENERGY_ITEM_EMISSION' && metric.unitCode === 'tCO2e')
    .map(metric => metric.dimensionCode))].sort()
  const firstCodes = codes(ready[0])
  const completeBreakdown = firstCodes.length > 0 && ready.every(detail =>
    codes(detail).join('\u0000') === firstCodes.join('\u0000')
    && firstCodes.every(code => readCarbonSummary(detail, 'ENERGY_ITEM_EMISSION', code, 'tCO2e') !== null))
  if (completeBreakdown) return firstCodes.map(code => ({
    name: code,
    values: details.map(detail => readCarbonSummary(detail, 'ENERGY_ITEM_EMISSION', code, 'tCO2e')),
  }))
  return [{ name: t('largeScreen.carbonTotal'), values: details.map(detail => readCarbonSummary(detail, 'TOTAL_EMISSION', 'TOTAL', 'tCO2e')) }]
}
