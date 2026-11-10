import { beforeEach, describe, expect, it, vi } from 'vitest'
import { previewJointFlow, queryJointFlow, queryPreparedJointFlow } from './joint-flow'

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

  it('新增准备式大屏读取使用兼容路由并解包 READY 数据', async () => {
    const request = { buildingId: 'B/01', periodType: 'MONTH' as const, period: '2026-09' }
    api.mockResolvedValue({ contractVersion: 'CARBON_DISPLAY_QUERY_V1', kind: 'JOINT_FLOW', buildingId: 'B/01',
      queryKey: 'q1', sourceVersion: 'v1', state: 'READY', data: { state: 'READY' }, retryAfterSeconds: 2 })
    const controller = new AbortController()

    await expect(queryPreparedJointFlow(request, controller.signal)).resolves.toEqual({ state: 'READY' })
    expect(api).toHaveBeenCalledWith({ method: 'post', url: '/v1/carbon-management/joint-flow/summary-query', data: request, signal: expect.any(AbortSignal) })
  })
})
