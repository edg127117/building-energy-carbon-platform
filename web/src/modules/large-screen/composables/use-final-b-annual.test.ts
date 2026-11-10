import { effectScope, ref } from 'vue'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { readAnnualOverview, type AnnualOverview } from '@/modules/energy-reporting/public'
import type { ScreenPeriod } from '../models/screen-period'
import { annualFixture } from '../models/final-b-annual.test-fixture'
import { useFinalBAnnual } from './use-final-b-annual'

vi.mock('@/modules/energy-reporting/api/annual-overview', async original => ({
  ...await original<typeof import('@/modules/energy-reporting/api/annual-overview')>(), readAnnualOverview: vi.fn(),
}))
beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] }); vi.setSystemTime(new Date('2026-10-31T10:00:00+08:00'))
  vi.mocked(readAnnualOverview).mockReset()
  vi.mocked(readAnnualOverview).mockImplementation(async query => annualFixture(query))
})
afterEach(() => vi.useRealTimers())

it('shares current-year reading but keeps historical composition and current top metrics independent', async () => {
  const scope = effectScope(), building = ref('B'), period = ref<ScreenPeriod>({ kind: 'year', value: '2026' })
  const state = scope.run(() => useFinalBAnnual(building, period))!
  await flushPromises()
  expect(readAnnualOverview).toHaveBeenCalledTimes(1)
  expect(state.data.value.annual?.carbon).toBe(129.456789)
  period.value = { kind: 'year', value: '2025' }
  await flushPromises()
  expect(readAnnualOverview).toHaveBeenLastCalledWith({ buildingId: 'B', year: 2025, throughMonth: 12, mode: 'MONTHLY_YTD', timezoneId: 'Asia/Shanghai' }, expect.any(AbortSignal))
  expect(state.data.value.annual?.year).toBe('2026')
  expect(state.data.value.composition?.period.value).toBe('2025')
  period.value = { kind: 'month', value: '2026-03' }
  await flushPromises()
  expect(state.data.value).not.toHaveProperty('composition')
  expect(readAnnualOverview).toHaveBeenCalledTimes(2)
  scope.stop()
})

it('clears switched scopes, ignores late responses and rejects responses for another building', async () => {
  const pending: Array<(result: AnnualOverview) => void> = []
  vi.mocked(readAnnualOverview).mockImplementation(() => new Promise(resolve => pending.push(resolve)))
  const scope = effectScope(), building = ref('B'), period = ref<ScreenPeriod>({ kind: 'year', value: '2026' })
  const state = scope.run(() => useFinalBAnnual(building, period))!
  building.value = 'C'; await flushPromises()
  expect(state.data.value.annual).toBeUndefined()
  const query = { buildingId: 'C', year: 2026, mode: 'MONTHLY_YTD' as const, throughMonth: 9, timezoneId: 'Asia/Shanghai' }
  pending[1](annualFixture(query)); await flushPromises()
  pending[0](annualFixture({ ...query, buildingId: 'B' })); await flushPromises()
  expect(state.result.value?.buildingId).toBe('C')
  const reload = state.reload()
  pending[2](annualFixture({ ...query, buildingId: 'WRONG' })); await reload
  expect(state.data.value.annual).toBeUndefined()
  expect(state.failed.value).toBe(true)
  scope.stop()
})
