import type { CarbonIntensityView, IntensityQuery } from '../api/carbon-intensity'

export interface IntensityPeriod { kind: 'year' | 'month'; value: string }
export function intensityQuery(buildingId: string, period: IntensityPeriod, now = Date.now()): IntensityQuery | null {
  if (!buildingId) return null
  const current = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit' }).format(now)
  const [currentYear, currentMonth] = current.split('-').map(Number)
  if (period.kind === 'month') {
    if (!/^\d{4}-(?:0[1-9]|1[0-2])$/.test(period.value)) return null
    const [year, month] = period.value.split('-').map(Number)
    return { buildingId, year, month, mode: 'MONTH', timezoneId: 'Asia/Shanghai' }
  }
  if (!/^\d{4}$/.test(period.value)) return null
  const year = Number(period.value)
  if (year === currentYear) return currentMonth === 1 ? null : { buildingId, year, month: currentMonth - 1, mode: 'MONTHLY_YTD', timezoneId: 'Asia/Shanghai' }
  return { buildingId, year, mode: 'FORMAL_YEAR', timezoneId: 'Asia/Shanghai' }
}
function decimal(value: string | null | undefined) {
  if (typeof value !== 'string' || !/^\d+(?:\.\d+)?$/.test(value)) return null
  const number = Number(value)
  return Number.isFinite(number) ? number : null
}
export function validateCarbonIntensity(view: CarbonIntensityView, query: IntensityQuery) {
  const month = query.mode === 'MONTH' ? query.month! : 1
  const duration = query.mode === 'FORMAL_YEAR' ? 12 : query.mode === 'MONTH' ? 1 : query.month!
  const endMonth = month + duration
  const end = `${query.year + Math.floor((endMonth - 1) / 12)}-${String(((endMonth - 1) % 12) + 1).padStart(2, '0')}-01`
  if (!view.actual || typeof view.actual.unit !== 'string' || !Array.isArray(view.reasons)
    || !Array.isArray(view.percentageReasons) || !['AVAILABLE', 'UNAVAILABLE'].includes(view.percentageStatus)
    || (view.reference !== null && !view.reference?.rule)
    || view.buildingId !== query.buildingId || view.mode !== query.mode || view.timezoneId !== query.timezoneId
    || view.start !== `${query.year}-${String(month).padStart(2, '0')}-01` || view.endExclusive !== end) throw new Error('CARBON_INTENSITY_SCOPE_MISMATCH')
}
/** 仅转换展示类型。实际强度、三个参考值及比例全部来自后端；不在浏览器年化或除以阈值。 */
export function mapCarbonIntensity(view: CarbonIntensityView, period: IntensityPeriod) {
  const reference = view.reference
  const actualAvailable = view.actual.status === 'AVAILABLE' && ['kgCO2e/m²', 'kgCO2/m²'].includes(view.actual.unit)
  const basisMatches = (view.mode === 'FORMAL_YEAR' && view.percentageBasis === 'ANNUAL_REFERENCE')
    || (view.mode === 'MONTHLY_YTD' && view.percentageBasis === 'YTD_SHARE_OF_ANNUAL_REFERENCE')
  return {
    intensity: { period, data: { areaValue: actualAvailable ? decimal(view.actual.value) : null,
      areaUnit: view.actual.unit.replace('CO2e', 'CO₂e').replace('CO2', 'CO₂'),
      areaPercentage: actualAvailable && basisMatches && view.percentageStatus === 'AVAILABLE' && reference ? decimal(view.percentage) : null,
      personValue: null, personPercentage: null, personUnit: '' } },
    intensityBenchmarks: { constraint: decimal(reference?.rule.constraintValue), baseline: decimal(reference?.rule.baselineValue), guide: decimal(reference?.rule.guideValue),
      unit: reference?.unit ?? '', sourceName: reference?.name ?? '', documentVersion: reference?.documentVersion ?? '', sourceReference: reference?.sourceReference ?? '',
      climateLabel: reference?.rule.climateLabel ?? '', versionId: reference?.versionId ?? '', timeStatus: reference?.rule.timeStatus ?? '',
      comparisonStatus: view.comparisonStatus, reasons: [...new Set([...view.reasons, ...view.percentageReasons])], mode: view.mode,
      percentageBasis: view.percentageBasis,
      throughMonth: Number(view.endExclusive.slice(5, 7)) === 1 ? 12 : Number(view.endExclusive.slice(5, 7)) - 1 },
  }
}
