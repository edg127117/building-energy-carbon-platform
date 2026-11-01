import { beforeEach, describe, expect, it, vi } from 'vitest'
import { requestApi } from '@/infrastructure/http/public'
import { carbonHourlyApi } from './carbon-hourly'

vi.mock('@/infrastructure/http/public', () => ({ requestApi: vi.fn() }))

describe('小时碳监测读取接口契约', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(requestApi).mockResolvedValue({} as never)
  })

  it('只对编码后的建筑路径发起携带日期和取消信号的 GET', async () => {
    const controller = new AbortController()

    await carbonHourlyApi.getHourly('B/01', '2026-10-27', controller.signal)

    expect(requestApi).toHaveBeenCalledTimes(1)
    expect(requestApi).toHaveBeenCalledWith(expect.objectContaining({
      method: 'get',
      url: '/v1/carbon-management/buildings/B%2F01/hourly',
      params: { date: '2026-10-27' },
      signal: controller.signal,
    }))
    expect(Object.keys(carbonHourlyApi)).toEqual(['getHourly'])
  })
})
