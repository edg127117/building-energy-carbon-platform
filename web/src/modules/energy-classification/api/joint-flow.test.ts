import { beforeEach, describe, expect, it, vi } from 'vitest'
import { previewJointFlow, queryJointFlow } from './joint-flow'

const api = vi.hoisted(() => vi.fn())
vi.mock('@/infrastructure/http/public', () => ({ requestApi: api }))

beforeEach(() => api.mockReset())

describe('联合能碳流读取契约', () => {
  it('只传递建筑、冻结期间和可选配置版本，并保留取消信号', async () => {
    const request = { buildingId: 'B/01', periodType: 'YEAR' as const, period: '2026', throughMonth: '2026-09', configurationVersionId: 'CFG-1' }
    const controller = new AbortController()
    api.mockResolvedValue({ state: 'READY' })

    await queryJointFlow(request, controller.signal)

    expect(api).toHaveBeenCalledWith({
      method: 'post', url: '/v1/carbon-management/joint-flow/query', data: request, signal: controller.signal,
    })
  })

  it('草稿预览仍使用明确配置版本，不把草稿请求伪装成正式查询', async () => {
    const request = { buildingId: 'B-1', periodType: 'MONTH' as const, period: '2026-09', configurationVersionId: 'DRAFT-1' }
    api.mockResolvedValue({ classificationBasis: 'DRAFT_PREVIEW' })

    await previewJointFlow(request)

    expect(api).toHaveBeenCalledWith({ method: 'post', url: '/v1/carbon-management/joint-flow/preview', data: request, signal: undefined })
  })
})
