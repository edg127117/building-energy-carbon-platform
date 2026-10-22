import { beforeEach, describe, expect, it, vi } from 'vitest'
import { requestApi } from '@/infrastructure/http/public'
import { carbonResultsApi } from './carbon-results'

vi.mock('@/infrastructure/http/public', () => ({ requestApi: vi.fn() }))

describe('碳排放结果接口契约', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(requestApi).mockResolvedValue({} as never)
  })

  it('按建筑读取批次，并对批次标识编码后读取分类报告', async () => {
    await carbonResultsApi.listBatches('B/01')
    await carbonResultsApi.getClassificationReport('batch/01')

    expect(requestApi).toHaveBeenNthCalledWith(1, expect.objectContaining({
      method: 'get', url: '/v1/carbon-management/calculations', params: { buildingId: 'B/01' },
    }))
    expect(requestApi).toHaveBeenNthCalledWith(2, expect.objectContaining({
      method: 'get', url: '/v1/carbon-management/calculations/batch%2F01/classification-report',
    }))
  })

  it('只转发明确填写的正式计算、固定批次证据与重算独立审核请求', async () => {
    const calculation = {
      buildingId: 'B/01', periodType: 'MONTH' as const,
      startInclusive: '2026-01-01T00:00:00+08:00', endExclusive: '2026-02-01T00:00:00+08:00',
      timezoneId: 'Asia/Shanghai', resultNature: 'FORMAL' as const, idempotencyKey: 'calc-key-1',
    }

    await carbonResultsApi.runCalculation(calculation)
    await carbonResultsApi.getCalculation('batch/01')
    await carbonResultsApi.getEvidence('batch/01', 'item/01')
    await carbonResultsApi.submitManualRecalculation({
      buildingId: 'B/01', accountingYear: 2026, resultNature: 'FORMAL',
      reason: '更正已封账来源', organizationBoundary: 'ORG-1',
    })
    await carbonResultsApi.listRecalculations('B/01')
    await carbonResultsApi.approveRecalculation('recalc/01', { reviewComment: '已独立复核' })
    await carbonResultsApi.rejectRecalculation('recalc/02', { reviewComment: '证据不足，退回' })

    expect(requestApi).toHaveBeenNthCalledWith(1, expect.objectContaining({
      method: 'post', url: '/v1/carbon-management/calculations', data: calculation,
    }))
    expect(requestApi).toHaveBeenNthCalledWith(2, expect.objectContaining({
      method: 'get', url: '/v1/carbon-management/calculations/batch%2F01',
    }))
    expect(requestApi).toHaveBeenNthCalledWith(3, expect.objectContaining({
      method: 'get', url: '/v1/carbon-management/calculations/batch%2F01/items/item%2F01/evidence',
    }))
    expect(requestApi).toHaveBeenNthCalledWith(4, expect.objectContaining({
      method: 'post', url: '/v1/carbon-management/recalculations/manual', data: {
        buildingId: 'B/01', accountingYear: 2026, resultNature: 'FORMAL',
        reason: '更正已封账来源', organizationBoundary: 'ORG-1',
      },
    }))
    expect(requestApi).toHaveBeenNthCalledWith(5, expect.objectContaining({
      method: 'get', url: '/v1/carbon-management/recalculations', params: { buildingId: 'B/01' },
    }))
    expect(requestApi).toHaveBeenNthCalledWith(6, expect.objectContaining({
      method: 'post', url: '/v1/carbon-management/recalculations/recalc%2F01/approve', data: { reviewComment: '已独立复核' },
    }))
    expect(requestApi).toHaveBeenNthCalledWith(7, expect.objectContaining({
      method: 'post', url: '/v1/carbon-management/recalculations/recalc%2F02/reject', data: { reviewComment: '证据不足，退回' },
    }))
  })
})
