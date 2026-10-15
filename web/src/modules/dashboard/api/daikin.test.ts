import { afterEach, describe, expect, it, vi } from 'vitest'
const { requestApi } = vi.hoisted(() => ({ requestApi: vi.fn().mockResolvedValue({}) }))
vi.mock('@/infrastructure/http/public', () => ({ requestApi }))
import { daikinApi, newDaikinFastWatchId } from './daikin'

describe('manufacturer platform query contracts', () => {
  afterEach(() => vi.unstubAllGlobals())
  it('sends all filters including false without filtering only the current page', async () => {
    await daikinApi.devices('b', 2, 'INDOOR', '301', 'space', 'STALE', false)
    expect(requestApi).toHaveBeenLastCalledWith({method:'GET',url:'/v1/hvac-monitoring/buildings/b/devices',params:{page:2,size:20,kind:'INDOOR',keyword:'301',spaceId:'space',state:'STALE',hasException:false}})
  })
  it('requests the complete bounded filter scope when batch selection needs every page', async () => {
    await daikinApi.devices('b', 1, 'INDOOR', '301', 'space', '', undefined, 100)
    expect(requestApi).toHaveBeenLastCalledWith({method:'GET',url:'/v1/hvac-monitoring/buildings/b/devices',params:{page:1,size:100,kind:'INDOOR',keyword:'301',spaceId:'space',state:undefined,hasException:undefined}})
  })
  it('encodes device IDs and preserves history bounds and zero cursor', async () => {
    await daikinApi.history('a/b', 'roomTemp', 0, 100, 0)
    expect(requestApi).toHaveBeenLastCalledWith({ method: 'GET', url: '/v1/hvac-monitoring/devices/a%2Fb/temperatures', params: { field: 'roomTemp', from: 0, to: 100, after: 0, limit: 1000 } })
  })
  it('starts and stops the browser fast-watch with its validated watch ID', async () => {
    await daikinApi.startFastWatch('a/b', 'watch-1234')
    expect(requestApi).toHaveBeenLastCalledWith({ method: 'POST', url: '/v1/hvac-monitoring/devices/a%2Fb/fast-watch', data: { watchId: 'watch-1234' } })
    await daikinApi.stopFastWatch('a/b', 'watch-1234')
    expect(requestApi).toHaveBeenLastCalledWith({ method: 'DELETE', url: '/v1/hvac-monitoring/devices/a%2Fb/fast-watch', params: { watchId: 'watch-1234' } })
  })
  it('uses a technical watch ID when the browser lacks randomUUID', () => {
    vi.stubGlobal('crypto', {})
    expect(newDaikinFastWatchId()).toMatch(/^web-fast-watch-\d+-[a-z0-9]+$/)
  })
  it('uses separate current/history alarm resources and independent runtime granularity', async () => {
    await daikinApi.exceptions('b/a', true, 'cursor')
    expect(requestApi).toHaveBeenLastCalledWith({ method: 'GET', url: '/v1/hvac-monitoring/buildings/b%2Fa/exceptions/history', params: { cursor: 'cursor', limit: 50 } })
    await daikinApi.runtime('a', 'MONTH', 'hash')
    expect(requestApi).toHaveBeenLastCalledWith({ method: 'GET', url: '/v1/hvac-monitoring/devices/a/runtime', params: { granularity: 'MONTH', cursor: 'hash', limit: 50 } })
    await daikinApi.observedRuntime('a/b', 'DAY', 0)
    expect(requestApi).toHaveBeenLastCalledWith({ method: 'GET', url: '/v1/hvac-monitoring/devices/a%2Fb/observed-runtime', params: { granularity: 'DAY', before: 0, limit: 50 } })
  })
})
