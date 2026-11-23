import { expect, it } from 'vitest'
import { peopleAxisMax, peopleHourlyOption, peopleTrendOption } from './sensor-charts'

const startsAt = '2026-11-18T16:00:00Z'
const sample = (count: number) => ({ startsAt, count, observedAt: startsAt })

it.each([false, true])('keeps the live count scale stable with either sampling interval (%s)', compact => {
  const { yAxis } = peopleTrendOption([sample(1), sample(2)], compact, peopleAxisMax(null))
  expect(yAxis).toMatchObject({ min: 0, minInterval: 1, splitNumber: 6, max: 6 })
  expect([0, 1, 2, 6, 7, 8, 11].map(count => peopleAxisMax({
    series1m: [sample(count)], series15m: [], hourlyAvg: [],
  }))).toEqual([6, 6, 6, 6, 8, 8, 12])
})

it('shares the live axis with the server averages and preserves zero and missing hours', () => {
  const hourlyAvg = [
    { startsAt, averageCount: 0, sampleCount: 3 },
    { startsAt: '2026-11-18T17:00:00Z', averageCount: 1, sampleCount: 3 },
    { startsAt: '2026-11-18T18:00:00Z', averageCount: null, sampleCount: 0 },
    { startsAt: '2026-11-18T19:00:00Z', averageCount: 9, sampleCount: 3 },
  ]
  const max = peopleAxisMax({ series1m: [sample(7)], series15m: [sample(2)], hourlyAvg })
  const chart = peopleHourlyOption(hourlyAvg, max)
  expect(chart.yAxis).toEqual(peopleTrendOption([sample(7)], false, max).yAxis)
  expect(chart.yAxis).toMatchObject({ min: 0, minInterval: 1, max: 10 })
  expect(chart.series).toMatchObject([{ data: [0, 1, null, 9] }])
})
