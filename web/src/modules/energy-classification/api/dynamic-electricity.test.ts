import { beforeEach, describe, expect, it, vi } from 'vitest'
import { requestApi } from '@/infrastructure/http/public'
import { readDynamicElectricity } from './dynamic-electricity'

vi.mock('@/infrastructure/http/public', () => ({ requestApi: vi.fn() }))

describe('动态电力监测读取接口契约', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(requestApi).mockResolvedValue({} as never)
  })

  it('只对编码后的建筑路径读取已保存的年/月动态结果并传递取消信号', async () => {
    const controller = new AbortController()

    await readDynamicElectricity('B/01', 'MONTH', '2026-09', controller.signal)

    expect(requestApi).toHaveBeenCalledTimes(1)
    expect(requestApi).toHaveBeenCalledWith(expect.objectContaining({
      method: 'get',
      url: '/v1/carbon-management/buildings/B%2F01/dynamic-electricity',
      params: { periodType: 'MONTH', period: '2026-09' },
      signal: controller.signal,
    }))
  })
})
