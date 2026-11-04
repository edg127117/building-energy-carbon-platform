import type { MonthlyComparisonResult, MonthlyMetricCode, MonthlyPeriodResult } from '@/modules/energy-reporting/public'
import type { CalculationDetail } from '@/modules/energy-classification/public'
import type { ScreenPeriods, ScreenRedesignData } from './screen-redesign'

const units: Record<MonthlyMetricCode, string> = {
  ELECTRICITY_KWH: 'kWh', ELECTRICITY_TCE: 'tce', TOTAL_EMISSION: 'tCO2e', MONTH_AREA_INTENSITY: 'kgCO2e/m²',
}
export function decimalValue(value: string | null | undefined): number | null {
  if (typeof value !== 'string' || !/^-?\d+(?:\.\d+)?$/.test(value)) return null
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : null
}
export function monthlyMetric(period: MonthlyPeriodResult | undefined, code: MonthlyMetricCode): number | null {
  const matches = period?.metrics.filter(metric => metric.metricCode === code) ?? []
  const metric = matches.length === 1 ? matches[0] : undefined
  return metric?.status === 'AVAILABLE' && metric.unit === units[code] ? decimalValue(metric.value) : null
}
export function validateMonthlyResult(result: MonthlyComparisonResult, buildingId: string, month: string): void {
  if (result.buildingId !== buildingId || result.timezoneId !== 'Asia/Shanghai' || result.current?.month !== `${month}-01`
    || !Array.isArray(result.current.metrics) || !Array.isArray(result.comparisons)) throw new Error('MONTHLY_RESULT_SCOPE_MISMATCH')
  const [year, value] = month.split('-').map(Number)
  const from = Date.UTC(year, value - 1, 1) - 8 * 60 * 60 * 1000
  const to = Date.UTC(year, value, 1) - 8 * 60 * 60 * 1000
  if (Date.parse(result.current.startInclusive) !== from || Date.parse(result.current.endExclusive) !== to) throw new Error('MONTHLY_RESULT_WINDOW_MISMATCH')
}
export function previousMonth(now = Date.now()): string {
  const date = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit' }).format(now)
  const [year, month] = date.split('-').map(Number)
  return `${month === 1 ? year - 1 : year}-${String(month === 1 ? 12 : month - 1).padStart(2, '0')}`
}
export function yearMonths(year: string): string[] {
  return Array.from({ length: 12 }, (_, index) => `${year}-${String(index + 1).padStart(2, '0')}`)
}

function record(value: unknown): Record<string, unknown> | null {
  return typeof value === 'object' && value !== null && !Array.isArray(value) ? value as Record<string, unknown> : null
}
/** 仅接受当前月批次冻结的电力固定因子证据；总排放字段本身不能证明“静态”口径。 */
export function staticElectricityFactor(detail: CalculationDetail, evidence: unknown, report: MonthlyComparisonResult): number | null {
  const batchId = report.current.metrics.find(metric => metric.metricCode === 'TOTAL_EMISSION')?.sourceBatchId
  const first = detail.items[0]
  if (!first || detail.calculationBatchId !== batchId || detail.buildingId !== report.buildingId || detail.timezoneId !== report.timezoneId
    || detail.startInclusive !== report.current.startInclusive || detail.endExclusive !== report.current.endExclusive
    || detail.status !== 'COMPLETED_COMPLETE' || !['DIRECT', 'PUBLISHED'].includes(detail.publicationStatus)
    || detail.resultNature !== 'FORMAL' || !detail.items.every(item => item.energyItemCode === 'ELECTRICITY' && item.scopeType === 'SCOPE_2'
      && item.factorVersionId === first.factorVersionId && item.formulaVersionId === first.formulaVersionId)) return null
  const frozen = record(evidence), factor = record(frozen?.factor)
  if (!factor || frozen?.factorVersionId !== first.factorVersionId || frozen?.formulaVersionId !== first.formulaVersionId
    || factor.category !== 'PURCHASED_ELECTRICITY_LOCATION' || factor.energyItemCode !== 'ELECTRICITY'
    || factor.scopeType !== 'SCOPE_2' || factor.gasCoverage !== 'CO2_ONLY_ELECTRICITY' || decimalValue(String(frozen?.gwpValue)) !== 1) return null
  const components = Array.isArray(factor.components) ? factor.components.map(record).filter(component => component?.type === 'DIRECT_EMISSION_FACTOR') : []
  const component = components.length === 1 ? components[0] : null
  const value = component?.unit === 'KG_CO2/KWH' ? decimalValue(String(component.value)) : null
  return value !== null && value >= 0 ? value : null
}

