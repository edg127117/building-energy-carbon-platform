import { expect, it } from 'vitest'
import { dailyScreenData, validateDailyTrends } from './final-b-daily'
import { dailyFixture } from './final-b-daily.test-fixture'
import { withApprovedScreenDemo } from './final-b-demo'

it.each(['2024-02', '2026-02', '2026-04', '2026-01'])('aligns exact daily values, preserves zero and gaps for %s', month => {
  const view = dailyFixture('B', month), period = { kind: 'month' as const, value: month }
  validateDailyTrends(view, 'B', month)
  view.days[1].electricityStatus = 'INCOMPLETE'
  view.days[2].staticCarbonStatus = 'INCOMPLETE_HOURS'; view.days[2].factorStatus = 'INCOMPLETE_HOURS'
  const data = dailyScreenData(view, period, period)
  expect(data.usage?.data.electricity).toHaveLength(view.days.length)
  expect(data.usage?.data.electricity.slice(0, 3)).toEqual([0, null, 123.456])
  expect(data.mirror?.data.staticEmissions.slice(0, 3)).toEqual([0.00123456789, 0.00123456789, null])
  expect(data.mirror?.data.staticFactors.slice(0, 3)).toEqual([0.56, 0.56, null])
})
it('rejects crossed identities, units, truncated months, duplicate dates and changed windows', () => {
  for (const change of [
    (v: ReturnType<typeof dailyFixture>) => { v.buildingId = 'OTHER' },
    (v: ReturnType<typeof dailyFixture>) => { v.emissionUnit = 'kgCO2e' },
    (v: ReturnType<typeof dailyFixture>) => { v.days.pop() },
    (v: ReturnType<typeof dailyFixture>) => { v.days[1].date = v.days[0].date },
    (v: ReturnType<typeof dailyFixture>) => { v.days[0].startInclusive = '2024-02-01T00:00:00Z' },
  ]) { const view = dailyFixture(); change(view); expect(() => validateDailyTrends(view, 'B', '2024-02')).toThrow() }
})
it('uses demo material only for explicitly authorized sections and never fills static data', () => {
  const period = { kind: 'month' as const, value: '2024-02' }
  const base = dailyScreenData(dailyFixture(), period, period)
  const data = withApprovedScreenDemo(base, period)
  expect(data.devices?.on).toBe(25); expect(data.alarms?.deviceAnomalies).toBe(3)
  expect(data.mirror?.data.dynamicFactors.some(v => v !== null)).toBe(true)
  expect(data.mirror?.data.staticEmissions).toEqual(base.mirror?.data.staticEmissions)
  expect(data.usage).toBe(base.usage)
  expect(withApprovedScreenDemo({}, period).mirror?.data.staticEmissions).toEqual(Array(29).fill(null))
  expect(withApprovedScreenDemo(base, { kind: 'year', value: '2025' }).mirror?.data.staticFactors).toEqual(Array(12).fill(null))
})
