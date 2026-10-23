import { beforeEach, describe, expect, it, vi } from 'vitest'
import { requestApi } from '@/infrastructure/http/public'
import { telemetryConflictApi } from './telemetry-conflicts'

vi.mock('@/infrastructure/http/public', () => ({ requestApi: vi.fn() }))

describe('遥测冲突接口契约', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(requestApi).mockResolvedValue({} as never)
  })

  it('传递建筑范围、并发修订和保留原值的显式空证据', async () => {
    await telemetryConflictApi.list({ buildingId: 'B/01', state: 'OPEN', page: 2, size: 20 })
    await telemetryConflictApi.detail('P/01', 1735689600000)
    await telemetryConflictApi.owner('B/01')
    await telemetryConflictApi.assignOwner({ buildingId: 'B/01', userId: 42 })
    await telemetryConflictApi.resolve('P/01', 1735689600000, {
      evidenceId: null,
      expectedRevision: 3,
      reason: '现场复核',
      evidenceReference: 'EV-1',
    })

    expect(requestApi).toHaveBeenNthCalledWith(1, expect.objectContaining({
      method: 'GET', url: '/v1/telemetry-conflicts', params: { buildingId: 'B/01', state: 'OPEN', page: 2, size: 20 },
    }))
    expect(requestApi).toHaveBeenNthCalledWith(2, expect.objectContaining({ method: 'GET', url: '/v1/telemetry-conflicts/P%2F01/1735689600000' }))
    expect(requestApi).toHaveBeenNthCalledWith(3, expect.objectContaining({ method: 'GET', url: '/v1/telemetry-conflicts/owners', params: { buildingId: 'B/01' } }))
    expect(requestApi).toHaveBeenNthCalledWith(4, expect.objectContaining({ method: 'PUT', url: '/v1/telemetry-conflicts/owners', data: { buildingId: 'B/01', userId: 42 } }))
    expect(requestApi).toHaveBeenNthCalledWith(5, expect.objectContaining({
      method: 'POST', url: '/v1/telemetry-conflicts/P%2F01/1735689600000/resolve',
      data: { evidenceId: null, expectedRevision: 3, reason: '现场复核', evidenceReference: 'EV-1' },
    }))
  })
})
