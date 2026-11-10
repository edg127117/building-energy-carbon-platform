import type { DailyTrends } from '@/modules/energy-reporting/public'
import type { ScreenPeriod } from './screen-period'
import type { ScreenRedesignData } from './screen-redesign'
import { decimalValue } from './final-b-monthly'

export function validateDailyTrends(view: DailyTrends, building: string, month: string) {
  const [year, m] = month.split('-').map(Number)
  const count = new Date(Date.UTC(year, m, 0)).getUTCDate()
  if (!/^\d{4}-(0[1-9]|1[0-2])$/.test(month) || view.buildingId !== building || view.month !== month
    || view.timezoneId !== 'Asia/Shanghai' || !['DAILY_ELECTRICITY_STATIC_CARBON_V1', 'DAILY_ELECTRICITY_STATIC_CARBON_SUMMARY_V1'].includes(view.contractVersion)
    || view.resultNature !== 'MONITORING' || view.electricityUnit !== 'kWh' || view.emissionUnit !== 'tCO2e'
    || view.factorUnit !== 'kgCO2e/kWh' || !Array.isArray(view.days) || view.days.length !== count) throw new Error('DAILY_TREND_SCOPE_MISMATCH')
  view.days.forEach((day, index) => {
    const start = Date.UTC(year, m - 1, index + 1) - 8 * 3600000
    if (day.date !== `${month}-${String(index + 1).padStart(2, '0')}` || Date.parse(day.startInclusive) !== start
      || Date.parse(day.endExclusive) !== start + 86400000
      || (day.gasBasis !== undefined && !['CO2_ONLY', 'GHG_CO2E', 'UNKNOWN'].includes(day.gasBasis))) throw new Error('DAILY_TREND_WINDOW_MISMATCH')
  })
}
function available(status: string, value: string | null): number | null {
  const parsed = status === 'AVAILABLE' ? decimalValue(value) : null
  return parsed !== null && parsed >= 0 ? parsed : null
}
function staticCo2Only(days: DailyTrends['days']): boolean {
  const values = days.filter(day => available(day.staticCarbonStatus, day.staticEmissionTco2e) !== null
    || available(day.factorStatus, day.staticFactorKgCo2ePerKwh) !== null)
  return values.length > 0 && values.every(day => day.gasBasis === 'CO2_ONLY')
}
export function dailyScreenData(view: DailyTrends, usage: ScreenPeriod, mirror: ScreenPeriod): ScreenRedesignData {
  const data: ScreenRedesignData = {}
  const staticUnitsAreCo2Only = staticCo2Only(view.days)
  if (usage.kind === 'month' && view.month === usage.value) data.usage = { period: usage, data: {
    electricity: view.days.map(day => available(day.electricityStatus, day.electricityKwh)),
    temperature: view.days.map(() => null), electricityUnit: 'kWh', temperatureUnit: '°C',
  } }
  if (mirror.kind === 'month' && view.month === mirror.value) data.mirror = { period: mirror, data: {
    staticEmissions: view.days.map(day => available(day.staticCarbonStatus, day.staticEmissionTco2e)),
    staticFactors: view.days.map(day => available(day.factorStatus, day.staticFactorKgCo2ePerKwh)),
    dynamicEmissions: view.days.map(() => null), dynamicFactors: view.days.map(() => null),
    // 摘要按日声明气体口径；仅每个有值静态日均为 CO2-only 时才能和动态 CO2 共用坐标轴。
    emissionUnit: staticUnitsAreCo2Only ? 'tCO₂' : 'tCO₂e', factorUnit: staticUnitsAreCo2Only ? 'kgCO₂/kWh' : 'kgCO₂e/kWh',
  } }
  return data
}
