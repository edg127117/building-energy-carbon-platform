import { effectScope, ref } from 'vue'
import { flushPromises } from '@vue/test-utils'
import { beforeEach, expect, it, vi } from 'vitest'
import { readDailyTrends, type DailyTrends } from '@/modules/energy-reporting/public'
import type { ScreenPeriod } from '../models/screen-period'
import { dailyFixture } from '../models/final-b-daily.test-fixture'
import { useFinalBDaily } from './use-final-b-daily'
vi.mock('@/modules/energy-reporting/api/daily-trends', () => ({ readDailyTrends: vi.fn() }))
beforeEach(() => { vi.mocked(readDailyTrends).mockReset(); vi.mocked(readDailyTrends).mockImplementation(async (building, month) => dailyFixture(building, month)) })

it('shares a month across two cards, independently supports different months and keeps year inputs untouched', async () => {
  const scope = effectScope(), building = ref('B'), usage = ref<ScreenPeriod>({ kind: 'year', value: '2026' }), mirror = ref<ScreenPeriod>({ kind: 'year', value: '2026' })
  const state = scope.run(() => useFinalBDaily(building, usage, mirror))!
  await flushPromises(); expect(readDailyTrends).not.toHaveBeenCalled(); expect(state.data.value).toEqual({})
  usage.value = mirror.value = { kind: 'month', value: '2024-02' }; await flushPromises()
  expect(readDailyTrends).toHaveBeenCalledTimes(1); expect(state.data.value.usage?.data.electricity).toHaveLength(29)
  mirror.value = { kind: 'month', value: '2026-04' }; await flushPromises()
  expect(state.data.value.mirror?.data.staticEmissions).toHaveLength(30)
  expect(state.data.value.usage?.period.value).toBe('2024-02')
  scope.stop()
})
it('aborts switched scopes, rejects wrong identities and ignores late responses without demo fallback', async () => {
  const pending: Array<(v: DailyTrends) => void> = []
  vi.mocked(readDailyTrends).mockImplementation(() => new Promise(resolve => pending.push(resolve)))
  const scope = effectScope(), building = ref('B'), period = ref<ScreenPeriod>({ kind: 'month', value: '2024-02' })
  const state = scope.run(() => useFinalBDaily(building, period, period))!
  const signal = vi.mocked(readDailyTrends).mock.calls[0][2]!
  building.value = 'C'; await flushPromises(); expect(signal.aborted).toBe(true); expect(state.data.value.usage).toBeUndefined()
  pending[1](dailyFixture('C')); await flushPromises(); pending[0](dailyFixture('B')); await flushPromises()
  expect(state.views.value.get('2024-02')?.buildingId).toBe('C')
  const reload = state.reload(); pending[2](dailyFixture('WRONG')); await reload
  expect(state.data.value.mirror).toBeUndefined(); expect(state.failedMonths.value).toEqual(['2024-02'])
  scope.stop()
})

it('reuses completed same-building months across staggered card changes but refreshes on explicit retry', async () => {
  const scope = effectScope(), building = ref('B')
  const usage = ref<ScreenPeriod>({ kind: 'month', value: '2026-01' }), mirror = ref<ScreenPeriod>({ kind: 'month', value: '2026-01' })
  const state = scope.run(() => useFinalBDaily(building, usage, mirror))!
  await flushPromises()
  usage.value = { kind: 'month', value: '2026-10' }; await flushPromises()
  expect(vi.mocked(readDailyTrends).mock.calls.map(call => call[1])).toEqual(['2026-01', '2026-10'])
  expect(state.data.value.mirror?.period.value).toBe('2026-01')
  mirror.value = { kind: 'month', value: '2026-10' }; await flushPromises()
  expect(readDailyTrends).toHaveBeenCalledTimes(2)
  expect(state.data.value.usage?.data.electricity).toHaveLength(31)
  expect(state.data.value.mirror?.data.staticEmissions).toHaveLength(31)
  await state.reload()
  expect(readDailyTrends).toHaveBeenCalledTimes(3)
  scope.stop()
})

