import { expect, it } from 'vitest'
import type { CurrentDayWeather } from '@/modules/weather/public'
import type { ComfortTrends } from '../api/comfort'
import { pmvChartOption, temperatureChartOption, temperaturesByHour } from './temperature-chart'

it('aligns by Beijing hour, preserves zero and missing values, and excludes future weather', () => {
  const startsAt = '2026-11-18T16:00:00Z'
  const trend: ComfortTrends = {
    space: { buildingId: 'b', spaceId: 'r', spaceName: 'B314', floorName: null },
    date: '2026-11-19', timeZone: 'Asia/Shanghai', pointId: 'p', selectedSource: null, temperatureSourceStatus: 'SELECTED',
    hours: [
      { startsAt, indoorTemperature: { celsius: 0, observedAt: startsAt, stale: true } },
      { startsAt: '2026-11-18T17:00:00Z', indoorTemperature: { celsius: null, observedAt: startsAt, stale: true } },
    ],
  }
  const weather: CurrentDayWeather = {
    date: '2026-11-19',
    current: {
      location: null, source: 'OPEN_METEO', product: 'CURRENT', status: 'READY', updateStatus: 'FRESH',
      fetchedAt: null, datasetId: 'w', provenance: null, samples: [],
    },
    hours: [
      { startsAt: '2026-11-18T17:00:00Z', observedAt: '2026-11-18T17:15:00Z', celsius: 10 },
      { startsAt: '2026-11-18T18:00:00Z', observedAt: '2026-11-18T18:15:00Z', celsius: 11 },
    ],
  }
  const hours = temperaturesByHour('2026-11-19', Date.parse('2026-11-18T17:25:00Z'), trend, weather)
  expect(hours).toHaveLength(24)
  expect(hours.slice(0, 3)).toEqual([
    { label: '00:00', indoor: 0, outdoor: null },
    { label: '01:00', indoor: null, outdoor: 10 },
    { label: '02:00', indoor: null, outdoor: null },
  ])
  expect(temperatureChartOption(hours).series).toMatchObject([
    { connectNulls: false, data: expect.arrayContaining([0, null]) },
    { connectNulls: false, lineStyle: { type: 'dashed' }, data: expect.arrayContaining([10, null]) },
  ])
  expect(temperaturesByHour('2026-11-19', Infinity, trend, { ...weather, date: '2026-11-18' })
    .every(hour => hour.outdoor === null)).toBe(true)
})

it('draws uninterrupted lines without permanent markers and keeps isolated readings visible', () => {
  const hours = [
    { label: '00:00', indoor: 24, outdoor: 20 },
    { label: '01:00', indoor: 25, outdoor: 19 },
    { label: '02:00', indoor: null, outdoor: 18 },
    { label: '03:00', indoor: 26, outdoor: 17 },
  ]
  const option = temperatureChartOption(hours)
  expect(option.tooltip).toMatchObject({ trigger: 'axis', axisPointer: { type: 'line', lineStyle: { type: 'dashed' } } })
  expect(option.series).toMatchObject([{ showSymbol: true }, { showSymbol: false }])

  const [indoor] = option.series as [{ symbolSize: (value: unknown, data: { dataIndex: number }) => number }]
  expect(indoor.symbolSize(24, { dataIndex: 0 })).toBe(0)
  expect(indoor.symbolSize(26, { dataIndex: 3 })).toBe(5)
})

it('fits a configured temperature band even when temperatures are missing or all zero', () => {
  const band = { tLow: 22, tHigh: 26 }
  const missing = [{ label: '00:00', indoor: null, outdoor: null }]
  expect(temperatureChartOption(missing, band).yAxis).toMatchObject({ min: 21.5, max: 26.5 })
  expect(temperatureChartOption([{ ...missing[0]!, indoor: 0 }], band).yAxis).toMatchObject({ min: 0, max: 26.5 })
  const measured = [
    { label: '00:00', indoor: 23, outdoor: 4 },
    { label: '01:00', indoor: 25, outdoor: 9 },
  ]
  expect(temperatureChartOption(measured, band).yAxis).toMatchObject({ boundaryGap: [0, 1.5 / 21] })
})

it('draws the PMV band and zero baseline while leaving future and missing hours empty', () => {
  const first = { sampledAt: '2026-11-19T00:10:00Z', temperatureObservedAt: '2026-11-19T00:09:59Z',
    value: 0, indoorCelsius: 24, inputs: { relativeHumidity: 50 } }
  const option = pmvChartOption('2026-11-19', Date.parse('2026-11-19T01:20:00Z'), [
    { startsAt: '2026-11-19T00:00:00Z', observation: first },
    { startsAt: '2026-11-19T02:00:00Z', observation: { ...first, sampledAt: '2026-11-19T02:15:00Z', value: 1.4 } },
  ])
  expect(option.series).toMatchObject([{
    connectNulls: false, markArea: { data: [[{ yAxis: -.5 }, { yAxis: .5 }]] }, markLine: { data: [{ yAxis: 0 }] },
  }])
  const [series] = option.series as [{ data: Array<number | null> }]
  expect(option.yAxis).toMatchObject({ min: -1, max: 1 })
  expect(series.data).toHaveLength(24)
  expect(series.data.slice(8, 11)).toEqual([0, null, null])
})

it('marks both out-of-range PMV values at the axis edges and reports actual values in the tooltip', () => {
  const date = '2026-11-19'
  const observation = (hour: string, value: number) => ({ startsAt: `${date}T${hour}:00:00+08:00`,
    observation: { sampledAt: `${date}T${hour}:15:00+08:00`, temperatureObservedAt: `${date}T${hour}:14:00+08:00`,
      value, indoorCelsius: 24, inputs: {} } })
  const option = pmvChartOption(date, Date.parse(`${date}T09:30:00+08:00`), [
    observation('06', -1.71), observation('07', -1), observation('08', 1), observation('09', 1.65),
  ])
  const [series] = option.series as [{ data: unknown[]; showSymbol: boolean; showAllSymbol: boolean; clip: boolean }]
  expect(series.data.slice(6, 10)).toMatchObject([{ value: -1, symbol: 'triangle', symbolRotate: 180 },
    -1, 1, { value: 1, symbol: 'triangle', symbolRotate: 0 }])
  expect(series).toMatchObject({ showSymbol: true, showAllSymbol: true, clip: false })
  expect(option.yAxis).toMatchObject({ min: -1, max: 1 })
  const { valueFormatter } = option.tooltip as { valueFormatter: (axisValue: number, index: number) => string }
  expect(valueFormatter(-1, 6)).toBe('-1.71（超出图表量程）')
  expect(valueFormatter(1, 9)).toBe('1.65（超出图表量程）')
  expect(valueFormatter(-1, 7)).toBe('-1.00')
})
