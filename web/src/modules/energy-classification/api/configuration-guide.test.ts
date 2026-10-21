import { beforeEach, describe, expect, it, vi } from 'vitest'
import { requestApi } from '@/infrastructure/http/public'
import { configurationGuideApi } from './configuration-guide'

vi.mock('@/infrastructure/http/public', () => ({ requestApi: vi.fn() }))

describe('配置引导接口契约', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(requestApi).mockResolvedValue({} as never)
  })

  it('按建筑和项目版本转义读取只读引导', async () => {
    const controller = new AbortController()

    await configurationGuideApi.get('B/01', 'P/01', controller.signal)

    expect(requestApi).toHaveBeenCalledWith(expect.objectContaining({
      method: 'get',
      url: '/v1/carbon-projects/buildings/B%2F01/versions/P%2F01/guide',
      signal: controller.signal,
    }))
  })
})