/** 各月结果只对齐到图表刻度；不累计年度指标、不拆分日量，也不补算因子、折标或碳排。 */
export function mapFinalBMonthly(reports: ReadonlyMap<string, MonthlyComparisonResult>, periods: ScreenPeriods, lastMonth: string, fixedFactors: ReadonlyMap<string, number> = new Map()): ScreenRedesignData {
  const data: ScreenRedesignData = {}
  const previous = reports.get(lastMonth)?.current
  if (previous) data.previousMonth = { month: lastMonth, carbon: monthlyMetric(previous, 'TOTAL_EMISSION'), intensity: monthlyMetric(previous, 'MONTH_AREA_INTENSITY') }
  if (periods.composition.kind === 'month') {
    const report = reports.get(periods.composition.value)
    const comparisons = report?.comparisons.filter(item => item.metricCode === 'TOTAL_EMISSION' && item.unit === units.TOTAL_EMISSION) ?? []
    const comparison = comparisons.length === 1 ? comparisons[0] : undefined
    if (report) data.composition = { period: periods.composition, data: {
      total: monthlyMetric(report.current, 'TOTAL_EMISSION'), unit: 'tCO₂e',
      yearOverYear: comparison?.yearOnYear.status === 'OK' ? decimalValue(comparison.yearOnYear.percentageChange) : null,
      monthOverMonth: comparison?.monthOnMonth.status === 'OK' ? decimalValue(comparison.monthOnMonth.percentageChange) : null,
    } }
  }
  if (periods.usage.kind === 'year') {
    data.usage = { period: periods.usage, data: {
      electricity: yearMonths(periods.usage.value).map(month => monthlyMetric(reports.get(month)?.current, 'ELECTRICITY_KWH')),
      temperature: Array.from({ length: 12 }, () => null), electricityUnit: 'kWh', temperatureUnit: '℃',
    } }
  }
  if (periods.intensity.kind === 'month') {
    const result = reports.get(periods.intensity.value)?.current
    if (result) data.intensity = { period: periods.intensity, data: {
      areaValue: monthlyMetric(result, 'MONTH_AREA_INTENSITY'), areaUnit: 'kgCO₂e/m²', areaPercentage: null,
      personValue: null, personUnit: '', personPercentage: null,
    } }
  }
  if (periods.mirror.kind === 'year') {
    const months = yearMonths(periods.mirror.value)
    // fixedFactors 只由 CO2-only 冻结证据写入，因此有静态值时可与动态 CO2 共用双轴单位。
    const staticCo2Only = months.some(month => fixedFactors.has(month))
    data.mirror = { period: periods.mirror, data: {
      dynamicEmissions: months.map(() => null), dynamicFactors: months.map(() => null),
      staticEmissions: months.map(month => fixedFactors.has(month) ? monthlyMetric(reports.get(month)?.current, 'TOTAL_EMISSION') : null),
      staticFactors: months.map(month => fixedFactors.get(month) ?? null),
      emissionUnit: staticCo2Only ? 'tCO₂' : 'tCO₂e', factorUnit: staticCo2Only ? 'kgCO₂/kWh' : 'kgCO₂e/kWh',
    } }
  }
  return data
}