it('commits each month as soon as it completes', async () => {
  const pending: Array<{ month: string; resolve: (view: DailyTrends) => void }> = []
  vi.mocked(readDailyTrends).mockImplementation((building, month) => new Promise(resolve => pending.push({ month, resolve })))
  const scope = effectScope(), building = ref('B')
  const usage = ref<ScreenPeriod>({ kind: 'month', value: '2026-01' })
  const mirror = ref<ScreenPeriod>({ kind: 'month', value: '2026-02' })
  const state = scope.run(() => useFinalBDaily(building, usage, mirror))!
  await flushPromises()
  expect(readDailyTrends).toHaveBeenCalledTimes(2)

  pending.find(item => item.month === '2026-02')!.resolve(dailyFixture('B', '2026-02'))
  await flushPromises()
  expect(state.data.value.mirror?.period.value).toBe('2026-02')
  expect(state.data.value.usage).toBeUndefined()
  expect(state.loading.value).toBe(true)

  pending.find(item => item.month === '2026-01')!.resolve(dailyFixture('B', '2026-01'))
  await flushPromises()
  expect(state.data.value.usage?.period.value).toBe('2026-01')
  expect(state.loading.value).toBe(false)

  mirror.value = usage.value
  await flushPromises()
  expect(readDailyTrends).toHaveBeenCalledTimes(2)
  scope.stop()
})

it('shares a same-building in-flight request when the second card selects its month', async () => {
  let resolveRequest!: (view: DailyTrends) => void
  vi.mocked(readDailyTrends).mockImplementation(() => new Promise(resolve => { resolveRequest = resolve }))
  const scope = effectScope(), building = ref('B')
  const usage = ref<ScreenPeriod>({ kind: 'month', value: '2026-01' })
  const mirror = ref<ScreenPeriod>({ kind: 'year', value: '2026' })
  const state = scope.run(() => useFinalBDaily(building, usage, mirror))!
  await flushPromises()
  expect(readDailyTrends).toHaveBeenCalledTimes(1)

  mirror.value = usage.value
  await flushPromises()
  expect(readDailyTrends).toHaveBeenCalledTimes(1)
  resolveRequest(dailyFixture('B', '2026-01'))
  await flushPromises()
  expect(state.data.value.usage?.period.value).toBe('2026-01')
  expect(state.data.value.mirror?.period.value).toBe('2026-01')
  scope.stop()
})

it('aborts the active request when its scope is disposed', async () => {
  vi.mocked(readDailyTrends).mockImplementation(() => new Promise(() => undefined))
  const scope = effectScope(), building = ref('B')
  const period = ref<ScreenPeriod>({ kind: 'month', value: '2026-01' })
  scope.run(() => useFinalBDaily(building, period, period))
  const signal = vi.mocked(readDailyTrends).mock.calls[0][2]!
  scope.stop()
  expect(signal.aborted).toBe(true)
})

it('does not commit a removed month response and reload replaces a current in-flight request', async () => {
  const pending: Array<{ building: string; month: string; resolve: (view: DailyTrends) => void; signal?: AbortSignal }> = []
  vi.mocked(readDailyTrends).mockImplementation((building, month, signal) => new Promise(resolve => pending.push({ building, month, resolve, signal })))
  const scope = effectScope(), building = ref('B')
  const period = ref<ScreenPeriod>({ kind: 'month', value: '2026-01' })
  const state = scope.run(() => useFinalBDaily(building, period, period))!
  await flushPromises()

  period.value = { kind: 'month', value: '2026-02' }
  await flushPromises()
  expect(pending[0].signal?.aborted).toBe(false)
  pending[0].resolve(dailyFixture('B', '2026-01'))
  await flushPromises()
  expect(state.views.value.has('2026-01')).toBe(false)
  expect(state.data.value.usage).toBeUndefined()

  const oldSignal = pending[1].signal
  const reload = state.reload()
  await flushPromises()
  expect(oldSignal?.aborted).toBe(true)
  expect(pending).toHaveLength(3)
  pending[1].resolve(dailyFixture('B', '2026-02'))
  pending[2].resolve(dailyFixture('B', '2026-02'))
  await reload
  expect(state.views.value.get('2026-02')?.buildingId).toBe('B')
  scope.stop()
})
