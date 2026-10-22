import { beforeEach, describe, expect, it, vi } from 'vitest'
import { requestApi } from '@/infrastructure/http/public'
import { boundarySummaryPoliciesApi } from './boundary-summary-policies'

vi.mock('@/infrastructure/http/public', () => ({ requestApi: vi.fn() }))

describe('计量边界汇总口径接口契约', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(requestApi).mockResolvedValue({} as never)
  })

  it('按建筑读取口径，并只提交明确选择的边界、能源与独立审核意见', async () => {
    const request = {
      buildingId: 'B/01', meteringBoundaryId: 'MB/01', energyItemCode: 'ELECTRICITY',
      aggregationMode: 'MAIN_METER_TOTAL' as const, sourceType: 'FORMAL' as const,
      evidenceReference: '专业口径依据', effectiveFrom: '2026-01-01T00:00', effectiveTo: null,
    }

    await boundarySummaryPoliciesApi.list('B/01')
    await boundarySummaryPoliciesApi.create(request)
    await boundarySummaryPoliciesApi.approve('policy/01', { expectedRevision: 3, reviewComment: '独立审核通过' })

    expect(requestApi).toHaveBeenNthCalledWith(1, expect.objectContaining({
      method: 'get', url: '/v1/energy-boundary-summaries/policies', params: { buildingId: 'B/01' },
    }))
    expect(requestApi).toHaveBeenNthCalledWith(2, expect.objectContaining({
      method: 'post', url: '/v1/energy-boundary-summaries/policies', data: request,
    }))
    expect(requestApi).toHaveBeenNthCalledWith(3, expect.objectContaining({
      method: 'post', url: '/v1/energy-boundary-summaries/policies/policy%2F01/approve',
      data: { expectedRevision: 3, reviewComment: '独立审核通过' },
    }))
  })
})
