import { describe, expect, it, vi } from 'vitest'
import { requestApi } from '@/infrastructure/http/public'
import { readBuildingDeviceSummary, readBuildingRuntimeRanking } from './building-monitoring'

vi.mock('@/infrastructure/http/public', () => ({ requestApi: vi.fn() }))

describe('building monitoring API', () => {
  it('uses scoped GET endpoints, bounded transport timeouts and the server top-five limit', async () => {
    const signal = new AbortController().signal
    vi.mocked(requestApi).mockResolvedValueOnce({}).mockResolvedValueOnce({})
    await readBuildingDeviceSummary('B/1', signal)
    await readBuildingRuntimeRanking('B/1', '2026-10', signal)
    expect(requestApi).toHaveBeenNthCalledWith(1, {
      method: 'get', url: '/v1/hvac-monitoring/buildings/B%2F1/device-summary', timeout: 20_000, signal,
    })
    expect(requestApi).toHaveBeenNthCalledWith(2, {
      method: 'get', url: '/v1/hvac-monitoring/buildings/B%2F1/runtime-ranking', params: { month: '2026-10', limit: 5 }, timeout: 20_000, signal,
    })
  })
})
