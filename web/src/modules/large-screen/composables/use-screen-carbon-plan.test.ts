import { expect, it, vi } from 'vitest'
import { effectScope, ref } from 'vue'
import { carbonPlanApi, type CarbonPlanResponse } from '@/modules/energy-classification/public'
import { useScreenCarbonPlan } from './use-screen-carbon-plan'

vi.mock('@/modules/energy-classification/public', async original => ({ ...await original<typeof import('@/modules/energy-classification/public')>(), carbonPlanApi: { query: vi.fn() } }))
it('ignores late period and building responses even if transport ignores abort', async () => {
  let old!: (r: CarbonPlanResponse) => void
  vi.mocked(carbonPlanApi.query).mockImplementation((b, y, through) => through === undefined
    ? new Promise(resolve => { old = resolve })
    : Promise.resolve({ buildingId: b, year: y, throughMonth: through, plan: null, months: [] } as unknown as CarbonPlanResponse))
  const scope = effectScope(), building = ref('b1'), month = ref<string | undefined>(undefined)
  const w = scope.run(() => useScreenCarbonPlan(() => building.value, () => 2026, () => month.value))!
  month.value = '2026-02'
  await vi.waitFor(() => expect(w.response.value?.throughMonth).toBe('2026-02'))
  old({ buildingId: 'b1', throughMonth: '2026-09', plan: null, months: [] } as unknown as CarbonPlanResponse)
  await Promise.resolve()
  expect(w.response.value?.throughMonth).toBe('2026-02')
  building.value = 'b2'
  expect(w.response.value).toBeNull()
  await vi.waitFor(() => expect(w.response.value?.buildingId).toBe('b2'))
  scope.stop()
})
