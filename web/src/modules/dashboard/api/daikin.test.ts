import { describe, expect, it, vi } from 'vitest'
const { requestApi } = vi.hoisted(() => ({ requestApi: vi.fn().mockResolvedValue({}) }))
vi.mock('@/infrastructure/http/public', () => ({ requestApi }))
import { daikinApi } from './daikin'

describe('manufacturer platform query contracts', () => {
  it('encodes device IDs and preserves history bounds and zero cursor', async () => {
    await daikinApi.history('a/b', 'roomTemp', 0, 100, 0)
    expect(requestApi).toHaveBeenLastCalledWith({ method: 'GET', url: '/v1/hvac-monitoring/devices/a%2Fb/temperatures', params: { field: 'roomTemp', from: 0, to: 100, after: 0, limit: 1000 } })
  })
  it('uses separate current/history alarm resources and independent runtime granularity', async () => {
    await daikinApi.exceptions('b/a', true, 'cursor')
    expect(requestApi).toHaveBeenLastCalledWith({ method: 'GET', url: '/v1/hvac-monitoring/buildings/b%2Fa/exceptions/history', params: { cursor: 'cursor', limit: 50 } })
    await daikinApi.runtime('a', 'MONTH', 'hash')
    expect(requestApi).toHaveBeenLastCalledWith({ method: 'GET', url: '/v1/hvac-monitoring/devices/a/runtime', params: { granularity: 'MONTH', cursor: 'hash', limit: 50 } })
  })
})
