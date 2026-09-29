import { describe, expect, it, vi } from 'vitest'
import { getMeterCoverage, listMeterCoverageCandidates, listMeterCoverageHistory, listMeterCoverages, updateMeterCoverage } from '../api/meter-coverage'
import { TransportError } from '@/infrastructure/http/public'
import { useMeterCoverage } from './use-meter-coverage'
import type { MeterCoverageView, MeterTarget } from '../models/meter-coverage'

vi.mock('../api/meter-coverage', () => ({
  getMeterCoverage: vi.fn(),
  listMeterCoverageCandidates: vi.fn(),
  listMeterCoverageHistory: vi.fn(),
  listMeterCoverages: vi.fn(),
  updateMeterCoverage: vi.fn(),
}))

const view = (equipmentId: string, targets: MeterTarget[] = []): MeterCoverageView => ({
  equipmentId, revision: 3, effectiveAt: '2026-09-29T12:00:00Z', installationSpaceId: null,
  installationSpaceName: null, scopeLabel: '冷站范围', reason: null, targets,
  quantityMode: 'GROUP_ONLY', aggregationPolicy: 'SEPARATE_ONLY',
})
const deferred = <T,>() => {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(done => { resolve = done })
  return { promise, resolve }
}

describe('电表计量范围状态', () => {
  it('设备切换时丢弃较早候选请求响应', async () => {
    const late = deferred<{ page: number; size: number; total: number; items: MeterTarget[] }>()
    vi.mocked(listMeterCoverageCandidates).mockImplementationOnce(() => late.promise as never)
    vi.mocked(listMeterCoverageCandidates).mockResolvedValueOnce({ page: 1, size: 10, total: 1, items: [{ equipmentId: 'E2', equipmentCode: 'EQ-2', equipmentName: '冷机二号', spaceId: null, spaceName: null, active: true }] })
    const state = useMeterCoverage()
    const first = state.loadCandidates('M1', { page: 1, size: 10 })
    await state.loadCandidates('M2', { page: 1, size: 10 })
    late.resolve({ page: 1, size: 10, total: 1, items: [{ equipmentId: 'E1', equipmentCode: 'EQ-1', equipmentName: '冷机一号', spaceId: null, spaceName: null, active: true }] })
    await first
    expect(state.candidates.value.items.map(item => item.equipmentId)).toEqual(['E2'])
  })

  it('切换电表后清除旧历史和保存错误，迟到响应不能污染新档案', async () => {
    const lateHistory = deferred<{ page: number; size: number; total: number; items: MeterCoverageView[] }>()
    vi.mocked(listMeterCoverageHistory).mockImplementationOnce(() => lateHistory.promise as never)
    vi.mocked(getMeterCoverage).mockResolvedValueOnce(view('M2'))
    vi.mocked(updateMeterCoverage).mockRejectedValueOnce(new TransportError('request', 409, undefined, 'METER_COVERAGE_CONFLICT'))
    const state = useMeterCoverage()
    state.coverage.value = view('M1')
    const historyRequest = state.loadHistory('M1')
    const saveRequest = state.saveCoverage('M1', { expectedRevision: 3, installationSpaceId: null, scopeLabel: '范围', targetEquipmentIds: [], reason: '说明' })
    await state.loadCoverage('M2')
    lateHistory.resolve({ page: 1, size: 20, total: 1, items: [view('M1')] })
    await historyRequest
    await expect(saveRequest).rejects.toBeDefined()
    expect(state.coverage.value?.equipmentId).toBe('M2')
    expect(state.history.value.items).toEqual([])
    expect(state.saveError.value).toBeNull()
    expect(state.saveLoading.value).toBe(false)
  })

  it('并发冲突保留错误，且一只表可以保存多台被测设备', async () => {
    const targets = [
      { equipmentId: 'E1', equipmentCode: 'EQ-1', equipmentName: '冷机一号', spaceId: 'S1', spaceName: '机房', active: true },
      { equipmentId: 'E2', equipmentCode: 'EQ-2', equipmentName: '冷机二号', spaceId: 'S1', spaceName: '机房', active: true },
    ]
    vi.mocked(updateMeterCoverage).mockRejectedValueOnce(new TransportError('request', 409, undefined, 'METER_COVERAGE_CONFLICT'))
    const state = useMeterCoverage()
    state.coverage.value = view('M1')
    const save = state.saveCoverage('M1', { expectedRevision: 3, installationSpaceId: 'S1', scopeLabel: '冷站范围', targetEquipmentIds: targets.map(target => target.equipmentId), reason: '补全范围' })
    await expect(save).rejects.toBeDefined()
    expect(state.saveError.value?.message).toBe('档案版本已变化，刷新后检查最新内容再保存。')
    vi.mocked(updateMeterCoverage).mockResolvedValueOnce(view('M1', targets))
    const saved = await state.saveCoverage('M1', { expectedRevision: 3, installationSpaceId: 'S1', scopeLabel: '冷站范围', targetEquipmentIds: ['E1', 'E2'], reason: '补全范围' })
    expect(updateMeterCoverage).toHaveBeenLastCalledWith('M1', expect.objectContaining({ targetEquipmentIds: ['E1', 'E2'] }))
    expect(saved.targets).toHaveLength(2)
    expect(state.coverage.value?.targets.map(target => target.equipmentId)).toEqual(['E1', 'E2'])
  })

  it('批量覆盖只请求当前页上限内的表计，并提供列表显示数据', async () => {
    const items = [view('M1', [{ equipmentId: 'E1', equipmentCode: 'EQ-1', equipmentName: '设备一', spaceId: null, spaceName: null, active: true }])]
    vi.mocked(listMeterCoverages).mockResolvedValueOnce(items)
    vi.mocked(listMeterCoverages).mockResolvedValueOnce([])
    const state = useMeterCoverage()
    await state.loadListCoverages('B1', Array.from({ length: 120 }, (_, i) => `M${i}`))
    expect(listMeterCoverages).toHaveBeenNthCalledWith(1, { buildingId: 'B1', equipmentIds: Array.from({ length: 100 }, (_, i) => `M${i}`) })
    expect(listMeterCoverages).toHaveBeenNthCalledWith(2, { buildingId: 'B1', equipmentIds: Array.from({ length: 20 }, (_, i) => `M${i + 100}`) })
    expect(state.listCoverages.value[0].targets).toHaveLength(1)
  })
})
