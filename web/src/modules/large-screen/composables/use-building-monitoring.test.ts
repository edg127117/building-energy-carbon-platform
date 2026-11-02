import { effectScope, ref } from 'vue'
import { flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { BuildingDeviceSummary, BuildingRuntimeRanking } from '../api/building-monitoring'
import { readBuildingDeviceSummary, readBuildingRuntimeRanking } from '../api/building-monitoring'
import { useBuildingMonitoring } from './use-building-monitoring'

vi.mock('../api/building-monitoring', () => ({ readBuildingDeviceSummary: vi.fn(), readBuildingRuntimeRanking: vi.fn() }))
const category = (counts: Record<string, number> = {}) => ({ counts, observed: Object.values(counts).reduce((sum, value) => sum + value, 0), unknown: 0, unsupported: 0, reasons: {} })
function summary(buildingId = 'B', overrides: Partial<BuildingDeviceSummary> = {}): BuildingDeviceSummary {
  return { buildingId, scope: 'ACTIVE_BOUND_DAIKIN_INDOOR', asOf: Date.now(), expiresAt: Date.now() + 120_000,
    total: 0, on: 0, off: 0, unknown: 0, unknownReasons: {}, modes: category({ fan: 0 }), speeds: category({ low: 0 }), ...overrides }
}
function ranking(buildingId = 'B', month = '2026-10'): BuildingRuntimeRanking {
  const [year, number] = month.split('-').map(Number), offset = 8 * 60 * 60 * 1000
  const start = Date.UTC(year, number - 1, 1) - offset, end = Date.UTC(year, number, 1) - offset
  return { buildingId, scope: 'ACTIVE_BOUND_DAIKIN_INDOOR', source: 'PLATFORM_OBSERVED', month, statisticsZone: 'Asia/Shanghai',
    asOf: Math.min(Date.now(), end - 1), periodStart: start, periodEnd: end, elapsedMillis: 0,
    totalDevices: 0, rankedDevices: 0, excludedReasons: {}, items: [] }
}
const deferred = <T,>() => {
  let resolve!: (value: T) => void, reject!: (reason?: unknown) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}

describe('useBuildingMonitoring', () => {
  beforeEach(() => {
    vi.useFakeTimers(); vi.setSystemTime(new Date('2026-10-31T15:59:40Z'))
    vi.mocked(readBuildingDeviceSummary).mockReset().mockImplementation(async id => summary(id))
    vi.mocked(readBuildingRuntimeRanking).mockReset().mockImplementation(async (id, month) => ranking(id, month))
  })
  afterEach(() => vi.useRealTimers())

  it('keeps last good summary while the independent ranking request recovers', async () => {
    const scope = effectScope(), id = ref('B')
    vi.mocked(readBuildingRuntimeRanking).mockRejectedValueOnce(new Error('offline'))
    const state = scope.run(() => useBuildingMonitoring(id))!
    await flushPromises()
    expect(state.rankingFailed.value).toBe(true)
    const prior = state.devices.value
    await vi.advanceTimersByTimeAsync(30_000)
    expect(state.devices.value).toEqual(prior)
    expect(state.rankingFailed.value).toBe(false)
    expect(readBuildingDeviceSummary).toHaveBeenCalledTimes(2)
    scope.stop()
  })

  it('isolates late responses on building change and aborts requests on change and disposal', async () => {
    const firstSummary = deferred<BuildingDeviceSummary>(), firstRanking = deferred<BuildingRuntimeRanking>()
    const currentSummary = deferred<BuildingDeviceSummary>(), currentRanking = deferred<BuildingRuntimeRanking>()
    vi.mocked(readBuildingDeviceSummary).mockReturnValueOnce(firstSummary.promise).mockReturnValueOnce(currentSummary.promise)
    vi.mocked(readBuildingRuntimeRanking).mockReturnValueOnce(firstRanking.promise).mockReturnValueOnce(currentRanking.promise)
    const scope = effectScope(), id = ref('B'), state = scope.run(() => useBuildingMonitoring(id))!
    const oldSummarySignal = vi.mocked(readBuildingDeviceSummary).mock.calls[0][1]!
    id.value = 'C'
    expect(oldSummarySignal.aborted).toBe(true)
    firstSummary.resolve(summary('B', { total: 3, on: 3, modes: category({ fan: 3 }), speeds: category({ low: 3 }) })); firstRanking.resolve(ranking('B'))
    await Promise.resolve(); await Promise.resolve()
    expect(state.devices.value).toBeUndefined()
    expect(readBuildingDeviceSummary).toHaveBeenLastCalledWith('C', expect.any(AbortSignal))
    const currentSignal = vi.mocked(readBuildingDeviceSummary).mock.calls.at(-1)![1]!
    scope.stop()
    expect(currentSignal.aborted).toBe(true)
  })

  it('hides a response that arrives after its backend expiry deadline', async () => {
    const startedAt = Date.now(), pending = deferred<BuildingDeviceSummary>()
    vi.mocked(readBuildingDeviceSummary).mockReturnValueOnce(pending.promise)
    const scope = effectScope(), id = ref('B'), state = scope.run(() => useBuildingMonitoring(id))!
    vi.setSystemTime(startedAt + 20_000)
    pending.resolve(summary('B', { asOf: startedAt, expiresAt: startedAt + 10_000 }))
    await flushPromises()
    expect(state.devices.value).toBeUndefined()
    expect(state.updatedAt.value).toBeUndefined()
    expect(state.summaryExpired.value).toBe(true)
    expect(state.summaryLastSuccess.value).toBe(startedAt)
    scope.stop()
  })

  it('hides an expired summary but retains last success metadata and refreshes ranking for the Beijing month', async () => {
    vi.setSystemTime(new Date('2026-10-31T15:59:59.999Z'))
    const startNow = Date.now()
    vi.mocked(readBuildingDeviceSummary).mockImplementationOnce(async id => summary(id, { asOf: startNow, expiresAt: startNow + 10_000 }))
      .mockRejectedValueOnce(new Error('refresh failed'))
    const scope = effectScope(), id = ref('B'), state = scope.run(() => useBuildingMonitoring(id))!
    await flushPromises()
    expect(state.updatedAt.value).toBe(startNow)
    await vi.advanceTimersByTimeAsync(1)
    await flushPromises()
    expect(state.month.value).toBe('2026-11')
    expect(readBuildingRuntimeRanking).toHaveBeenCalledWith('B', '2026-11', expect.any(AbortSignal))
    await vi.advanceTimersByTimeAsync(9_999)
    expect(state.devices.value).toBeUndefined()
    expect(state.updatedAt.value).toBeUndefined()
    expect(state.summaryExpired.value).toBe(true)
    expect(state.summaryLastSuccess.value).toBe(startNow)
    scope.stop()
  })
})
