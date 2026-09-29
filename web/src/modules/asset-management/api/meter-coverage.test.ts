import { describe, expect, it, vi } from 'vitest'
import { requestApi } from '@/infrastructure/http/public'
import { getMeterCoverage, listMeterCoverageCandidates, listMeterCoverageHistory, listMeterCoverages, updateMeterCoverage } from './meter-coverage'

vi.mock('@/infrastructure/http/public', () => ({ requestApi: vi.fn() }))

describe('电表档案 API 契约', () => {
  it('按设备编码路径读取、保存和读取历史及候选设备', async () => {
    vi.mocked(requestApi).mockResolvedValue({} as never)
    await getMeterCoverage('M/1')
    await updateMeterCoverage('M/1', { expectedRevision: 2, installationSpaceId: 'S1', scopeLabel: '冷站', targetEquipmentIds: ['E1', 'E2'], reason: '范围核对' })
    await listMeterCoverageHistory('M/1', { page: 2, size: 20 })
    await listMeterCoverageCandidates('M/1', { page: 1, size: 20, keyword: '冷机' })
    expect(vi.mocked(requestApi).mock.calls.map(([config]) => config)).toEqual([
      { method: 'get', url: '/v1/assets/equipment/M%2F1/meter-coverage' },
      { method: 'put', url: '/v1/assets/equipment/M%2F1/meter-coverage', data: { expectedRevision: 2, installationSpaceId: 'S1', scopeLabel: '冷站', targetEquipmentIds: ['E1', 'E2'], reason: '范围核对' } },
      { method: 'get', url: '/v1/assets/equipment/M%2F1/meter-coverage/history', params: { page: 2, size: 20 } },
      { method: 'get', url: '/v1/assets/equipment/M%2F1/meter-coverage/candidates', params: { page: 1, size: 20, keyword: '冷机' } },
    ])
  })

  it('批量列表查询传递当前页表计 ID 和建筑范围', async () => {
    vi.mocked(requestApi).mockResolvedValue([] as never)
    await listMeterCoverages({ buildingId: 'B1', equipmentIds: ['M1', 'M2'] })
    expect(requestApi).toHaveBeenCalledWith({ method: 'get', url: '/v1/assets/meter-coverages', params: { buildingId: 'B1', equipmentIds: 'M1,M2' } })
  })
})
