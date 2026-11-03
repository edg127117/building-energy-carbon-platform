import { expect, it } from 'vitest'
import { temperaturePeriodDates, temperatureTrendValues, type TemperatureTrend } from './temperature-trend'

function fixture(): TemperatureTrend {
  return {
    buildingId: 'OTHER', periodType: 'MONTH', period: '2024-02', timezoneId: 'Asia/Shanghai', unit: '°C',
    aggregation: 'COMPLETE_HISTORY_HOURLY_MEAN', asOfDate: '2026-10-30', status: 'READY', resultVersion: 'a'.repeat(64),
    sources: [{ datasetId: 'DATA', bindingId: 'LOCATION', locationVersion: 4, provider: 'OPEN_METEO', product: 'HISTORY_HOURLY',
      fetchedAt: '2026-10-29T00:00:00Z', contentHash: 'b'.repeat(64), provenance: { dataKind: 'MODEL_HISTORY_BEST_MATCH', sourcePublishedAt: null, gridLatitude: 30, gridLongitude: 120, parserVersion: 'v1', timezone: 'Asia/Shanghai', units: { temperature_2m: '°C' } } }],
    points: temperaturePeriodDates('MONTH', '2024-02').map((date, index) => ({ date, startInclusive: new Date(Date.UTC(2024, 1, index + 1) - 28800000).toISOString(), endExclusive: new Date(Date.UTC(2024, 1, index + 2) - 28800000).toISOString(), value: '0', status: 'READY', validHours: 24, expectedHours: 24, reasons: [], datasetIds: ['DATA'] })),
  }
}
it('保留零值、负温度及闰日，不求平均或把缺失补零', () => {
  const view = fixture()
  view.points[0]!.value = '-3.5'
  view.points[1] = { ...view.points[1]!, status: 'PARTIAL', validHours: 23, value: null }
  const values = temperatureTrendValues(view, 'OTHER', 'MONTH', '2024-02')
  expect(values).toHaveLength(29)
  expect(values.slice(0, 3)).toEqual([-3.5, null, 0])
  expect(temperaturePeriodDates('YEAR', '2025')).toHaveLength(12)
})
it('拒绝错建筑、错期间、错单位、重复或截断日期', () => {
  expect(() => temperatureTrendValues(fixture(), 'UNAUTHORIZED', 'MONTH', '2024-02')).toThrow()
  expect(() => temperatureTrendValues(fixture(), 'OTHER', 'YEAR', '2024')).toThrow()
  const view = fixture(); view.unit = '°F'
  expect(() => temperatureTrendValues(view, 'OTHER', 'MONTH', '2024-02')).toThrow()
  view.unit = '°C'; view.points.pop()
  expect(() => temperatureTrendValues(view, 'OTHER', 'MONTH', '2024-02')).toThrow()
})
it('拒绝缺小时仍出均值、未来值及预报冒充历史', () => {
  const view = fixture(); view.points[0]!.validHours = 23
  expect(() => temperatureTrendValues(view, 'OTHER', 'MONTH', '2024-02')).toThrow()
  view.points[0]!.validHours = 24; view.asOfDate = '2024-02-15'
  expect(() => temperatureTrendValues(view, 'OTHER', 'MONTH', '2024-02')).toThrow()
  view.asOfDate = '2026-10-30'; view.sources[0]!.product = 'FORECAST_HOURLY'
  expect(() => temperatureTrendValues(view, 'OTHER', 'MONTH', '2024-02')).toThrow()
})
