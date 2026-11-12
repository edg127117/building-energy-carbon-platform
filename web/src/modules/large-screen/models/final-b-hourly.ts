import type { ScreenMirrorData, ScreenUsageData } from './screen-analytical'
import { validScreenPeriod } from './screen-period'
import type { HourlyMonitoringView } from '../api/hourly-monitoring'
import type { WeatherSeries } from '@/modules/weather/public'

const TIMEZONE = 'Asia/Shanghai'
const HOUR_MS = 3_600_000
const HOUR_STATUSES = new Set(['COMPLETE', 'ACCUMULATING', 'MISSING', 'PREPARING', 'FAILED', 'NOT_ENDED', 'STALE'])
// Backend gasCoverage is point evidence; the current electricity-only CO2 enum must not be inferred from values.
const CO2_ONLY_COVERAGE = new Set(['CO2_ONLY_ELECTRICITY', 'CO2_ONLY'])

function instant(value: unknown): number | null {
  if (typeof value !== 'string' || !/(?:Z|[+-]\d{2}:\d{2})$/i.test(value)) return null
  const timestamp = Date.parse(value)
  return Number.isFinite(timestamp) ? timestamp : null
}

function decimal(value: unknown): number | null | undefined {
  if (value === null) return null
  if (typeof value !== 'string' || !/^\d+(?:\.\d+)?(?:[eE][+-]?\d+)?$/.test(value)) return undefined
  const parsed = Number(value)
  return Number.isFinite(parsed) && parsed >= 0 ? parsed : undefined
}

function beijingStart(date: string): number | null {
  const timestamp = Date.parse(`${date}T00:00:00+08:00`)
  return Number.isFinite(timestamp) ? timestamp : null
}

export function validateHourlyMonitoring(view: HourlyMonitoringView, buildingId: string, date: string): void {
  const start = beijingStart(date)
  if (!validScreenPeriod({ kind: 'day', value: date }) || start === null || view.buildingId !== buildingId || view.date !== date || view.timezoneId !== TIMEZONE
    || instant(view.asOf) === null || !Array.isArray(view.points) || view.points.length !== 24) throw new Error('HOURLY_MONITORING_SCOPE_MISMATCH')
  view.points.forEach((point, hour) => {
    const expectedStart = start + hour * HOUR_MS
    if (point.hour !== hour || instant(point.startInclusive) !== expectedStart || instant(point.endExclusive) !== expectedStart + HOUR_MS
      || !HOUR_STATUSES.has(point.electricityStatus) || !HOUR_STATUSES.has(point.staticStatus) || !HOUR_STATUSES.has(point.dynamicStatus)
      || typeof point.approximate !== 'boolean' || !Array.isArray(point.sourceNatures) || !point.sourceNatures.every(value => typeof value === 'string')) throw new Error('HOURLY_MONITORING_WINDOW_MISMATCH')
    const actualStart = point.actualStart === null ? null : instant(point.actualStart)
    const actualEnd = point.actualEnd === null ? null : instant(point.actualEnd)
    if ((point.actualStart !== null && actualStart === null) || (point.actualEnd !== null && actualEnd === null)
      // Boundary readings may precede the hour by up to six minutes under the backend contract.
      || (actualStart !== null && (actualStart < expectedStart - 6 * 60_000 || actualStart >= expectedStart + HOUR_MS))
      || (actualEnd !== null && (actualEnd <= expectedStart || actualEnd > expectedStart + HOUR_MS))) throw new Error('HOURLY_MONITORING_ACTUAL_WINDOW_MISMATCH')
    for (const [status, value] of [
      [point.electricityStatus, point.electricityKwh], [point.staticStatus, point.staticEmissionTco2e],
      [point.staticStatus, point.staticFactorKgCo2ePerKwh], [point.dynamicStatus, point.dynamicEmissionTco2], [point.dynamicStatus, point.dynamicFactorKgCo2PerKwh],
    ] as const) {
      if (value !== null && decimal(value) === undefined) throw new Error('HOURLY_MONITORING_DECIMAL_INVALID')
      if (status !== 'COMPLETE' && status !== 'ACCUMULATING' && value !== null) throw new Error('HOURLY_MONITORING_STATUS_VALUE_MISMATCH')
    }
  })
}

// Only current backend statuses with values enter the chart; missing, stale and future hours stay null.
function available(status: string, value: string | null): number | null {
  if (status !== 'COMPLETE' && status !== 'ACCUMULATING') return null
  const parsed = decimal(value)
  return parsed === null || parsed === undefined ? null : parsed
}

