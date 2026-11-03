import { beforeEach, describe, expect, it, vi } from 'vitest'
import { requestApi } from '@/infrastructure/http/public'
import { readCarbonIntensity } from './carbon-intensity'

vi.mock('@/infrastructure/http/public', () => ({ requestApi: vi.fn() }))

beforeEach(() => vi.mocked(requestApi).mockReset())

describe('大屏碳强度准备式读取', () => {
  it('使用 summary 路由并返回后端 READY 数据', async () => {
    const view = { buildingId: 'B/01', mode: 'MONTH', actual: { value: '1' } }
    vi.mocked(requestApi).mockResolvedValue({ contractVersion: 'CARBON_DISPLAY_QUERY_V1', kind: 'CARBON_INTENSITY',
      buildingId: 'B/01', queryKey: 'q1', sourceVersion: 'v1', state: 'READY', data: view, retryAfterSeconds: 2 } as never)
    const controller = new AbortController()

    await expect(readCarbonIntensity({ buildingId: 'B/01', mode: 'MONTH', year: 2026, month: 9, timezoneId: 'Asia/Shanghai' }, controller.signal))
      .resolves.toEqual(view)
    expect(requestApi).toHaveBeenCalledWith({ method: 'get', url: '/v1/carbon-intensity/buildings/B%2F01/summary',
      params: { mode: 'MONTH', year: 2026, month: 9, timezoneId: 'Asia/Shanghai' }, signal: expect.any(AbortSignal) })
  })
})
