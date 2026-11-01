import { annualDisplayValue, type AnnualOverview, type AnnualQuery } from '@/modules/energy-reporting/public'
import { decimalValue } from './final-b-monthly'
import type { ScreenPeriod } from './screen-period'
import type { ScreenRedesignData } from './screen-redesign'

/** 年内截至最近结束月；历史年显式查询12个月累计，不冒充或回退为正式YEAR核算。 */
export function annualScreenQuery(buildingId: string, year: string, now = Date.now()): AnnualQuery | null {
  if (!buildingId || !/^\d{4}$/.test(year)) return null
  const selected = Number(year)
  const [currentYear, currentMonth] = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit',
  }).format(now).split('-').map(Number)
  if (selected < 2000 || selected > 2200 || selected > currentYear) return null
  const cutoff = selected === currentYear ? currentMonth - 1 : 12
  return cutoff > 0 ? { buildingId, year: selected, mode: 'MONTHLY_YTD', throughMonth: cutoff, timezoneId: 'Asia/Shanghai' } : null
}

/** 仅把后端十进制结果转换为展示类型，不累计月排放、计算强度或同比。 */
export function screenAnnualOverview(result: AnnualOverview): ScreenRedesignData['annual'] {
  return {
    year: String(result.year), throughMonth: result.throughMonth,
    carbon: annualDisplayValue(result, 'TOTAL_EMISSION', 'tCO2e'),
    intensity: annualDisplayValue(result, result.mode === 'MONTHLY_YTD' ? 'YTD_AREA_INTENSITY' : 'AREA_INTENSITY', 'kgCO2e/m²'),
  }
}

export function screenAnnualComposition(result: AnnualOverview, period: ScreenPeriod): ScreenRedesignData['composition'] {
  const total = annualDisplayValue(result, 'TOTAL_EMISSION', 'tCO2e')
  const comparisons = result.comparisons.filter(item => item.code === 'TOTAL_EMISSION')
  const change = comparisons.length === 1 ? comparisons[0].yearOnYear : undefined
  return { period, data: { total, throughMonth: result.throughMonth, unit: 'tCO₂e', monthOverMonth: null,
    yearOverYear: total !== null && change?.status === 'OK' ? decimalValue(change.percentageChange) : null } }
}