export function hourlyScreenData(view: HourlyMonitoringView, weather?: { history: Array<number | null>; forecast: Array<number | null> }): { usage: ScreenUsageData; mirror: ScreenMirrorData } {
  const points = view.points
  const staticGasKinds = new Set(points.filter(point => available(point.staticStatus, point.staticEmissionTco2e) !== null || available(point.staticStatus, point.staticFactorKgCo2ePerKwh) !== null)
    .map(point => CO2_ONLY_COVERAGE.has(point.staticGasCoverage ?? '') ? 'CO2' : 'CO2E'))
  const staticUnit = staticGasKinds.size === 1 && staticGasKinds.has('CO2') ? 'tCO₂' : 'tCO₂e'
  const staticFactorUnit = staticGasKinds.size === 1 && staticGasKinds.has('CO2') ? 'kgCO₂/kWh' : 'kgCO₂e/kWh'
  const mirror: ScreenMirrorData = {
    dynamicEmissions: points.map(point => available(point.dynamicStatus, point.dynamicEmissionTco2)),
    staticEmissions: points.map(point => available(point.staticStatus, point.staticEmissionTco2e)),
    dynamicFactors: points.map(point => available(point.dynamicStatus, point.dynamicFactorKgCo2PerKwh)),
    staticFactors: points.map(point => available(point.staticStatus, point.staticFactorKgCo2ePerKwh)),
    emissionUnit: staticUnit, factorUnit: staticFactorUnit,
    dynamicEmissionUnit: 'tCO₂', staticEmissionUnit: staticUnit,
    dynamicFactorUnit: 'kgCO₂/kWh', staticFactorUnit,
    hourDetails: points.map(point => ({ actualStart: point.actualStart, actualEnd: point.actualEnd, status: point.staticStatus, approximate: point.approximate, gasCoverage: point.staticGasCoverage, reasonCode: point.reasonCode })),
  }
  const usage: ScreenUsageData = {
    electricity: points.map(point => available(point.electricityStatus, point.electricityKwh)),
    temperature: weather ? weather.history.map((value, index) => value ?? weather.forecast[index] ?? null) : points.map(() => null),
    ...(weather ? { temperatureHistory: weather.history, temperatureForecast: weather.forecast } : {}),
    electricityUnit: 'kWh', temperatureUnit: '°C',
    hourDetails: points.map(point => ({ actualStart: point.actualStart, actualEnd: point.actualEnd, status: point.electricityStatus, approximate: point.approximate, reasonCode: point.reasonCode })),
  }
  return { usage, mirror }
}

export function weatherTemperatures(series: WeatherSeries[], buildingId: string, date: string): { history: Array<number | null>; forecast: Array<number | null> } {
  const start = beijingStart(date)
  if (start === null) return { history: Array(24).fill(null), forecast: Array(24).fill(null) }
  const expected = Array.from({ length: 24 }, (_, hour) => start + hour * HOUR_MS)
  const values: Record<'HISTORY_HOURLY' | 'FORECAST_HOURLY', Array<Array<number | null>>> = { HISTORY_HOURLY: expected.map(() => []), FORECAST_HOURLY: expected.map(() => []) }
  for (const item of series) {
    const product = item.product as keyof typeof values
    const location = item.location
    if (!(product in values) || item.source !== 'OPEN_METEO' || !location || location.buildingId !== buildingId || location.enabled !== true
      || !item.provenance || item.provenance.timezone !== TIMEZONE || item.provenance.units.temperature_2m !== '°C'
      || !['READY', 'PARTIAL'].includes(item.status)) continue
    const effectiveFrom = Date.parse(location.effectiveFrom)
    const effectiveTo = location.effectiveTo === null ? null : Date.parse(location.effectiveTo)
    if (!Number.isFinite(effectiveFrom) || (effectiveTo !== null && !Number.isFinite(effectiveTo))) continue
    const seen = new Set<number>()
    for (const sample of item.samples) {
      const at = instant(sample.time)
      if (at === null || !expected.includes(at) || at < effectiveFrom || (effectiveTo !== null && at >= effectiveTo)) continue
      const index = (at - start) / HOUR_MS
      if (seen.has(at)) {
        values[product][index].push(null)
        continue
      }
      seen.add(at)
      const value = sample.values.temperature_2m
      values[product][index].push(typeof value === 'number' && Number.isFinite(value) ? value : null)
    }
  }
  const take = (product: keyof typeof values) => values[product].map(items => items.length === 1 ? items[0] : null)
  return { history: take('HISTORY_HOURLY'), forecast: take('FORECAST_HOURLY') }
}
