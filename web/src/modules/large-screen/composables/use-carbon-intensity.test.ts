import { describe, it, expect, vi, beforeEach } from 'vitest'
import { effectScope, ref } from 'vue'
import { useCarbonIntensity } from './use-carbon-intensity'
import { readCarbonIntensity, type CarbonIntensityView, type IntensityQuery } from '../api/carbon-intensity'
import { intensityView } from '../models/carbon-intensity.test-fixture'

vi.mock('../api/carbon-intensity', () => ({ readCarbonIntensity: vi.fn() }))
const settle = async () => { await Promise.resolve(); await Promise.resolve(); await Promise.resolve() }
describe('carbon intensity request ownership', () => {
  beforeEach(() => { vi.mocked(readCarbonIntensity).mockReset() })
  it('keeps the verified card when another card recreates an equivalent query object', async () => {
    vi.mocked(readCarbonIntensity).mockResolvedValue(intensityView())
    const query = ref<IntensityQuery | null>({ buildingId: 'B', mode: 'FORMAL_YEAR', year: 2025, timezoneId: 'Asia/Shanghai' })
    const scope = effectScope()
    const state = scope.run(() => useCarbonIntensity(query, ref({ kind: 'year', value: '2025' })))!
    await settle()
    const verified = state.result.value
    expect(verified).not.toBeNull()
    query.value = { ...query.value! }
    expect(state.result.value).toBe(verified)
    await settle()
    expect(readCarbonIntensity).toHaveBeenCalledTimes(1)
    expect(state.loading.value).toBe(false)
    scope.stop()
  })
  it('clears old values immediately, rejects late responses, and stops updates on disposal', async () => {
    const pending: Array<(v: CarbonIntensityView) => void> = []
    vi.mocked(readCarbonIntensity).mockImplementation(() => new Promise(resolve => pending.push(resolve)))
    const query = ref<IntensityQuery | null>({ buildingId: 'B', mode: 'FORMAL_YEAR', year: 2025, timezoneId: 'Asia/Shanghai' })
    const period = ref({ kind: 'year' as const, value: '2025' })
    const scope = effectScope(), state = scope.run(() => useCarbonIntensity(query, period))!
    query.value = { ...query.value!, buildingId: 'C' }
    expect(state.result.value).toBeNull()
    pending[1](intensityView({ buildingId: 'C' })); await settle()
    expect(state.result.value?.buildingId).toBe('C')
    pending[0](intensityView()); await settle()
    expect(state.result.value?.buildingId).toBe('C')
    query.value = null
    expect(state.result.value).toBeNull(); expect(state.loading.value).toBe(false)
    query.value = { buildingId: 'B', mode: 'FORMAL_YEAR', year: 2025, timezoneId: 'Asia/Shanghai' }
    scope.stop(); pending[2](intensityView()); await settle()
    expect(state.result.value).toBeNull()
  })
  it('marks mismatched or failed requests and allows a fresh retry', async () => {
    vi.mocked(readCarbonIntensity).mockResolvedValueOnce(intensityView({ buildingId: 'wrong' })).mockResolvedValueOnce(intensityView())
    const scope = effectScope()
    const state = scope.run(() => useCarbonIntensity(ref({ buildingId: 'B', mode: 'FORMAL_YEAR', year: 2025, timezoneId: 'Asia/Shanghai' }), ref({ kind: 'year', value: '2025' })))!
    await settle(); expect(state.failed.value).toBe(true); expect(state.data.value).toBeUndefined()
    await state.reload(); expect(state.failed.value).toBe(false); expect(state.data.value?.intensity.data.areaPercentage).toBe(150)
    scope.stop()
  })
})
