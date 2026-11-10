import { effectScope, nextTick, ref } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import { readAnnualOverview, type AnnualOverview, type AnnualQuery } from '../api/annual-overview'
import { useAnnualOverview } from './use-annual-overview'
vi.mock('../api/annual-overview', async importOriginal => ({ ...await importOriginal<typeof import('../api/annual-overview')>(), readAnnualOverview: vi.fn() }))
describe('年度概览请求生命周期', () => {
  it('切换建筑清空旧值并阻止迟到响应，空建筑不请求', async () => {
    let resolve!: (value: AnnualOverview) => void
    vi.mocked(readAnnualOverview).mockImplementationOnce(() => new Promise(r => { resolve = r }))
    vi.mocked(readAnnualOverview).mockRejectedValueOnce(new Error('forbidden'))
    const query = ref<AnnualQuery | null>({ buildingId: 'A', year: 2024, mode: 'MONTHLY_YTD', throughMonth: 2, timezoneId: 'Asia/Shanghai' })
    const scope = effectScope()
    const state = scope.run(() => useAnnualOverview(() => query.value))!
    query.value = { ...query.value!, buildingId: 'B' }
    await nextTick()
    await nextTick()
    resolve({ buildingId: 'A' } as AnnualOverview)
    await nextTick()
    expect(state.result.value).toBeNull()
    expect(state.error.value).toBeInstanceOf(Error)
    query.value = null
    await nextTick()
    expect(state.loading.value).toBe(false)
    expect(state.error.value).toBeNull()
    expect(readAnnualOverview).toHaveBeenCalledTimes(2)
    scope.stop()
  })
})
