import { beforeEach, describe, expect, it, vi } from 'vitest'
import { requestApi } from '@/infrastructure/http/public'
import { energyProfileBatchApi } from './energy-profile-batch'

vi.mock('@/infrastructure/http/public', () => ({ requestApi: vi.fn() }))

describe('批量能源测点专业属性接口契约', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(requestApi).mockResolvedValue({} as never)
  })

  it('按分页读取建筑属性目录和服务端选项', async () => {
    const controller = new AbortController()
    await energyProfileBatchApi.listProfiles('B/01', 2, 100, undefined, controller.signal)
    await energyProfileBatchApi.listProfiles('B/01', 1, 20, 'P/01', controller.signal)
    await energyProfileBatchApi.options(controller.signal)

    expect(requestApi).toHaveBeenNthCalledWith(1, expect.objectContaining({ method: 'get', url: '/v1/energy-point-profiles', params: { buildingId: 'B/01', page: 2, size: 100 }, signal: controller.signal }))
    expect(requestApi).toHaveBeenNthCalledWith(2, expect.objectContaining({ method: 'get', url: '/v1/energy-point-profiles', params: { buildingId: 'B/01', page: 1, size: 20, pointId: 'P/01' }, signal: controller.signal }))
    expect(requestApi).toHaveBeenNthCalledWith(3, expect.objectContaining({ method: 'get', url: '/v1/energy-point-profiles/options', signal: controller.signal }))
  })

  it('用同一批次契约执行预检或原子写入', async () => {
    const request = {
      buildingId: 'B-1', rows: [{ pointId: 'P-1', profileId: 'PROFILE-1', expectedRevision: 3 }, { pointId: 'P-2', profileId: null, expectedRevision: null }],
      energyType: 'ELECTRICITY', energySubtype: 'GRID_PURCHASED', valueSemantics: 'CUMULATIVE', reportingPeriod: 'MONTH',
      annualSummary: true, confirmationStatus: 'PENDING_EXPERT', evidenceReference: '批量依据', preview: true,
    }

    await energyProfileBatchApi.batch(request)
    await energyProfileBatchApi.batch({ ...request, preview: false })

    expect(requestApi).toHaveBeenNthCalledWith(1, expect.objectContaining({ method: 'post', url: '/v1/energy-point-profiles/batch', data: request }))
    expect(requestApi).toHaveBeenNthCalledWith(2, expect.objectContaining({ method: 'post', url: '/v1/energy-point-profiles/batch', data: { ...request, preview: false } }))
  })
})
