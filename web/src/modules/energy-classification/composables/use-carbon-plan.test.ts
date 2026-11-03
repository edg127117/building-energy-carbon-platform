import { beforeEach, expect, it, vi } from 'vitest'
import { effectScope, ref } from 'vue'
import { carbonPlanApi, type CarbonPlanBaseline, type CarbonPlanVersion } from '../api/carbon-plan'
import { useCarbonPlan } from './use-carbon-plan'

vi.mock('../api/carbon-plan', () => ({ carbonPlanApi: { versions: vi.fn(), create: vi.fn(), save: vi.fn(), transition: vi.fn() } }))
const version = (id: string, revision = 0): CarbonPlanVersion => ({ versionId: id, revision, status: 'DRAFT', configuration: null } as CarbonPlanVersion)
beforeEach(() => vi.resetAllMocks())
it('ignores late building/year results and immediately clears old selection', async () => {
  let old!: (v: CarbonPlanVersion[]) => void
  vi.mocked(carbonPlanApi.versions).mockImplementation((_, y) => y === 2025 ? new Promise(resolve => { old = resolve }) : Promise.resolve([version('new')]))
  const scope = effectScope(), building = ref('b'), year = ref(2025)
  const w = scope.run(() => useCarbonPlan(() => building.value, () => year.value))!
  year.value = 2026
  await vi.waitFor(() => expect(w.selectedId.value).toBe('new'))
  old([version('old')])
  await Promise.resolve()
  expect(w.selectedId.value).toBe('new')
  building.value = ''
  expect(w.selected.value).toBeNull()
  scope.stop()
})
it('invalidates late baseline preview when selected version changes', async () => {
  vi.mocked(carbonPlanApi.versions).mockResolvedValue([version('a'), version('b')])
  const scope = effectScope(), w = scope.run(() => useCarbonPlan(() => 'building', () => 2026))!
  await vi.waitFor(() => expect(w.selectedId.value).toBe('a'))
  let done!: (v: CarbonPlanBaseline) => void
  const pending = w.preview(() => new Promise(resolve => { done = resolve }))
  w.selectedId.value = 'b'
  done({ annualBaseline: '999' } as CarbonPlanBaseline)
  await pending
  expect(w.baseline.value).toBeNull()
  expect(w.previewing.value).toBe(false)
  scope.stop()
})
it('uses returned revision after save and ignores old mutations after building change', async () => {
  vi.mocked(carbonPlanApi.versions).mockResolvedValueOnce([version('a', 3)]).mockResolvedValue([version('a', 4)])
  vi.mocked(carbonPlanApi.save).mockResolvedValue(version('a', 4))
  const scope = effectScope(), building = ref('one'), w = scope.run(() => useCarbonPlan(() => building.value, () => 2026))!
  await vi.waitFor(() => expect(w.selected.value?.revision).toBe(3))
  w.baseline.value = { annualBaseline: '160' } as CarbonPlanBaseline
  w.reductionPercent.value = '15'
  await w.save('测试')
  expect(carbonPlanApi.save).toHaveBeenCalledWith('one', expect.objectContaining({ revision: 3 }), expect.objectContaining({ reductionPercent: '15' }), '测试')
  expect(w.selected.value?.revision).toBe(4)
  let done!: (v: CarbonPlanVersion) => void
  vi.mocked(carbonPlanApi.create).mockImplementation(() => new Promise(resolve => { done = resolve }))
  const pending = w.create('旧建筑创建')
  vi.mocked(carbonPlanApi.versions).mockResolvedValue([version('new-building')])
  building.value = 'two'
  await vi.waitFor(() => expect(w.selectedId.value).toBe('new-building'))
  done(version('stale-created'))
  await pending
  expect(w.selectedId.value).toBe('new-building')
  scope.stop()
})
