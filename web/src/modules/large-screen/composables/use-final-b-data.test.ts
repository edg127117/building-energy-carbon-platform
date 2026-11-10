import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { MonthlyComparisonResult } from '@/modules/energy-reporting/public'
import { useFinalBData } from './use-final-b-data'

const api = vi.hoisted(() => ({ buildings: vi.fn(), monthly: vi.fn(), detail: vi.fn(), evidence: vi.fn() }))
vi.mock('@/modules/dashboard/public', () => ({ listAccessibleBuildings: api.buildings }))
vi.mock('@/modules/energy-reporting/public', () => ({ queryMonthlyComparison: api.monthly }))
vi.mock('@/modules/energy-classification/public', () => ({ screenCarbonQueries: { getCalculation: api.detail, getEvidence: api.evidence } }))
vi.mock('@/locales', () => ({ t: (key: string) => key }))
const wrappers: Array<ReturnType<typeof mount>> = []
function report(buildingId: string, month: string): MonthlyComparisonResult {
  const [year, value] = month.split('-').map(Number)
  const current = { month: `${month}-01`, startInclusive: new Date(Date.UTC(year, value - 1, 1) - 28800000).toISOString(), endExclusive: new Date(Date.UTC(year, value, 1) - 28800000).toISOString(), metrics: [] }
  return { buildingId, timezoneId: 'Asia/Shanghai', current, previousMonth: current, sameMonthLastYear: current, comparisons: [] }
}
async function harness(code = 'CXG') {
  let state!: ReturnType<typeof useFinalBData>
  const wrapper = mount(defineComponent({ setup() { state = useFinalBData(code); return () => null } }))
  wrappers.push(wrapper)
  await flushPromises()
  return { state, wrapper }
}
beforeEach(() => {
  vi.clearAllMocks()
  api.buildings.mockResolvedValue([{ buildingId: 'B1', buildingCode: 'CXG', buildingName: '创新港' }, { buildingId: 'B2', buildingCode: 'OTHER', buildingName: '其他楼' }])
  api.monthly.mockImplementation(async (id, month) => report(id, month))
})
afterEach(() => { wrappers.splice(0).forEach(wrapper => wrapper.unmount()) })

describe('B screen monthly data lifecycle', () => {
  it('loads authorized configured building with bounded requests and no prototype fallback', async () => {
    let active = 0, maximum = 0
    api.monthly.mockImplementation(async (id, month) => { active++; maximum = Math.max(maximum, active); await Promise.resolve(); active--; return report(id, month) })
    const { state } = await harness()
    expect(state.buildingId.value).toBe('B1')
    expect(maximum).toBeLessThanOrEqual(3)
    expect(api.monthly.mock.calls.every(call => call[0] === 'B1')).toBe(true)
    expect(state.data.value.annual).toBeUndefined()
    expect(state.data.value.devices).toBeUndefined()
    expect(state.data.value.usage?.data.electricity.every(value => value === null)).toBe(true)
    expect(state.loading.value).toBe(false)
    const count = api.monthly.mock.calls.length
    await state.reload()
    expect(api.monthly).toHaveBeenCalledTimes(count)
  })
  it('does not query an unconfigured or inaccessible building', async () => {
    const { state } = await harness('NOT_AUTHORIZED')
    expect(state.buildingId.value).toBe('')
    expect(api.monthly).not.toHaveBeenCalled()
    state.buildingId.value = 'UNAUTHORIZED'
    await flushPromises()
    expect(api.monthly).not.toHaveBeenCalled()
  })
  it('rejects response scope mismatch while keeping errors separate from zero values', async () => {
    api.monthly.mockImplementation(async (_id, month) => report('OTHER', month))
    const { state } = await harness()
    expect(state.failedMonths.value.length).toBeGreaterThan(0)
    expect(state.reports.value.size).toBe(0)
    expect(state.data.value.previousMonth).toBeUndefined()
  })
  it('aborts old work and ignores late responses after building change and unmount', async () => {
    const deferred: Array<() => void> = []
    const signals: AbortSignal[] = []
    api.monthly.mockImplementation((id, month, signal) => {
      signals.push(signal)
      return id === 'B1' ? new Promise(resolve => { deferred.push(() => resolve(report(id, month))) }) : Promise.resolve(report(id, month))
    })
    const { state, wrapper } = await harness()
    expect(state.loading.value).toBe(true)
    state.buildingId.value = 'B2'
    await flushPromises()
    deferred.forEach(resolve => resolve())
    await flushPromises()
    expect(signals[0]?.aborted).toBe(true)
    expect([...state.reports.value.values()].every(result => result.buildingId === 'B2')).toBe(true)
    wrapper.unmount()
    expect(signals.at(-1)?.aborted).toBe(true)
  })
})
