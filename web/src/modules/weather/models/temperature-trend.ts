import type { Provenance } from './weather'

export type TemperaturePeriodType = 'YEAR' | 'MONTH'
export type TemperatureStatus = 'READY' | 'PARTIAL' | 'MISSING' | 'NOT_CONFIGURED' | 'IN_PROGRESS' | 'NOT_STARTED'
export interface TemperatureTrend {
  buildingId: string
  periodType: TemperaturePeriodType
  period: string
  timezoneId: string
  unit: string
  aggregation: string
  asOfDate: string
  status: TemperatureStatus
  points: Array<{ date: string; startInclusive: string; endExclusive: string; value: string | null;
    status: TemperatureStatus; validHours: number; expectedHours: number; reasons: string[]; datasetIds: string[] }>
  sources: Array<{ datasetId: string; bindingId: string; locationVersion: number; provider: string; product: string;
    fetchedAt: string; contentHash: string; provenance: Provenance }>
  resultVersion: string
}
export function temperaturePeriodDates(type: TemperaturePeriodType, period: string): string[] {
  const pattern = type === 'YEAR' ? /^\d{4}$/ : /^\d{4}-(0[1-9]|1[0-2])$/
  if (!pattern.test(period) || Number(period.slice(0, 4)) < 2024 || Number(period.slice(0, 4)) > 2200) throw new Error('TEMPERATURE_PERIOD_INVALID')
  if (type === 'YEAR') return Array.from({ length: 12 }, (_, index) => `${period}-${String(index + 1).padStart(2, '0')}-01`)
  const [year, month] = period.split('-').map(Number)
  return Array.from({ length: new Date(Date.UTC(year, month, 0)).getUTCDate() }, (_, index) => `${period}-${String(index + 1).padStart(2, '0')}`)
}
const fail = (): never => { throw new Error('TEMPERATURE_TREND_CONTRACT_MISMATCH') }
const statuses: TemperatureStatus[] = ['READY', 'PARTIAL', 'MISSING', 'NOT_CONFIGURED', 'IN_PROGRESS', 'NOT_STARTED']
const decimal = (value: unknown) => {
  if (typeof value !== 'string' || !/^-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?$/.test(value) || !Number.isFinite(Number(value))) return fail()
  return Number(value)
}

/** 只验证期间、单位和完整性并转为绘图类型；日/月平均值始终由后端提供。 */
export function temperatureTrendValues(view: TemperatureTrend, buildingId: string, type: TemperaturePeriodType, period: string): Array<number | null> {
  const dates = temperaturePeriodDates(type, period)
  if (view.buildingId !== buildingId || view.periodType !== type || view.period !== period || view.timezoneId !== 'Asia/Shanghai'
    || view.unit !== '°C' || view.aggregation !== 'COMPLETE_HISTORY_HOURLY_MEAN' || !statuses.includes(view.status)
    || !Array.isArray(view.points) || view.points.length !== dates.length || !Array.isArray(view.sources)
    || !/^\d{4}-\d{2}-\d{2}$/.test(view.asOfDate) || !Number.isFinite(Date.parse(`${view.asOfDate}T00:00:00+08:00`))
    || !/^[0-9a-f]{64}$/.test(view.resultVersion)) return fail()
  const sourceIds = new Set<string>()
  for (const source of view.sources) {
    if (!source.datasetId || sourceIds.has(source.datasetId) || !source.bindingId || !Number.isSafeInteger(source.locationVersion) || source.locationVersion < 1
      || source.provider !== 'OPEN_METEO' || source.product !== 'HISTORY_HOURLY' || !Number.isFinite(Date.parse(source.fetchedAt))
      || !/^[0-9a-f]{64}$/.test(source.contentHash) || source.provenance?.dataKind !== 'MODEL_HISTORY_BEST_MATCH'
      || source.provenance.timezone !== 'Asia/Shanghai' || source.provenance.units.temperature_2m !== '°C') return fail()
    sourceIds.add(source.datasetId)
  }
  return view.points.map((point, index) => {
    const date = dates[index]!, [year, month, day] = date.split('-').map(Number)
    const from = Date.UTC(year, month - 1, day) - 28800000
    const to = (type === 'YEAR' ? Date.UTC(year, month, 1) : Date.UTC(year, month - 1, day + 1)) - 28800000
    const expectedHours = (to - from) / 3600000
    if (point.date !== date || Date.parse(point.startInclusive) !== from || Date.parse(point.endExclusive) !== to
      || point.expectedHours !== expectedHours || !Number.isSafeInteger(point.validHours) || point.validHours < 0 || point.validHours > expectedHours
      || !statuses.includes(point.status) || !Array.isArray(point.reasons) || !Array.isArray(point.datasetIds)
      || new Set(point.datasetIds).size !== point.datasetIds.length || point.datasetIds.some(id => !sourceIds.has(id))) return fail()
    if (point.status !== 'READY') return point.value === null ? null : fail()
    if (point.validHours !== expectedHours || !point.datasetIds.length || to > Date.parse(`${view.asOfDate}T00:00:00+08:00`)) return fail()
    return decimal(point.value)
  })
}
